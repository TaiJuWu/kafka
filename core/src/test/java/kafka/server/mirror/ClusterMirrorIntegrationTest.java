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
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.test.KafkaClusterTestKit;
import org.apache.kafka.common.test.TestKitNodes;
import org.apache.kafka.server.common.MetadataVersion;
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
        destination = startCluster(Map.of(
            MirrorMetadataManager.ClusterMirrorPrefix() + MIRROR + ".bootstrap.servers", source.bootstrapServers(),
            "mirror.metadata.refresh.interval.ms", "1000"));
        sourceAdmin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, source.bootstrapServers()));
        destinationAdmin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, destination.bootstrapServers()));
        for (Admin admin : List.of(sourceAdmin, destinationAdmin)) {
            admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 3))).all().get();
        }
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
        long divergence = uncleanSourceElection(tp);
        long destinationEnd = endOffsets(destinationAdmin).get(tp);
        assertTrue(destinationEnd > divergence, "the destination did not copy the records lost by the source");
        produce(source, 0, "after", 10);
        Thread.sleep(5000);
        assertEquals(destinationEnd, endOffsets(destinationAdmin).get(tp), "the mirror went on after the source diverged");
    }

    @Test
    public void testSourceUncleanLeaderElectionTruncatesMirrorWhenEnabled() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        destinationAdmin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, TOPIC), List.of(
            new AlterConfigOp(new ConfigEntry(TopicConfig.MIRROR_UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, "true"), AlterConfigOp.OpType.SET)
        ))).all().get();
        uncleanSourceElection(tp);
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
    private long uncleanSourceElection(TopicPartition tp) throws Exception {
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
        destinationAdmin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, TOPIC), List.of(
            new AlterConfigOp(new ConfigEntry(LogConfig.MIRROR_NAME_CONFIG, MIRROR), AlterConfigOp.OpType.SET),
            new AlterConfigOp(new ConfigEntry(LogConfig.MIRROR_STATE_CONFIG, LogConfig.MIRROR_STATE_MIRRORING), AlterConfigOp.OpType.SET)
        ))).all().get();
    }

    private void setMirrorState(String state) throws Exception {
        destinationAdmin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, TOPIC), List.of(
            new AlterConfigOp(new ConfigEntry(LogConfig.MIRROR_STATE_CONFIG, state), AlterConfigOp.OpType.SET)
        ))).all().get();
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
