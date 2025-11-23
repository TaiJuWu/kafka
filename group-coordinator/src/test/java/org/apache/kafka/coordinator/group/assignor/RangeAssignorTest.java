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
import org.apache.kafka.common.metadata.PartitionRecord;
import org.apache.kafka.common.metadata.RegisterBrokerRecord;
import org.apache.kafka.common.metadata.TopicRecord;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetadataImage;
import org.apache.kafka.coordinator.common.runtime.KRaftCoordinatorMetadataImage;
import org.apache.kafka.coordinator.common.runtime.MetadataImageBuilder;
import org.apache.kafka.coordinator.group.api.assignor.GroupAssignment;
import org.apache.kafka.coordinator.group.api.assignor.GroupSpec;
import org.apache.kafka.coordinator.group.api.assignor.MemberAssignment;
import org.apache.kafka.coordinator.group.api.assignor.PartitionAssignorException;
import org.apache.kafka.coordinator.group.api.assignor.SubscribedTopicDescriber;
import org.apache.kafka.coordinator.group.api.assignor.SubscriptionType;
import org.apache.kafka.coordinator.group.modern.Assignment;
import org.apache.kafka.coordinator.group.modern.GroupSpecImpl;
import org.apache.kafka.coordinator.group.modern.MemberAssignmentImpl;
import org.apache.kafka.coordinator.group.modern.MemberSubscriptionAndAssignmentImpl;
import org.apache.kafka.coordinator.group.modern.SubscribedTopicDescriberImpl;
import org.apache.kafka.image.MetadataDelta;
import org.apache.kafka.image.MetadataImage;
import org.apache.kafka.image.MetadataProvenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.apache.kafka.coordinator.group.AssignmentTestUtil.invertedTargetAssignment;
import static org.apache.kafka.coordinator.group.AssignmentTestUtil.mkAssignment;
import static org.apache.kafka.coordinator.group.AssignmentTestUtil.mkTopicAssignment;
import static org.apache.kafka.coordinator.group.api.assignor.SubscriptionType.HETEROGENEOUS;
import static org.apache.kafka.coordinator.group.api.assignor.SubscriptionType.HOMOGENEOUS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RangeAssignorTest {
    private final RangeAssignor assignor = new RangeAssignor();
    private final Uuid topic1Uuid = Uuid.randomUuid();
    private final String topic1Name = "topic1";
    private final Uuid topic2Uuid = Uuid.randomUuid();
    private final String topic2Name = "topic2";
    private final Uuid topic3Uuid = Uuid.randomUuid();
    private final String topic3Name = "topic3";
    private final String memberA = "A";
    private final String memberB = "B";
    private final String memberC = "C";

    @ParameterizedTest
    @CsvSource({
        "HOMOGENEOUS, false",
        "HOMOGENEOUS, true",
        "HETEROGENEOUS, false",
        "HETEROGENEOUS, true"
    })
    public void testReassignmentStickiness(SubscriptionType subscriptionType, boolean rackAware) {
        CommonAssignorTests.testReassignmentStickiness(assignor, subscriptionType, rackAware);
    }

    @Test
    public void testOneMemberNoTopic() {
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            CoordinatorMetadataImage.EMPTY
        );

        Map<String, MemberSubscriptionAndAssignmentImpl> members = Map.of(
            memberA,
            new MemberSubscriptionAndAssignmentImpl(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                Assignment.EMPTY
            )
        );

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            Map.of()
        );

        GroupAssignment groupAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        Map<String, MemberAssignment> expectedAssignment = Map.of(
            memberA,
            new MemberAssignmentImpl(Map.of())
        );

        assertEquals(expectedAssignment, groupAssignment.members());
    }

    @Test
    public void testOneMemberSubscribedToNonExistentTopic() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .build();
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        Map<String, MemberSubscriptionAndAssignmentImpl> members = Map.of(
            memberA,
            new MemberSubscriptionAndAssignmentImpl(
                Optional.empty(),
                Optional.empty(),
                Set.of(topic2Uuid),
                Assignment.EMPTY
            )
        );

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            Map.of()
        );

        assertThrows(PartitionAssignorException.class,
            () -> assignor.assign(groupSpec, subscribedTopicMetadata));
    }

    @Test
    public void testFirstAssignmentTwoMembersTwoTopicsSameSubscriptions() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .addTopic(topic3Uuid, topic3Name, 2)
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic3Uuid),
            Assignment.EMPTY
        ));

        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic3Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0, 1),
            mkTopicAssignment(topic3Uuid, 0)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 2),
            mkTopicAssignment(topic3Uuid, 1)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testFirstAssignmentThreeMembersThreeTopicsDifferentSubscriptions() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .addTopic(topic2Uuid, topic2Name, 3)
            .addTopic(topic3Uuid, topic3Name, 2)
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic3Uuid),
            Assignment.EMPTY
        ));

        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic2Uuid, topic3Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HETEROGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0, 1, 2),
            mkTopicAssignment(topic2Uuid, 0, 1)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic3Uuid, 0)
        ));
        expectedAssignment.put(memberC, mkAssignment(
            mkTopicAssignment(topic2Uuid, 2),
            mkTopicAssignment(topic3Uuid, 1)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testFirstAssignmentNumMembersGreaterThanNumPartitions() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .addTopic(topic3Uuid, topic3Name, 2)
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic3Uuid),
            Assignment.EMPTY
        ));

        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic3Uuid),
            Assignment.EMPTY
        ));

        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic3Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        // Topic 3 has 2 partitions but three Members subscribed to it - one of them will not get a partition.
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0),
            mkTopicAssignment(topic3Uuid, 0)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 1),
            mkTopicAssignment(topic3Uuid, 1)
        ));
        expectedAssignment.put(memberC, mkAssignment(
            mkTopicAssignment(topic1Uuid, 2)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testStaticMembership() throws PartitionAssignorException {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .build();
        SubscribedTopicDescriber subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.of("instanceA"),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));
        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.of("instanceB"),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            SubscriptionType.HOMOGENEOUS,
            invertedTargetAssignment(members)
        );

        GroupAssignment initialAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        // Remove static memberA and add it back with a different member Id but same instance Id.
        members.remove(memberA);
        members.put("memberA1", new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.of("instanceA"),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        groupSpec = new GroupSpecImpl(
            members,
            SubscriptionType.HOMOGENEOUS,
            invertedTargetAssignment(members)
        );

        GroupAssignment reassignedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        // Assert that the assignment did not change
        assertEquals(
            initialAssignment.members().get(memberA).partitions(),
            reassignedAssignment.members().get("memberA1").partitions()
        );

        assertEquals(
            initialAssignment.members().get(memberB).partitions(),
            reassignedAssignment.members().get(memberB).partitions()
        );
    }

    @Test
    public void testMixedStaticMembership() throws PartitionAssignorException {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 5)
            .build();
        SubscribedTopicDescriber subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        // Initialize members with instance Ids.
        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.of("instanceA"),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));
        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.of("instanceC"),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        // Initialize member without an instance Id.
        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            SubscriptionType.HOMOGENEOUS,
            invertedTargetAssignment(members)
        );

        GroupAssignment initialAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        // Remove memberA and add it back with a different member Id but same instance Id.
        members.remove(memberA);
        members.put("memberA1", new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.of("instanceA"),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        groupSpec = new GroupSpecImpl(
            members,
            SubscriptionType.HOMOGENEOUS,
            invertedTargetAssignment(members)
        );

        GroupAssignment reassignedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        // Assert that the assignments did not change.
        assertEquals(
            initialAssignment.members().get(memberA).partitions(),
            reassignedAssignment.members().get("memberA1").partitions()
        );

        assertEquals(
            initialAssignment.members().get(memberB).partitions(),
            reassignedAssignment.members().get(memberB).partitions()
        );

        assertEquals(
            initialAssignment.members().get(memberC).partitions(),
            reassignedAssignment.members().get(memberC).partitions()
        );
    }

    @Test
    public void testReassignmentNumMembersGreaterThanNumPartitionsWhenOneMemberAdded() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 2)
            .addTopic(topic2Uuid, topic2Name, 2)
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            new Assignment(mkAssignment(
                mkTopicAssignment(topic1Uuid, 0),
                mkTopicAssignment(topic2Uuid, 0)
            ))
        ));

        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            new Assignment(mkAssignment(
                mkTopicAssignment(topic1Uuid, 1),
                mkTopicAssignment(topic2Uuid, 1)
            ))
        ));

        // Add a new Member to trigger a re-assignment
        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0),
            mkTopicAssignment(topic2Uuid, 0)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 1),
            mkTopicAssignment(topic2Uuid, 1)
        ));
        // Member C shouldn't get any assignment.
        expectedAssignment.put(memberC, Map.of());

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testReassignmentWhenOnePartitionAddedForTwoMembersTwoTopics() {
        // Simulating adding a partition - originally T1 -> 3 Partitions and T2 -> 3 Partitions
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 4)
            .addTopic(topic2Uuid, topic2Name, 4)
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            new Assignment(mkAssignment(
                mkTopicAssignment(topic1Uuid, 0, 1),
                mkTopicAssignment(topic2Uuid, 0, 1)
            ))
        ));

        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            new Assignment(mkAssignment(
                mkTopicAssignment(topic1Uuid, 2),
                mkTopicAssignment(topic2Uuid, 2)
            ))
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0, 1),
            mkTopicAssignment(topic2Uuid, 0, 1)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 2, 3),
            mkTopicAssignment(topic2Uuid, 2, 3)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testReassignmentWhenOneMemberAddedAfterInitialAssignmentWithTwoMembersTwoTopics() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .addTopic(topic2Uuid, topic2Name, 3)
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            new Assignment(mkAssignment(
                mkTopicAssignment(topic1Uuid, 0, 1),
                mkTopicAssignment(topic2Uuid, 0, 1)
            ))
        ));

        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            new Assignment(mkAssignment(
                mkTopicAssignment(topic1Uuid, 2),
                mkTopicAssignment(topic2Uuid, 2)
            ))
        ));

        // Add a new Member to trigger a re-assignment
        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0),
            mkTopicAssignment(topic2Uuid, 0)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 1),
            mkTopicAssignment(topic2Uuid, 1)
        ));
        expectedAssignment.put(memberC, mkAssignment(
            mkTopicAssignment(topic1Uuid, 2),
            mkTopicAssignment(topic2Uuid, 2)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testReassignmentWhenOneMemberAddedAndOnePartitionAfterInitialAssignmentWithTwoMembersTwoTopics() {
        // Add a new partition to topic 1, initially T1 -> 3 partitions
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 4)
            .addTopic(topic2Uuid, topic2Name, 3)
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            new Assignment(mkAssignment(
                mkTopicAssignment(topic1Uuid, 0, 1),
                mkTopicAssignment(topic2Uuid, 0, 1)
            ))
        ));

        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            new Assignment(mkAssignment(
                mkTopicAssignment(topic1Uuid, 2),
                mkTopicAssignment(topic2Uuid, 2)
            ))
        ));

        // Add a new Member to trigger a re-assignment
        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HETEROGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0, 1),
            mkTopicAssignment(topic2Uuid, 0, 1)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 2),
            mkTopicAssignment(topic2Uuid, 2)
        ));
        expectedAssignment.put(memberC, mkAssignment(
            mkTopicAssignment(topic1Uuid, 3)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testReassignmentWhenOneMemberRemovedAfterInitialAssignmentWithTwoMembersTwoTopics() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .addTopic(topic2Uuid, topic2Name, 3)
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        // Member A was removed

        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            new Assignment(mkAssignment(
                mkTopicAssignment(topic1Uuid, 2),
                mkTopicAssignment(topic2Uuid, 2)
            ))
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0, 1, 2),
            mkTopicAssignment(topic2Uuid, 0, 1, 2)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testReassignmentWhenMultipleSubscriptionsRemovedAfterInitialAssignmentWithThreeMembersTwoTopics() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .addTopic(topic2Uuid, topic2Name, 3)
            .addTopic(topic3Uuid, topic3Name, 2)
            .build();

        // Let initial subscriptions be A -> T1, T2 // B -> T2 // C -> T2, T3
        // Change the subscriptions to A -> T1 // B -> T1, T2, T3 // C -> T2
        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid),
            new Assignment(mkAssignment(
                mkTopicAssignment(topic1Uuid, 0, 1, 2),
                mkTopicAssignment(topic2Uuid, 0)
            ))
        ));

        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid, topic3Uuid),
            new Assignment(mkAssignment(
                mkTopicAssignment(topic2Uuid, 1)
            ))
        ));

        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic2Uuid),
            new Assignment(mkAssignment(
                mkTopicAssignment(topic2Uuid, 2),
                mkTopicAssignment(topic3Uuid, 0, 1)
            ))
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HETEROGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0, 1)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 2),
            mkTopicAssignment(topic2Uuid, 0, 1),
            mkTopicAssignment(topic3Uuid, 0, 1)
        ));
        expectedAssignment.put(memberC, mkAssignment(
            mkTopicAssignment(topic2Uuid, 2)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testRackAwareAssignmentWithCoPartitionedTopics() {
        // Setup: 2 co-partitioned topics (same partition count), 3 partitions each
        // 3 members on different racks
        // Partition replicas distributed across racks to test rack matching
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .addTopic(topic2Uuid, topic2Name, 3)
            .addRacks()  // Adds rack0-rack3 for brokers 0-3
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        // Member A on rack0 (should get partitions with replicas on rack0)
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack0"),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        // Member B on rack1 (should get partitions with replicas on rack1)
        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack1"),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        // Member C on rack2 (should get partitions with replicas on rack2)
        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack2"),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        // With addRacks(), partition replicas are:
        // P0: [0, 1] -> racks [rack0, rack1]
        // P1: [1, 2] -> racks [rack1, rack2]
        // P2: [2, 3] -> racks [rack2, rack3]
        // Expected: rack-aware matching with co-partitioning maintained
        // A (rack0) should get P0 from both topics (has rack0)
        // B (rack1) should get P1 from both topics (has rack1)
        // C (rack2) should get P2 from both topics (has rack2)
        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0),
            mkTopicAssignment(topic2Uuid, 0)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 1),
            mkTopicAssignment(topic2Uuid, 1)
        ));
        expectedAssignment.put(memberC, mkAssignment(
            mkTopicAssignment(topic1Uuid, 2),
            mkTopicAssignment(topic2Uuid, 2)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testRackAwareAssignmentWithCoPartitionedTopicsNoConsumerRackMatch() {
        // Setup: 2 co-partitioned topics with 4 partitions each, replicas only on rack0/rack1
        // Member C is on rack2 (no matching replica racks). Ensure no duplicate assignment occurs.
        MetadataDelta delta = new MetadataDelta(MetadataImage.EMPTY);
        delta.replay(new RegisterBrokerRecord().setBrokerId(0).setRack("rack0"));
        delta.replay(new RegisterBrokerRecord().setBrokerId(1).setRack("rack1"));

        delta.replay(new TopicRecord().setTopicId(topic1Uuid).setName(topic1Name));
        delta.replay(new TopicRecord().setTopicId(topic2Uuid).setName(topic2Name));
        for (int partition = 0; partition < 4; partition++) {
            delta.replay(new PartitionRecord()
                .setTopicId(topic1Uuid)
                .setPartitionId(partition)
                .setReplicas(List.of(0, 1)));
            delta.replay(new PartitionRecord()
                .setTopicId(topic2Uuid)
                .setPartitionId(partition)
                .setReplicas(List.of(0, 1)));
        }

        SubscribedTopicDescriber subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(delta.apply(new MetadataProvenance(0, 0, 0L, true)))
        );

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack0"),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack1"),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack2"),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0, 1),
            mkTopicAssignment(topic2Uuid, 0, 1)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 2),
            mkTopicAssignment(topic2Uuid, 2)
        ));
        expectedAssignment.put(memberC, mkAssignment(
            mkTopicAssignment(topic1Uuid, 3),
            mkTopicAssignment(topic2Uuid, 3)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testRackAwareAssignmentWithSingleTopic() {
        // Setup: Single topic with 4 partitions, 2 members on different racks
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 4)
            .addRacks()
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        // Member A on rack0
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack0"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        // Member B on rack2
        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack2"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        // Partition replicas:
        // P0: [0, 1] -> racks [rack0, rack1]
        // P1: [1, 2] -> racks [rack1, rack2]
        // P2: [2, 3] -> racks [rack2, rack3]
        // P3: [3, 0] -> racks [rack3, rack0]
        // Member A (rack0) should get P0, P3 (both have rack0)
        // Member B (rack2) should get P1, P2 (both have rack2)
        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0, 3)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 1, 2)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testRackAwareAssignmentWithHeterogeneousSubscriptions() {
        // Setup: 3 topics, members with different subscriptions and racks
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .addTopic(topic2Uuid, topic2Name, 2)
            .addTopic(topic3Uuid, topic3Name, 2)
            .addRacks()
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        // Member A on rack0, subscribes to topic1
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack0"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        // Member B on rack1, subscribes to topic2
        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack1"),
            Optional.empty(),
            Set.of(topic2Uuid),
            Assignment.EMPTY
        ));

        // Member C on rack2, subscribes to topic3
        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack2"),
            Optional.empty(),
            Set.of(topic3Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HETEROGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        // With addRacks(), partition replicas for each topic are:
        // Topic1 (3 partitions): P0: [0,1] -> racks [rack0, rack1], P1: [1,2] -> racks [rack1, rack2], P2: [2,3] -> racks [rack2, rack3]
        // Topic2 (2 partitions): P0: [0,1] -> racks [rack0, rack1], P1: [1,2] -> racks [rack1, rack2]
        // Topic3 (2 partitions): P0: [0,1] -> racks [rack0, rack1], P1: [1,2] -> racks [rack1, rack2]
        //
        // memberA (rack0) subscribes to topic1: gets all, P0 matches rack0
        // memberB (rack1) subscribes to topic2: gets all, both P0 and P1 match rack1
        // memberC (rack2) subscribes to topic3: gets all, P1 matches rack2
        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0, 1, 2)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic2Uuid, 0, 1)
        ));
        expectedAssignment.put(memberC, mkAssignment(
            mkTopicAssignment(topic3Uuid, 0, 1)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testNoRackAwareAssignmentWhenNoRackInfo() {
        // Setup: Members without rack information should fall back to standard assignment
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .addTopic(topic2Uuid, topic2Name, 3)
            .addRacks()  // Racks exist for partitions but members have no rack info
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        // Both members have no rack information
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),  // No rack
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),  // No rack
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        // Should fall back to standard range assignment
        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0, 1),
            mkTopicAssignment(topic2Uuid, 0, 1)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 2),
            mkTopicAssignment(topic2Uuid, 2)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testNoRackAwareAssignmentWhenNoPartitionRackInfo() {
        // Setup: Members with rack info but partitions without rack info
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .addTopic(topic2Uuid, topic2Name, 3)
            // Don't call addRacks() - no rack info for brokers/partitions
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack0"),  // Member has rack but partitions don't
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack1"),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        // Should fall back to standard range assignment
        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0, 1),
            mkTopicAssignment(topic2Uuid, 0, 1)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 2),
            mkTopicAssignment(topic2Uuid, 2)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testPartialRackMatching() {
        // Setup: 4 partitions, 3 members where only some can get rack matches
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 4)
            .addRacks()
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        // Member A on rack0 (can match P0 and P3)
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack0"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        // Member B on rack1 (can match P0 and P1)
        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack1"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        // Member C on rack5 (no replicas on rack5, so no rack match possible)
        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack5"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        // Partition replicas:
        // P0: [0, 1] -> racks [rack0, rack1]
        // P1: [1, 2] -> racks [rack1, rack2]
        // P2: [2, 3] -> racks [rack2, rack3]
        // P3: [3, 0] -> racks [rack3, rack0]
        // Expected: Each member gets ~1-2 partitions (4 partitions / 3 members)
        // Rack-aware will try to match, but balance is also important
        // A (rack0) can match P0, P3
        // B (rack1) can match P0, P1
        // C (rack5) cannot match any
        // Standard range would give: A=[0,1], B=[2], C=[3]
        // Rack-aware tries to improve: A gets P0 (matches rack0), but P2 for balance
        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0, 2)  // P0 has rack0, P2 for balance
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 1)  // P1 has rack1
        ));
        expectedAssignment.put(memberC, mkAssignment(
            mkTopicAssignment(topic1Uuid, 3)  // Gets remaining
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    @Test
    public void testRackAwareWithMixedRackInfoMembers() {
        // Setup: Some members with rack info, some without
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 3)
            .addTopic(topic2Uuid, topic2Name, 3)
            .addRacks()
            .build();

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();

        // Member A with rack info
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack0"),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        // Member B without rack info (should match all partitions)
        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.empty(),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        // Member C with rack info
        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("rack2"),
            Optional.empty(),
            Set.of(topic1Uuid, topic2Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        GroupAssignment computedAssignment = assignor.assign(
            groupSpec,
            subscribedTopicMetadata
        );

        // Members with matching racks should get preference for those partitions
        // Member without rack can match any partition
        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment = new HashMap<>();
        expectedAssignment.put(memberA, mkAssignment(
            mkTopicAssignment(topic1Uuid, 0),  // P0 has rack0
            mkTopicAssignment(topic2Uuid, 0)
        ));
        expectedAssignment.put(memberB, mkAssignment(
            mkTopicAssignment(topic1Uuid, 1),  // Can match any
            mkTopicAssignment(topic2Uuid, 1)
        ));
        expectedAssignment.put(memberC, mkAssignment(
            mkTopicAssignment(topic1Uuid, 2),  // P2 has rack2
            mkTopicAssignment(topic2Uuid, 2)
        ));

        assertAssignment(expectedAssignment, computedAssignment);
    }

    /**
     * Asserts that the computed group assignment matches the expected assignment.
     *
     * @param expectedAssignment       A map representing the expected assignment for each member.
     *                                 The key is the member Id and the value is another map where
     *                                 the key is the topic Uuid and the value is a set of assigned partition Ids.
     * @param computedGroupAssignment  The computed group assignment to be checked against the expected assignment.
     *                                 Contains the actual assignments for each member.
     */
    private void assertAssignment(
        Map<String, Map<Uuid, Set<Integer>>> expectedAssignment,
        GroupAssignment computedGroupAssignment
    ) {
        assertEquals(expectedAssignment.size(), computedGroupAssignment.members().size());
        for (String memberId : computedGroupAssignment.members().keySet()) {
            Map<Uuid, Set<Integer>> computedAssignmentForMember = computedGroupAssignment.members().get(memberId).partitions();
            assertEquals(expectedAssignment.get(memberId), computedAssignmentForMember);
        }
    }
}
