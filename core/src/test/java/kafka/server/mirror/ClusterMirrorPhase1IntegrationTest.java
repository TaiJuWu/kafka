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
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.ReadOnlyTopicException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.test.KafkaClusterTestKit;
import org.apache.kafka.common.test.TestKitNodes;
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
 * Phase 1 of our KIP-1279 implementation: the leader of a destination partition copies the record batches of the
 * source partition as they are, the destination topic is read-only while it is mirrored, and the mirroring is
 * controlled by the topic configurations mirror.name and mirror.state.
 */
@Timeout(300)
public class ClusterMirrorPhase1IntegrationTest {
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
            .build());
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
     * In this phase, the leader epoch of a destination partition is not bumped to the leader epoch of the source
     * partition, so the mirroring of a partition stops on a batch with a higher epoch, and resumes when the
     * destination partition gets a high enough leader epoch.
     */
    @Test
    public void testHigherSourceEpochStopsMirroringUntilDestinationEpochCatchesUp() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        produce(source, 0, "first", 20);
        startMirroring();
        waitForMirror();

        // A new source leader bumps the source epoch above the destination epoch.
        int sourceLeader = leader(sourceAdmin, tp);
        source.brokers().get(sourceLeader).shutdown();
        TestUtils.waitForCondition(() -> leader(sourceAdmin, tp) != sourceLeader, "no new source leader");
        source.brokers().get(sourceLeader).startup();
        produce(source, 0, "second", 20);
        long destinationEnd = endOffsets(destinationAdmin).get(tp);
        Thread.sleep(5000);
        assertEquals(destinationEnd, endOffsets(destinationAdmin).get(tp), "the mirror copied a batch of a higher epoch");

        // A new destination leader has a higher epoch, so the mirroring resumes.
        int destinationLeader = leader(destinationAdmin, tp);
        destination.brokers().get(destinationLeader).shutdown();
        TestUtils.waitForCondition(() -> leader(destinationAdmin, tp) != destinationLeader, "no new destination leader");
        destination.brokers().get(destinationLeader).startup();
        waitForMirror();
        assertEquals(batches(source, tp), batches(destination, tp));
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
