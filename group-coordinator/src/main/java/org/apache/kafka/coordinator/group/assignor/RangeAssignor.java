/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.coordinator.group.assignor;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.coordinator.group.api.assignor.ConsumerGroupPartitionAssignor;
import org.apache.kafka.coordinator.group.api.assignor.GroupAssignment;
import org.apache.kafka.coordinator.group.api.assignor.GroupSpec;
import org.apache.kafka.coordinator.group.api.assignor.MemberAssignment;
import org.apache.kafka.coordinator.group.api.assignor.MemberSubscription;
import org.apache.kafka.coordinator.group.api.assignor.PartitionAssignorException;
import org.apache.kafka.coordinator.group.api.assignor.SubscribedTopicDescriber;
import org.apache.kafka.coordinator.group.api.assignor.SubscriptionType;
import org.apache.kafka.coordinator.group.modern.MemberAssignmentImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A range assignor assigns contiguous partition ranges to members of a consumer group such that:
 * <ol>
 *   <li>Each subscribed member receives at least one partition from that topic.</li>
 *   <li>Each member receives the same partition number from every subscribed topic when co-partitioning is possible.</li>
 * </ol>
 *
 * Co-partitioning is possible when the below conditions are satisfied:
 * <ol>
 *   <li>All the members are subscribed to the same set of topics.</li>
 *   <li>All the topics have the same number of partitions.</li>
 * </ol>
 *
 * Co-partitioning is useful in performing joins on data streams.
 *
 * <p>For example, suppose there are two members M0 and M1, two topics T1 and T2, and each topic has 3 partitions.
 *
 * <p>The co-partitioned assignment will be:
 * <ul>
 * <li<code>    M0: [T1P0, T1P1, T2P0, T2P1]    </code></li>
 * <li><code>   M1: [T1P2, T2P2]                </code></li>
 * </ul>
 *
 * <p>Rack-aware assignment is used if both consumer and partition replica racks are available and
 * some partitions have replicas only on a subset of racks. We attempt to match consumer racks with
 * partition replica racks on a best-effort basis, prioritizing balanced assignment over rack alignment.
 * Topics with equal partition count and same set of subscribers guarantee co-partitioning by prioritizing
 * co-partitioning over rack alignment. In this case, aligning partition replicas of these topics on the
 * same racks will improve locality for consumers.
 *
 *
 * Since the introduction of static membership, we could leverage <code>member.instance.id</code> to make the
 * assignment behavior more sticky.
 * For the above example, after one rolling bounce, the group coordinator will attempt to assign new member Ids towards
 * members, for example if <code>M0</code> -&gt; <code>M3</code> <code>M1</code> -&gt; <code>M2</code>.
 *
 * <p>The assignment could be completely shuffled to:
 * <ul>
 * <li><code>   M3 (was M0): [T1P2, T2P2]               (before it was [T1P0, T1P1, T2P0, T2P1])  </code>
 * <li><code>   M2 (was M1): [T1P0, T1P1, T2P0, T2P1]   (before it was [T1P2, T2P2])  </code>
 * </ul>
 *
 * The assignment change was caused by the change of <code>member.id</code> relative order, and
 * can be avoided by setting the instance.id.
 * Members will have individual instance Ids <code>I0</code>, <code>I1</code>. As long as
 * 1. Number of members remain the same.
 * 2. Topic metadata doesn't change.
 * 3. Subscription pattern doesn't change for any member.
 *
 * <p>The assignment will always be:
 * <ul>
 * <li><code>   I0: [T1P0, T1P1, T2P0, T2P1]    </code>
 * <li><code>   I1: [T1P2, T2P2]                </code>
 * </ul>
 * <p>
 */
public class RangeAssignor implements ConsumerGroupPartitionAssignor {
    public static final String NAME = "range";

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Metadata for a topic including partition and subscription details.
     */
    private static class TopicMetadata {
        private final Uuid topicId;
        private final int numPartitions;
        private int numMembers;
        private int minQuota = -1;
        private int extraPartitions = -1;
        private int nextRange = 0;
        Map<Integer, Set<String>> partitionRacks;
        Set<Integer> unassignedPartitions;

        /**
         * Constructs a new TopicMetadata instance.
         *
         * @param topicId           The topic Id.
         * @param numPartitions     The number of partitions.
         * @param numMembers        The number of subscribed members.
         */
        private TopicMetadata(Uuid topicId, int numPartitions, int numMembers, Map<Integer, Set<String>> partitionRacks) {
            this.topicId = topicId;
            this.numPartitions = numPartitions;
            this.numMembers = numMembers;
            this.partitionRacks = partitionRacks;
            this.unassignedPartitions = new LinkedHashSet<>();
            for (int i = 0; i < numPartitions; i++) {
                unassignedPartitions.add(i);
            }
        }

        /**
         * Computes the minimum partition quota per member and the extra partitions, if not already computed.
         */
        private void maybeComputeQuota() {
            if (minQuota != -1) return;

            // The minimum number of partitions each member should receive for a balanced assignment.
            minQuota = numPartitions / numMembers;

            // Extra partitions to be distributed one to each member.
            extraPartitions = numPartitions % numMembers;
        }

        /**
         * Checks if the given partition's replicas match the member's rack.
         *
         * @param partition     The partition number.
         * @param memberRack    The rack Id of the member.
         * @return true if the member has no rack or the partition has a replica on the member's rack.
         */
        boolean racksMatch(int partition, Optional<String> memberRack) {
            if (memberRack.isEmpty()) {
                return true;
            }
            Set<String> replicaRacks = partitionRacks.get(partition);
            return replicaRacks != null && replicaRacks.contains(memberRack.get());
        }

        /**
         * Marks a partition as assigned.
         *
         * @param partition     The partition number to mark as assigned.
         */
        void markAssigned(int partition) {
            unassignedPartitions.remove(partition);
        }

        /**
         * Gets the set of unassigned partitions.
         *
         * @return The set of unassigned partition numbers.
         */
        Set<Integer> getUnassignedPartitions() {
            return unassignedPartitions;
        }

        @Override
        public String toString() {
            return "TopicMetadata(topicId=" + topicId +
                ", numPartitions=" + numPartitions +
                ", numMembers=" + numMembers +
                ", partitionRacks=" + partitionRacks +
                ", minQuota=" + minQuota +
                ", extraPartitions=" + extraPartitions +
                ", nextRange=" + nextRange +
                ')';
        }
    }

    /**
     * Assigns partitions to members of a homogeneous group. All members are subscribed to the same set of topics.
     * Assignment will be co-partitioned when all the topics have an equal number of partitions.
     */
    private GroupAssignment assignHomogeneousGroup(
        GroupSpec groupSpec,
        SubscribedTopicDescriber subscribedTopicDescriber
    ) throws PartitionAssignorException {
        List<String> memberIds = sortMemberIds(groupSpec);
        int numMembers = groupSpec.memberIds().size();

        MemberSubscription subs = groupSpec.memberSubscription(memberIds.get(0));

        // Collect member racks
        Map<String, Optional<String>> memberRacks = collectMemberRacks(groupSpec, memberIds);

        // Build topic metadata with partition rack information
        List<TopicMetadata> topics = buildTopicMetadata(
            subs.subscribedTopicIds(),
            numMembers,
            subscribedTopicDescriber
        );

        // Initialize assignments
        Map<String, Map<Uuid, Set<Integer>>> memberAssignments = initializeMemberAssignments(
            memberIds,
            topics.size()
        );

        // Compute quotas
        for (TopicMetadata topicMetadata : topics) {
            topicMetadata.maybeComputeQuota();
        }

        // Perform rack-aware assignment if applicable
        performRackAwareAssignment(topics, memberIds, memberRacks, memberAssignments);

        // Assign remaining partitions using standard range assignment
        return completeAssignment(groupSpec, memberIds, topics, memberAssignments);
    }

    /**
     * Collects rack information for all members.
     */
    private Map<String, Optional<String>> collectMemberRacks(
            GroupSpec groupSpec,
            List<String> memberIds
    ) {
        Map<String, Optional<String>> memberRacks = new LinkedHashMap<>();
        for (String memberId : memberIds) {
            memberRacks.put(memberId, groupSpec.memberSubscription(memberId).rackId());
        }
        return memberRacks;
    }

    /**
     * Builds topic metadata for all subscribed topics.
     */
    private List<TopicMetadata> buildTopicMetadata(
        Set<Uuid> subscribedTopicIds,
        int numMembers,
        SubscribedTopicDescriber subscribedTopicDescriber
    ) throws PartitionAssignorException {
        List<TopicMetadata> topics = new ArrayList<>(subscribedTopicIds.size());

        for (Uuid topicId : subscribedTopicIds) {
            int numPartitions = subscribedTopicDescriber.numPartitions(topicId);
            if (numPartitions == -1) {
                throw new PartitionAssignorException("Member is subscribed to a non-existent topic");
            }

            Map<Integer, Set<String>> partitionRacks = new HashMap<>();
            for (int partition = 0; partition < numPartitions; partition++) {
                Set<String> racks = subscribedTopicDescriber.racksForPartition(topicId, partition);
                if (!racks.isEmpty()) {
                    partitionRacks.put(partition, racks);
                }
            }

            topics.add(new TopicMetadata(topicId, numPartitions, numMembers, partitionRacks));
        }

        return topics;
    }

    /**
     * Initializes empty assignment maps for all members.
     */
    private Map<String, Map<Uuid, Set<Integer>>> initializeMemberAssignments(
        List<String> memberIds,
        int numTopics
    ) {
        Map<String, Map<Uuid, Set<Integer>>> memberAssignments = new HashMap<>();
        int memberAssignmentInitialCapacity = (int) ((numTopics / 0.75f) + 1);
        for (String memberId : memberIds) {
            memberAssignments.put(memberId, new HashMap<>(memberAssignmentInitialCapacity));
        }
        return memberAssignments;
    }

    /**
     * Performs rack-aware assignment if conditions are met.
     */
    private void performRackAwareAssignment(
        List<TopicMetadata> topics,
        List<String> memberIds,
        Map<String, Optional<String>> memberRacks,
        Map<String, Map<Uuid, Set<Integer>>> memberAssignments
    ) {
        boolean useRackAware = shouldUseRackAwareAssignment(memberRacks, topics);

        if (useRackAware) {
            if (topics.size() > 1 && areTopicsCoPartitioned(topics)) {
                assignCoPartitionedWithRackMatching(topics, memberIds, memberRacks, memberAssignments);
            } else {
                for (TopicMetadata topic : topics) {
                    assignTopicWithRackMatching(topic, memberIds, memberRacks, memberAssignments);
                }
            }
        }
    }

    /**
     * Completes the assignment by assigning remaining partitions and building the final GroupAssignment.
     */
    private GroupAssignment completeAssignment(
        GroupSpec groupSpec,
        List<String> memberIds,
        List<TopicMetadata> topics,
        Map<String, Map<Uuid, Set<Integer>>> memberAssignments
    ) {
        Map<String, MemberAssignment> assignments = new HashMap<>(
            (int) ((groupSpec.memberIds().size() / 0.75f) + 1)
        );

        for (String memberId : memberIds) {
            Map<Uuid, Set<Integer>> assignment = memberAssignments.get(memberId);
            for (TopicMetadata topicMetadata : topics) {
                topicMetadata.maybeComputeQuota();
                addPartitionsToAssignment(topicMetadata, assignment);
            }
            assignments.put(memberId, new MemberAssignmentImpl(assignment));
        }

        return new GroupAssignment(assignments);
    }

    /**
     * Determines if rack-aware assignment should be used.
     */
    private boolean shouldUseRackAwareAssignment(
            Map<String, Optional<String>> memberRacks,
            List<TopicMetadata> topics
    ) {
        // Check if any members have rack information
        Set<String> consumerRacks = memberRacks.values().stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());

        if (consumerRacks.isEmpty()) {
            return false;
        }

        // Check if any topics have partition rack information
        Set<String> allPartitionRacks = new HashSet<>();
        for (TopicMetadata topic : topics) {
            for (Set<String> racks : topic.partitionRacks.values()) {
                allPartitionRacks.addAll(racks);
            }
        }

        if (allPartitionRacks.isEmpty() || Collections.disjoint(consumerRacks, allPartitionRacks)) {
            return false;
        }

        // Check if some partitions have replicas only on a subset of racks
        // If all partitions have the same set of racks, rack-aware assignment doesn't help
        Set<Set<String>> uniqueRackSets = topics.stream()
                .flatMap(t -> t.partitionRacks.values().stream())
                .collect(Collectors.toSet());

        return uniqueRackSets.size() > 1;
    }

    /**
     * Checks if topics are co-partitioned (same number of partitions).
     */
    private boolean areTopicsCoPartitioned(List<TopicMetadata> topics) {
        if (topics.isEmpty()) {
            return false;
        }
        int numPartitions = topics.get(0).numPartitions;
        return topics.stream().allMatch(t -> t.numPartitions == numPartitions);
    }

    /**
     * Assigns co-partitioned topics with rack matching.
     * Attempts to assign the same partition number from all topics to the same member,
     * preferring members whose rack matches the partition replicas.
     */
    private void assignCoPartitionedWithRackMatching(
        List<TopicMetadata> topics,
        List<String> memberIds,
        Map<String, Optional<String>> memberRacks,
        Map<String, Map<Uuid, Set<Integer>>> memberAssignments
    ) {
        int numPartitions = topics.get(0).numPartitions;
        Set<String> remainingMembers = new LinkedHashSet<>(memberIds);
        Map<String, Integer> assignedCounts = new HashMap<>();
        for (String memberId : memberIds) {
            assignedCounts.put(memberId, 0);
        }

        // Try to assign each partition number to a member with rack match
        for (int partition = 0; partition < numPartitions; partition++) {
            final int p = partition;

            // Find a member whose rack matches all topics for this partition
            // and who still has capacity
            Optional<String> matchingMember = remainingMembers.stream()
                .filter(memberId -> {
                    // Check if member has capacity
                    TopicMetadata firstTopic = topics.get(0);
                    if (assignedCounts.get(memberId) < firstTopic.minQuota + (firstTopic.extraPartitions > 0 ? 1 : 0)) {
                        // Check if rack matches for all topics
                        return topics.stream().allMatch(t -> t.racksMatch(p, memberRacks.get(memberId)));
                    }
                    return false;
                })
                .findFirst();

            if (matchingMember.isPresent()) {
                String memberId = matchingMember.get();
                Map<Uuid, Set<Integer>> assignment = memberAssignments.get(memberId);

                // Assign this partition from all topics to this member
                for (TopicMetadata topic : topics) {
                    assignment.computeIfAbsent(topic.topicId, k -> new HashSet<>()).add(p);
                    topic.markAssigned(p);
                }

                // Update assigned count
                assignedCounts.put(memberId, assignedCounts.get(memberId) + 1);

                // Check if member reached capacity
                TopicMetadata firstTopic = topics.get(0);
                int memberQuota = firstTopic.minQuota + (assignedCounts.get(memberId) <= firstTopic.extraPartitions ? 1 : 0);
                if (assignedCounts.get(memberId) >= memberQuota) {
                    remainingMembers.remove(memberId);
                    if (remainingMembers.isEmpty()) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Assigns a single topic with rack matching.
     */
    private void assignTopicWithRackMatching(
        TopicMetadata topic,
        List<String> memberIds,
        Map<String, Optional<String>> memberRacks,
        Map<String, Map<Uuid, Set<Integer>>> memberAssignments
    ) {
        Map<String, Integer> assignedCounts = new HashMap<>();
        for (String memberId : memberIds) {
            assignedCounts.put(memberId, 0);
        }

        // Try to assign partitions to members with matching racks
        for (int partition : new ArrayList<>(topic.unassignedPartitions)) {
            Optional<String> matchingMember = memberIds.stream()
                .filter(memberId -> {
                    int quota = topic.minQuota + (assignedCounts.get(memberId) < topic.extraPartitions ? 1 : 0);
                    return assignedCounts.get(memberId) < quota && topic.racksMatch(partition, memberRacks.get(memberId));
                })
                .findFirst();

            if (matchingMember.isPresent()) {
                String memberId = matchingMember.get();
                memberAssignments.get(memberId).computeIfAbsent(topic.topicId, k -> new HashSet<>()).add(partition);
                topic.markAssigned(partition);
                assignedCounts.put(memberId, assignedCounts.get(memberId) + 1);
            }
        }
    }

    /**
     * Assigns partitions to members of a heterogeneous group. Not all members are subscribed to the same topics.
     */
    private GroupAssignment assignHeterogeneousGroup(
        GroupSpec groupSpec,
        SubscribedTopicDescriber subscribedTopicDescriber
    ) throws PartitionAssignorException {
        List<String> memberIds = sortMemberIds(groupSpec);

        Map<Uuid, TopicMetadata> topics = new HashMap<>();

        for (String memberId : memberIds) {
            MemberSubscription subs = groupSpec.memberSubscription(memberId);
            for (Uuid topicId : subs.subscribedTopicIds()) {
                TopicMetadata topicMetadata = topics.computeIfAbsent(topicId, __ -> {
                    int numPartitions = subscribedTopicDescriber.numPartitions(topicId);
                    if (numPartitions == -1) {
                        throw new PartitionAssignorException("Member is subscribed to a non-existent topic");
                    }

                    return new TopicMetadata(
                        topicId,
                        numPartitions,
                        0,
                        new HashMap<>()
                    );
                });
                topicMetadata.numMembers++;
            }
        }

        Map<String, MemberAssignment> assignments = new HashMap<>((int) ((groupSpec.memberIds().size() / 0.75f) + 1));

        for (String memberId : memberIds) {
            MemberSubscription subs = groupSpec.memberSubscription(memberId);
            Map<Uuid, Set<Integer>> assignment = new HashMap<>((int) ((subs.subscribedTopicIds().size() / 0.75f) + 1));
            for (Uuid topicId : subs.subscribedTopicIds()) {
                TopicMetadata metadata = topics.get(topicId);
                metadata.maybeComputeQuota();
                addPartitionsToAssignment(metadata, assignment);
            }
            assignments.put(memberId, new MemberAssignmentImpl(assignment));
        }

        return new GroupAssignment(assignments);
    }

    /**
     * Sorts members based on their instance Ids if available or by member Ids if not.
     *
     * Static members are placed first and non-static members follow.
     *
     * Prioritizing static members helps them retain their partitions, enhancing stickiness
     * and stability. Non-static members, which do not have guaranteed rejoining Ids, are placed
     * later, allowing for more dynamic and flexible partition assignments.
     *
     * @param groupSpec     The group specification containing the member information.
     * @return A sorted list of member Ids.
     */
    private List<String> sortMemberIds(
        GroupSpec groupSpec
    ) {
        List<String> sortedMemberIds = new ArrayList<>(groupSpec.memberIds());

        sortedMemberIds.sort((memberId1, memberId2) -> {
            Optional<String> instanceId1 = groupSpec.memberSubscription(memberId1).instanceId();
            Optional<String> instanceId2 = groupSpec.memberSubscription(memberId2).instanceId();

            if (instanceId1.isPresent() && instanceId2.isPresent()) {
                return instanceId1.get().compareTo(instanceId2.get());
            } else if (instanceId1.isPresent()) {
                return -1;
            } else if (instanceId2.isPresent()) {
                return 1;
            } else {
                return memberId1.compareTo(memberId2);
            }
        });
        return sortedMemberIds;
    }

    /**
     * Assigns a range of partitions to the specified topic based on the provided metadata.
     * This method assigns remaining unassigned partitions using the standard range assignment logic.
     *
     * @param topicMetadata         Metadata containing the topic details, including the number of partitions,
     *                              the next range to assign, minQuota, and extra partitions.
     * @param memberAssignment      Map from topic Id to the set of assigned partition Ids.
     */
    private void addPartitionsToAssignment(
        TopicMetadata topicMetadata,
        Map<Uuid, Set<Integer>> memberAssignment
    ) {
        Set<Integer> currentAssignment = memberAssignment.get(topicMetadata.topicId);
        int alreadyAssigned = currentAssignment != null ? currentAssignment.size() : 0;

        int quota = topicMetadata.minQuota;
        // Adjust quota to account for extra partitions if available.
        if (topicMetadata.extraPartitions > 0) {
            quota++;
            topicMetadata.extraPartitions--;
        }

        int neededPartitions = quota - alreadyAssigned;
        if (neededPartitions <= 0) {
            return;
        }

        // Assign remaining unassigned partitions if any
        Set<Integer> unassigned = topicMetadata.unassignedPartitions;
        if (!unassigned.isEmpty()) {
            List<Integer> partitionsToAssign = unassigned.stream()
                .limit(neededPartitions)
                .collect(Collectors.toList());

            if (!partitionsToAssign.isEmpty()) {
                Set<Integer> assignment = memberAssignment.computeIfAbsent(
                    topicMetadata.topicId,
                    k -> new HashSet<>()
                );
                assignment.addAll(partitionsToAssign);
                partitionsToAssign.forEach(topicMetadata::markAssigned);
                return;
            }
        }

        // Fallback to range-based assignment for remaining partitions
        int start = topicMetadata.nextRange;
        int end = Math.min(start + neededPartitions, topicMetadata.numPartitions);

        topicMetadata.nextRange = end;

        if (start < end) {
            memberAssignment.put(topicMetadata.topicId, new RangeSet(start, end));
        }
    }

    /**
     * Assigns partitions to members based on their topic subscriptions and the properties of a range assignor:
     *
     * @param groupSpec                     The group specification containing the member information.
     * @param subscribedTopicDescriber      The describer for subscribed topics to get the number of partitions.
     * @return The group's assignment with the partition assignments for each member.
     * @throws PartitionAssignorException if any member is subscribed to a non-existent topic.
     */
    @Override
    public GroupAssignment assign(
        GroupSpec groupSpec,
        SubscribedTopicDescriber subscribedTopicDescriber
    ) throws PartitionAssignorException {
        if (groupSpec.memberIds().isEmpty()) {
            return new GroupAssignment(Map.of());
        } else if (groupSpec.subscriptionType() == SubscriptionType.HOMOGENEOUS) {
            return assignHomogeneousGroup(groupSpec, subscribedTopicDescriber);
        } else {
            return assignHeterogeneousGroup(groupSpec, subscribedTopicDescriber);
        }
    }
}
