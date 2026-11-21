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
package kafka.server.mirror;

import kafka.server.BrokerServer;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewPartitionReassignment;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.ElectionType;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.ReadOnlyTopicException;
import org.apache.kafka.common.message.ReadMirrorStatesRequestData;
import org.apache.kafka.common.message.ReadMirrorStatesResponseData;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.test.KafkaClusterTestKit;
import org.apache.kafka.common.test.TestKitNodes;
import org.apache.kafka.server.common.MetadataVersion;
import org.apache.kafka.server.mirror.MirrorPartitionState;
import org.apache.kafka.storage.internals.log.LogConfig;
import org.apache.kafka.storage.internals.log.LogSegment;
import org.apache.kafka.storage.internals.log.UnifiedLog;
import org.apache.kafka.test.TestUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Our KIP-1279 implementation: the leader of a destination partition copies the record batches of the source
 * partition as they are, the destination topic is read-only while it is mirrored, the destination leader epoch stays
 * at least the source leader epoch, and the destination truncates when the source has an unclean leader election.
 */
@Timeout(300)
public class ClusterMirrorIntegrationTest {
    private static final String MIRROR = "m1";
    private static final String TOPIC = "mirrored";
    private static final int PARTITIONS = 2;

    private KafkaClusterTestKit source;
    private KafkaClusterTestKit destination;
    private Admin sourceAdmin;
    private Admin destinationAdmin;

    @BeforeEach
    public void setUp() throws Exception {
        source = startCluster(Map.of());
        // Mirror topics are created with the default replication factor of the destination cluster.
        destination = startCluster(Map.of("mirror.metadata.refresh.interval.ms", "1000", "default.replication.factor", "3"));
        sourceAdmin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, source.bootstrapServers()));
        destinationAdmin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, destination.bootstrapServers()));
        sourceAdmin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 3))).all().get();
        // The destination topic is created by StartMirrorTopics, with the topic id of the source topic.
        destinationAdmin.createClusterMirror(MIRROR, Map.of("bootstrap.servers", source.bootstrapServers())).all().get();
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (AutoCloseable closeable : new AutoCloseable[]{sourceAdmin, destinationAdmin, destination, source}) {
            if (closeable != null) closeable.close();
        }
    }

    private static KafkaClusterTestKit startCluster(Map<String, String> props) throws Exception {
        KafkaClusterTestKit.Builder builder = new KafkaClusterTestKit.Builder(new TestKitNodes.Builder()
            .setCombined(false)
            .setNumBrokerNodes(3)
            .setNumControllerNodes(1)
            .setBootstrapMetadataVersion(MetadataVersion.IBP_4_5_IV1)
            .build());
        builder.setConfigProp("unstable.feature.versions.enable", "true");
        // The ACLs are synchronized between the clusters; the clients of the tests are super users.
        builder.setConfigProp("authorizer.class.name", "org.apache.kafka.metadata.authorizer.StandardAuthorizer");
        builder.setConfigProp("super.users", "User:ANONYMOUS");
        props.forEach(builder::setConfigProp);
        KafkaClusterTestKit cluster = builder.build();
        cluster.format();
        cluster.startup();
        cluster.waitForReadyBrokers();
        return cluster;
    }

    @Test
    public void testMirrorCopiesBatchesAsTheyAre() throws Exception {
        produceMixedBatches();
        startMirroring();
        waitForMirror();

        for (int partition = 0; partition < PARTITIONS; partition++) {
            TopicPartition tp = new TopicPartition(TOPIC, partition);
            List<String> sourceBatches = batches(source, tp);
            assertTrue(sourceBatches.size() > 5, "too few batches: " + sourceBatches);
            // Every replica of the destination partition has the batches of the source leader.
            for (BrokerServer broker : destination.brokers().values()) {
                assertEquals(sourceBatches, batches(broker, tp), "replica " + broker.config().brokerId() + " of " + tp);
            }
        }
        assertEquals(consume(source, "read_committed"), consume(destination, "read_committed"));
        assertEquals(consume(source, "read_uncommitted"), consume(destination, "read_uncommitted"));
    }

    @Test
    public void testMirroredTopicIsReadOnlyUntilStopped() throws Exception {
        produce(source, 0, "before", 10);
        startMirroring();
        waitForMirror();

        ExecutionException e = assertThrows(ExecutionException.class, () -> produce(destination, 0, "local", 1));
        assertInstanceOf(ReadOnlyTopicException.class, e.getCause());

        setMirrorState(LogConfig.MIRROR_STATE_STOPPED);
        TestUtils.waitForCondition(() -> {
            try {
                produce(destination, 0, "local", 1);
                return true;
            } catch (ExecutionException ex) {
                return false;
            }
        }, "the destination topic did not become writable");
        long destinationEnd = endOffsets(destinationAdmin).get(new TopicPartition(TOPIC, 0));

        // The records written to the source after the mirroring stopped are not copied.
        produce(source, 0, "after", 10);
        Thread.sleep(3000);
        assertEquals(destinationEnd, endOffsets(destinationAdmin).get(new TopicPartition(TOPIC, 0)));
    }

    @Test
    public void testLogStartOffsetFollowsSource() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        produce(source, 0, "r", 100);
        startMirroring();
        waitForMirror();

        sourceAdmin.deleteRecords(Map.of(tp, RecordsToDelete.beforeOffset(60))).all().get();
        produce(source, 0, "more", 1);
        TestUtils.waitForCondition(() -> leaderLog(destination, tp).logStartOffset() == 60,
            () -> "the log start offset of the destination is " + leaderLog(destination, tp).logStartOffset());
    }

    @Test
    public void testMirrorStartsFromSourceLogStartOffset() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        produce(source, 0, "r", 100);
        sourceAdmin.deleteRecords(Map.of(tp, RecordsToDelete.beforeOffset(40))).all().get();
        startMirroring();
        waitForMirror();

        assertEquals(40, leaderLog(destination, tp).logStartOffset());
        assertEquals(batches(source, tp), batches(destination, tp));
    }

    @Test
    public void testNewDestinationLeaderContinues() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        produce(source, 0, "first", 50);
        startMirroring();
        waitForMirror();

        int oldLeader = leader(destinationAdmin, tp);
        destination.brokers().get(oldLeader).shutdown();
        TestUtils.waitForCondition(() -> leader(destinationAdmin, tp) != oldLeader, "no new destination leader");
        produce(source, 0, "second", 50);
        waitForMirror();
        destination.brokers().get(oldLeader).startup();

        assertEquals(batches(source, tp), batches(destination, tp));
    }

    /**
     * The destination leader epoch is bumped ahead of the source leader epoch (DLE >= SLE), so the mirroring goes on
     * when the source partition elects new leaders, without a destination leader election.
     */
    @Test
    public void testDestinationEpochStaysAboveSourceEpoch() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        produce(source, 0, "first", 20);
        startMirroring();
        waitForMirror();
        int destinationLeader = leader(destinationAdmin, tp);

        for (int election = 0; election < 3; election++) {
            int sourceLeader = leader(sourceAdmin, tp);
            source.brokers().get(sourceLeader).shutdown();
            TestUtils.waitForCondition(() -> leader(sourceAdmin, tp) != sourceLeader, "no new source leader");
            source.brokers().get(sourceLeader).startup();
            produce(source, 0, "election-" + election, 20);
            waitForMirror();
            assertTrue(leaderEpoch(destination, tp) >= leaderEpoch(source, tp),
                "destination epoch " + leaderEpoch(destination, tp) + " < source epoch " + leaderEpoch(source, tp));
        }
        assertEquals(destinationLeader, leader(destinationAdmin, tp), "the destination leader changed");
        assertEquals(batches(source, tp), batches(destination, tp));
    }

    @Test
    public void testSourceUncleanLeaderElectionFailsMirrorByDefault() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        long divergence = uncleanSourceElection(tp, false);
        long destinationEnd = endOffsets(destinationAdmin).get(tp);
        assertTrue(destinationEnd > divergence, "the destination did not copy the records lost by the source");
        produce(source, 0, "after", 10);
        Thread.sleep(5000);
        assertEquals(destinationEnd, endOffsets(destinationAdmin).get(tp), "the mirror went on after the source diverged");
    }

    @Test
    public void testSourceUncleanLeaderElectionTruncatesMirrorWhenEnabled() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        uncleanSourceElection(tp, true);
        produce(source, 0, "after", 10);
        waitForMirror();
        for (BrokerServer broker : destination.brokers().values()) {
            TestUtils.waitForCondition(() -> batches(source, tp).equals(batches(broker, tp)),
                () -> "replica " + broker.config().brokerId() + " of " + tp + " did not converge");
        }
    }

    /**
     * Makes the source partition diverge from the destination: the source leader gets records which its only other
     * replica does not have, the destination copies them, and then the stale replica becomes the leader through an
     * unclean leader election and gets other records at the same offsets.
     *
     * @return the offset at which the source partition diverged
     */
    private void enableMirrorUncleanLeaderElection() throws Exception {
        destinationAdmin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, TOPIC), List.of(
            new AlterConfigOp(new ConfigEntry(TopicConfig.MIRROR_UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, "true"), AlterConfigOp.OpType.SET)
        ))).all().get();
    }

    private long uncleanSourceElection(TopicPartition tp, boolean enableMirrorUncleanLeaderElection) throws Exception {
        // The source partition has two replicas, so the stale one is the only candidate.
        int leader = leader(sourceAdmin, tp);
        int stale = source.brokers().keySet().stream().filter(id -> id != leader).findFirst().get();
        sourceAdmin.alterPartitionReassignments(Map.of(tp, java.util.Optional.of(new NewPartitionReassignment(List.of(leader, stale)))))
            .all().get();
        TestUtils.waitForCondition(() -> sourceAdmin.describeTopics(Set.of(TOPIC)).allTopicNames().get().get(TOPIC)
            .partitions().get(tp.partition()).replicas().size() == 2, "the reassignment did not complete");
        sourceAdmin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, TOPIC), List.of(
            new AlterConfigOp(new ConfigEntry(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1"), AlterConfigOp.OpType.SET)
        ))).all().get();
        produce(source, 0, "shared", 10);
        startMirroring();
        waitForMirror();
        if (enableMirrorUncleanLeaderElection) enableMirrorUncleanLeaderElection();

        source.brokers().get(stale).shutdown();
        TestUtils.waitForCondition(() -> sourceAdmin.describeTopics(Set.of(TOPIC)).allTopicNames().get().get(TOPIC)
            .partitions().get(tp.partition()).isr().size() == 1, "the stale replica did not leave the ISR");
        long divergence = endOffsets(sourceAdmin).get(tp);
        produce(source, 0, "lost", 10);
        waitForMirror();

        source.brokers().get(leader).shutdown();
        source.brokers().get(stale).startup();
        TestUtils.waitForCondition(() -> {
            try {
                sourceAdmin.electLeaders(ElectionType.UNCLEAN, Set.of(tp)).all().get();
            } catch (ExecutionException e) {
                // Retried until the stale replica is registered again.
            }
            return leader(sourceAdmin, tp) == stale;
        }, "the stale replica was not elected");
        source.brokers().get(leader).startup();
        produce(source, 0, "new", 5);
        return divergence;
    }

    @Test
    public void testStopStoresLastMirrorEpoch() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        produce(source, 0, "r", 30);
        startMirroring();
        waitForMirror();
        TestUtils.waitForCondition(() -> mirrorState(tp).map(s -> s.state() == MirrorPartitionState.MIRRORING.value()).orElse(false),
            () -> "the partition did not reach MIRRORING: " + mirrorState(tp));

        setMirrorState(LogConfig.MIRROR_STATE_STOPPED);
        TestUtils.waitForCondition(() -> mirrorState(tp).map(s -> s.state() == MirrorPartitionState.STOPPED.value()).orElse(false),
            () -> "the partition did not reach STOPPED: " + mirrorState(tp));
        ReadMirrorStatesResponseData.PartitionResult state = mirrorState(tp).orElseThrow();
        assertEquals(leaderLog(source, tp).latestEpoch().orElseThrow().intValue(), state.lastMirrorEpoch());
        assertEquals(endOffsets(sourceAdmin).get(tp).longValue(), state.lastMirrorOffset());
    }

    @Test
    public void testRestartAfterStopTruncatesLocalWrites() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        produce(source, 0, "first", 20);
        startMirroring();
        waitForMirror();

        setMirrorState(LogConfig.MIRROR_STATE_STOPPED);
        TestUtils.waitForCondition(() -> {
            try {
                produce(destination, 0, "local", 5);
                return true;
            } catch (ExecutionException e) {
                return false;
            }
        }, "the destination topic did not become writable");
        produce(source, 0, "second", 10);

        // Mirroring again truncates the destination to the last mirror offset, which drops the local records.
        setMirrorState(LogConfig.MIRROR_STATE_MIRRORING);
        waitForMirror();
        for (BrokerServer broker : destination.brokers().values()) {
            TestUtils.waitForCondition(() -> batches(source, tp).equals(batches(broker, tp)),
                () -> "replica " + broker.config().brokerId() + " of " + tp + " did not converge");
        }
        assertEquals(0, consume(destination, "read_committed").stream().filter(v -> v.contains(":local-")).count());
    }

    @Test
    public void testStartCreatesTopicWithSourceTopicId() throws Exception {
        startMirroring();
        TestUtils.waitForCondition(() -> destinationAdmin.listTopics().names().get().contains(TOPIC), "no destination topic");
        assertEquals(sourceAdmin.describeTopics(Set.of(TOPIC)).allTopicNames().get().get(TOPIC).topicId(),
            destinationAdmin.describeTopics(Set.of(TOPIC)).allTopicNames().get().get(TOPIC).topicId());
    }

    @Test
    public void testPauseAndResume() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        produce(source, 0, "first", 10);
        startMirroring();
        waitForMirror();

        retry(() -> destinationAdmin.pauseMirrorTopics(MIRROR, List.of(TOPIC)).all().get(), "pause");
        TestUtils.waitForCondition(() -> mirrorState(tp).map(s -> s.state() == MirrorPartitionState.PAUSED.value()).orElse(false),
            () -> "the partition did not reach PAUSED: " + mirrorState(tp));
        long pausedEnd = endOffsets(destinationAdmin).get(tp);
        produce(source, 0, "paused", 10);
        Thread.sleep(3000);
        assertEquals(pausedEnd, endOffsets(destinationAdmin).get(tp), "a paused partition was mirrored");
        // A paused partition is read-only.
        ExecutionException e = assertThrows(ExecutionException.class, () -> produce(destination, 0, "local", 1));
        assertInstanceOf(ReadOnlyTopicException.class, e.getCause());

        retry(() -> destinationAdmin.resumeMirrorTopics(MIRROR, List.of(TOPIC)).all().get(), "resume");
        waitForMirror();
        assertEquals(batches(source, tp), batches(destination, tp));
    }

    @Test
    public void testRecoverAfterEnablingUncleanLeaderElection() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        uncleanSourceElection(tp, false);
        TestUtils.waitForCondition(() -> mirrorState(tp).map(s -> s.state() == MirrorPartitionState.FAILED.value()).orElse(false),
            () -> "the partition did not reach FAILED: " + mirrorState(tp));
        assertEquals(-1, mirrorState(tp).orElseThrow().retryAttempt(), "the failure is not retryable");

        enableMirrorUncleanLeaderElection();
        destinationAdmin.recoverMirrorTopics(MIRROR, List.of(TOPIC)).all().get();
        produce(source, 0, "after", 10);
        waitForMirror();
        for (BrokerServer broker : destination.brokers().values()) {
            TestUtils.waitForCondition(() -> batches(source, tp).equals(batches(broker, tp)),
                () -> "replica " + broker.config().brokerId() + " of " + tp + " did not converge");
        }
    }

    @Test
    public void testListDescribeAndDelete() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        produce(source, 0, "r", 10);
        startMirroring();
        waitForMirror();

        var listings = destinationAdmin.listClusterMirrors().all().get();
        assertEquals(1, listings.size());
        var listing = listings.iterator().next();
        assertEquals(MIRROR, listing.mirrorName());
        assertEquals(List.of(TOPIC), listing.topicNames().orElseThrow());
        assertEquals(sourceAdmin.describeCluster().clusterId().get(), listing.sourceClusterId());

        TestUtils.waitForCondition(() -> {
            var description = destinationAdmin.describeClusterMirrors(List.of(MIRROR), Map.of(),
                new org.apache.kafka.clients.admin.DescribeClusterMirrorsOptions().includeMirrorState(true).includeMirrorOffset(true))
                .allDescriptions().get().get(MIRROR);
            return description.leaderStates().get(TOPIC).stream().anyMatch(state -> state.topicPartition().equals(tp) &&
                state.state().equals("MIRRORING") && state.lag() == 0 && state.destinationOffset() == 10);
        }, "the description does not show the mirrored partition");

        // A cluster mirror whose topics are not all stopped cannot be deleted.
        assertThrows(ExecutionException.class, () -> destinationAdmin.deleteClusterMirror(MIRROR).all().get());
        setMirrorState(LogConfig.MIRROR_STATE_STOPPED);
        retry(() -> destinationAdmin.deleteClusterMirror(MIRROR).all().get(), "delete");
        assertEquals(0, destinationAdmin.listClusterMirrors().all().get().size());
        TestUtils.waitForCondition(() -> {
            try {
                produce(destination, 0, "local", 1);
                return true;
            } catch (ExecutionException e) {
                return false;
            }
        }, "the destination topic did not become writable after the mirror was deleted");
    }

    @Test
    public void testTopicConfigsAndPartitionsAreSynchronized() throws Exception {
        produce(source, 0, "r", 10);
        startMirroring();
        waitForMirror();

        sourceAdmin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, TOPIC), List.of(
            new AlterConfigOp(new ConfigEntry(TopicConfig.RETENTION_MS_CONFIG, "123456789"), AlterConfigOp.OpType.SET)
        ))).all().get();
        sourceAdmin.createPartitions(Map.of(TOPIC, org.apache.kafka.clients.admin.NewPartitions.increaseTo(3))).all().get();
        TestUtils.waitForCondition(() -> "123456789".equals(destinationAdmin.describeConfigs(
                List.of(new ConfigResource(ConfigResource.Type.TOPIC, TOPIC))).all().get()
                .get(new ConfigResource(ConfigResource.Type.TOPIC, TOPIC)).get(TopicConfig.RETENTION_MS_CONFIG).value()),
            "the topic config was not synchronized");
        TestUtils.waitForCondition(() -> destinationAdmin.describeTopics(Set.of(TOPIC)).allTopicNames().get()
            .get(TOPIC).partitions().size() == 3, "the partitions were not synchronized");

        produce(source, 2, "new-partition", 5);
        TopicPartition added = new TopicPartition(TOPIC, 2);
        TestUtils.waitForCondition(() -> {
            try {
                return batches(source, added).equals(batches(destination, added));
            } catch (Exception e) {
                return false;
            }
        }, "the added partition was not mirrored");
    }

    @Test
    public void testNewSourceTopicIsDiscovered() throws Exception {
        startMirroring();
        TestUtils.waitForCondition(() -> destinationAdmin.listTopics().names().get().contains(TOPIC), "no destination topic");

        String discovered = "discovered";
        sourceAdmin.createTopics(List.of(new NewTopic(discovered, 1, (short) 3))).all().get();
        try (KafkaProducer<byte[], byte[]> producer = producer(source, Map.of())) {
            for (int i = 0; i < 5; i++) producer.send(new ProducerRecord<>(discovered, 0, null, ("d-" + i).getBytes())).get();
        }
        TestUtils.waitForCondition(() -> destinationAdmin.listTopics().names().get().contains(discovered), 60_000,
            "the new source topic was not discovered");
        assertEquals(sourceAdmin.describeTopics(Set.of(discovered)).allTopicNames().get().get(discovered).topicId(),
            destinationAdmin.describeTopics(Set.of(discovered)).allTopicNames().get().get(discovered).topicId());
        TopicPartition tp = new TopicPartition(discovered, 0);
        TestUtils.waitForCondition(() -> destinationAdmin.listOffsets(Map.of(tp, OffsetSpec.latest())).all().get().get(tp).offset() == 5,
            60_000, "the discovered topic was not mirrored");
    }

    @Test
    public void testConsumerGroupOffsetsAreSynchronized() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        produce(source, 0, "r", 30);
        startMirroring();
        waitForMirror();

        sourceAdmin.alterConsumerGroupOffsets("group", Map.of(tp, new org.apache.kafka.clients.consumer.OffsetAndMetadata(20))).all().get();
        TestUtils.waitForCondition(() -> {
            var offsets = destinationAdmin.listConsumerGroupOffsets("group").partitionsToOffsetAndMetadata().get();
            return offsets.get(tp) != null && offsets.get(tp).offset() == 20;
        }, 60_000, "the consumer group offsets were not synchronized");

        // An offset beyond the mirrored records is clamped to the log end offset of the destination.
        sourceAdmin.alterConsumerGroupOffsets("group", Map.of(tp, new org.apache.kafka.clients.consumer.OffsetAndMetadata(30))).all().get();
        TestUtils.waitForCondition(() -> destinationAdmin.listConsumerGroupOffsets("group").partitionsToOffsetAndMetadata().get()
            .get(tp).offset() == 30, 60_000, "the consumer group offsets were not synchronized again");
    }

    @Test
    public void testAclsAreSynchronized() throws Exception {
        startMirroring();
        var binding = new org.apache.kafka.common.acl.AclBinding(
            new org.apache.kafka.common.resource.ResourcePattern(org.apache.kafka.common.resource.ResourceType.TOPIC, TOPIC,
                org.apache.kafka.common.resource.PatternType.LITERAL),
            new org.apache.kafka.common.acl.AccessControlEntry("User:alice", "*", org.apache.kafka.common.acl.AclOperation.READ,
                org.apache.kafka.common.acl.AclPermissionType.ALLOW));
        sourceAdmin.createAcls(List.of(binding)).all().get();
        TestUtils.waitForCondition(() -> destinationAdmin.describeAcls(org.apache.kafka.common.acl.AclBindingFilter.ANY)
            .values().get().contains(binding), 60_000, "the ACL was not synchronized");
    }

    /**
     * @return the state of a mirror partition, read from the coordinator of the destination cluster which keeps it
     */
    private java.util.Optional<ReadMirrorStatesResponseData.PartitionResult> mirrorState(TopicPartition tp) {
        ReadMirrorStatesRequestData request = new ReadMirrorStatesRequestData().setMirrorName(MIRROR);
        ReadMirrorStatesRequestData.TopicMetadata topic = new ReadMirrorStatesRequestData.TopicMetadata().setTopicName(tp.topic());
        topic.partitions().add(new ReadMirrorStatesRequestData.PartitionData().setPartitionIndex(tp.partition()));
        request.topics().add(topic);
        for (BrokerServer broker : destination.brokers().values()) {
            try {
                ReadMirrorStatesResponseData.PartitionResult result = broker.mirrorCoordinator().readMirrorStates(request).get()
                    .topics().iterator().next().partitions().get(0);
                if (result.errorCode() == 0) return java.util.Optional.of(result);
            } catch (Exception e) {
                // Not the coordinator of the partition.
            }
        }
        return java.util.Optional.empty();
    }

    private static int leaderEpoch(KafkaClusterTestKit cluster, TopicPartition tp) {
        for (BrokerServer broker : cluster.brokers().values()) {
            var partition = broker.replicaManager().onlinePartition(tp);
            if (partition.isDefined() && partition.get().isLeader()) return partition.get().getLeaderEpoch();
        }
        return -1;
    }

    private void produceMixedBatches() throws Exception {
        for (String compression : List.of("none", "gzip", "lz4", "zstd")) {
            try (KafkaProducer<byte[], byte[]> producer = producer(source, Map.of(ProducerConfig.COMPRESSION_TYPE_CONFIG, compression,
                    ProducerConfig.LINGER_MS_CONFIG, "50"))) {
                for (int i = 0; i < 200; i++) {
                    producer.send(new ProducerRecord<>(TOPIC, i % PARTITIONS, null, (compression + "-" + i).getBytes()));
                }
            }
        }
        try (KafkaProducer<byte[], byte[]> producer = producer(source, Map.of(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "txn"))) {
            producer.initTransactions();
            producer.beginTransaction();
            for (int i = 0; i < 20; i++) producer.send(new ProducerRecord<>(TOPIC, i % PARTITIONS, null, ("committed-" + i).getBytes()));
            producer.commitTransaction();
            producer.beginTransaction();
            for (int i = 0; i < 20; i++) producer.send(new ProducerRecord<>(TOPIC, i % PARTITIONS, null, ("aborted-" + i).getBytes()));
            producer.flush();
            producer.abortTransaction();
        }
    }

    private void startMirroring() throws Exception {
        retry(() -> destinationAdmin.startMirrorTopics(MIRROR, List.of(TOPIC)).all().get(), "start");
    }

    private void setMirrorState(String state) throws Exception {
        if (LogConfig.MIRROR_STATE_STOPPED.equals(state)) {
            retry(() -> destinationAdmin.stopMirrorTopics(MIRROR, List.of(TOPIC)).all().get(), "stop");
        } else {
            startMirroring();
        }
    }

    /**
     * Retries a lifecycle operation, which is rejected while the partitions are still in transition.
     */
    private static void retry(org.junit.jupiter.api.function.Executable operation, String name) throws InterruptedException {
        TestUtils.waitForCondition(() -> {
            try {
                operation.execute();
                return true;
            } catch (Throwable e) {
                return false;
            }
        }, 60_000, "the " + name + " operation did not succeed");
    }

    private void waitForMirror() throws InterruptedException {
        TestUtils.waitForCondition(() -> endOffsets(sourceAdmin).equals(endOffsets(destinationAdmin)), 60_000,
            () -> "source " + endOffsets(sourceAdmin) + ", destination " + endOffsets(destinationAdmin));
    }

    private static Map<TopicPartition, Long> endOffsets(Admin admin) {
        try {
            Map<TopicPartition, OffsetSpec> specs = new java.util.HashMap<>();
            for (int partition = 0; partition < PARTITIONS; partition++) specs.put(new TopicPartition(TOPIC, partition), OffsetSpec.latest());
            return admin.listOffsets(specs).all().get().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static int leader(Admin admin, TopicPartition tp) {
        try {
            var leader = admin.describeTopics(Set.of(tp.topic())).allTopicNames().get().get(tp.topic())
                .partitions().get(tp.partition()).leader();
            return leader == null ? -1 : leader.id();
        } catch (Exception e) {
            return -1;
        }
    }

    private UnifiedLog leaderLog(KafkaClusterTestKit cluster, TopicPartition tp) {
        Admin admin = cluster == source ? sourceAdmin : destinationAdmin;
        return cluster.brokers().get(leader(admin, tp)).replicaManager().localLogOrException(tp);
    }

    private List<String> batches(KafkaClusterTestKit cluster, TopicPartition tp) {
        Admin admin = cluster == source ? sourceAdmin : destinationAdmin;
        return batches(cluster.brokers().get(leader(admin, tp)), tp);
    }

    /**
     * Describes every batch of the local log of a replica from its log start offset: its offsets, partition leader
     * epoch, size and checksum, which covers its attributes, producer, timestamps and records. The batches below the
     * log start offset may still be in the first segment until it is deleted, so they are skipped.
     */
    private static List<String> batches(BrokerServer broker, TopicPartition tp) {
        List<String> batches = new ArrayList<>();
        UnifiedLog log = broker.replicaManager().localLogOrException(tp);
        for (LogSegment segment : log.logSegments()) {
            segment.log().batches().forEach(batch -> {
                if (batch.lastOffset() >= log.logStartOffset()) {
                    batches.add(batch.baseOffset() + "-" + batch.lastOffset() + " epoch=" + batch.partitionLeaderEpoch() +
                        " size=" + batch.sizeInBytes() + " crc=" + batch.checksum() + " control=" + batch.isControlBatch() +
                        " txn=" + batch.isTransactional());
                }
            });
        }
        return batches;
    }

    private static KafkaProducer<byte[], byte[]> producer(KafkaClusterTestKit cluster, Map<String, String> overrides) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.putAll(overrides);
        return new KafkaProducer<>(props, new ByteArraySerializer(), new ByteArraySerializer());
    }

    private static void produce(KafkaClusterTestKit cluster, int partition, String prefix, int count) throws ExecutionException, InterruptedException {
        try (KafkaProducer<byte[], byte[]> producer = producer(cluster, Map.of(ProducerConfig.RETRIES_CONFIG, "0"))) {
            for (int i = 0; i < count; i++) {
                producer.send(new ProducerRecord<>(TOPIC, partition, null, (prefix + "-" + i).getBytes())).get();
            }
        }
    }

    private static List<String> consume(KafkaClusterTestKit cluster, String isolation) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers());
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolation);
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> tps = new ArrayList<>();
            for (int partition = 0; partition < PARTITIONS; partition++) tps.add(new TopicPartition(TOPIC, partition));
            consumer.assign(tps);
            consumer.seekToBeginning(tps);
            Map<TopicPartition, Long> ends = consumer.endOffsets(tps);
            long deadline = System.currentTimeMillis() + 30_000;
            while (tps.stream().anyMatch(tp -> consumer.position(tp) < ends.get(tp)) && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    values.add(record.partition() + ":" + record.offset() + ":" + new String(record.value()));
                }
            }
        }
        // The order of the records of different partitions depends on the polls, so they are sorted by partition and offset.
        values.sort(java.util.Comparator.comparing((String value) -> Integer.parseInt(value.split(":")[0]))
            .thenComparing(value -> Long.parseLong(value.split(":")[1])));
        return values;
    }
}
