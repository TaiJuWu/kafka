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
package org.apache.kafka.clients.consumer;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.internals.Topic;
import org.apache.kafka.common.test.ClusterInstance;
import org.apache.kafka.common.test.api.ClusterConfigProperty;
import org.apache.kafka.common.test.api.ClusterTest;
import org.apache.kafka.common.test.api.ClusterTestDefaults;
import org.apache.kafka.common.test.api.Type;

import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.kafka.test.TestUtils.waitForCondition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Partition expansion of a topic that a share group is already consuming, combined with a change of
 * the share coordinator, of the leader of the new partition, or of the share coordinator while a new
 * topic is being initialized.
 */
@ClusterTestDefaults(
    types = {Type.KRAFT},
    brokers = 3,
    serverProperties = {
        @ClusterConfigProperty(key = "auto.create.topics.enable", value = "false"),
        @ClusterConfigProperty(key = "offsets.topic.num.partitions", value = "1"),
        @ClusterConfigProperty(key = "offsets.topic.replication.factor", value = "3"),
        @ClusterConfigProperty(key = "share.coordinator.state.topic.min.isr", value = "1"),
        @ClusterConfigProperty(key = "share.coordinator.state.topic.num.partitions", value = "1"),
        @ClusterConfigProperty(key = "share.coordinator.state.topic.replication.factor", value = "3"),
        @ClusterConfigProperty(key = "transaction.state.log.min.isr", value = "1"),
        @ClusterConfigProperty(key = "transaction.state.log.replication.factor", value = "1")
    }
)
public class ShareConsumerPartitionExpansionTest extends ShareConsumerTestBase {

    private static final String GROUP = "expand-group";
    private static final String TOPIC = "expand";

    public ShareConsumerPartitionExpansionTest(ClusterInstance cluster) {
        super(cluster);
    }

    /**
     * The topic gains a partition and the broker hosting the share coordinator goes away right after.
     * Every record written to the new partition must be delivered.
     */
    @ClusterTest
    @Timeout(240)
    public void testExpansionWithShareCoordinatorChange() throws Exception {
        runExpansion((admin, newPartitionLeader) -> leader(admin, Topic.SHARE_GROUP_STATE_TOPIC_NAME, 0));
    }

    /**
     * The topic gains a partition and the leader of the new partition goes away right after.
     * Every record written to the new partition must be delivered.
     */
    @ClusterTest
    @Timeout(240)
    public void testExpansionWithNewPartitionLeaderChange() throws Exception {
        runExpansion((admin, newPartitionLeader) -> newPartitionLeader);
    }

    /**
     * The topic gains a partition and the broker hosting the group coordinator goes away right after.
     * Every record written to the new partition must be delivered.
     */
    @ClusterTest
    @Timeout(240)
    public void testExpansionWithGroupCoordinatorChange() throws Exception {
        runExpansion((admin, newPartitionLeader) -> leader(admin, Topic.GROUP_METADATA_TOPIC_NAME, 0));
    }

    /**
     * A brand-new topic is added to the subscription while the share coordinator cannot be reached for
     * longer than the initialize retry interval. The topic must still start where
     * {@code share.auto.offset.reset=latest} says, so the records written before the subscription
     * are not delivered.
     */
    @ClusterTest(
        serverProperties = {
            @ClusterConfigProperty(key = "share.coordinator.state.topic.replication.factor", value = "1"),
            @ClusterConfigProperty(key = "offsets.commit.timeout.ms", value = "2000"),
            @ClusterConfigProperty(key = "group.share.initialize.retry.interval.ms", value = "2000")
        }
    )
    @Timeout(240)
    public void testNewTopicKeepsLatestWhenInitializeIsRetried() throws Exception {
        String freshTopic = "fresh";
        alterShareAutoOffsetReset(GROUP, "latest");
        try (Admin admin = createAdminClient();
             Producer<byte[], byte[]> producer = createProducer();
             ShareConsumer<byte[], byte[]> consumer1 = createShareConsumer(GROUP);
             ShareConsumer<byte[], byte[]> consumer2 = createShareConsumer(GROUP)) {
            // The group starts on another topic, which creates the share state topic on one broker.
            createTopic(TOPIC, 1, 3);
            consumer1.subscribe(Set.of(TOPIC));
            consumer2.subscribe(Set.of(TOPIC));
            pollUntil(List.of(consumer1, consumer2), () -> assignedPartitions(GROUP).contains(new TopicPartition(TOPIC, 0)));
            int coordinator = leader(admin, Topic.SHARE_GROUP_STATE_TOPIC_NAME, 0);

            // The new topic is not on the share coordinator's broker, and it already has 100 records.
            List<Integer> replicas = IntStream.range(0, 3).filter(b -> b != coordinator).boxed().toList();
            admin.createTopics(List.of(new NewTopic(freshTopic, Map.of(0, replicas)))).all().get();
            cluster.waitTopicCreation(freshTopic, 1);
            send(producer, freshTopic, 0, "old", 100);

            // The share coordinator is unreachable for longer than the initialize retry interval.
            cluster.shutdownBroker(coordinator);
            consumer1.subscribe(Set.of(TOPIC, freshTopic));
            consumer2.subscribe(Set.of(TOPIC, freshTopic));
            pollFor(List.of(consumer1, consumer2), Duration.ofSeconds(10), new ConcurrentHashMap<>());
            cluster.startBroker(coordinator);
            pollUntil(List.of(consumer1, consumer2), () -> assignedPartitions(GROUP).contains(new TopicPartition(freshTopic, 0)));

            send(producer, freshTopic, 0, "new", 10);
            Map<String, Boolean> received = new ConcurrentHashMap<>();
            pollFor(List.of(consumer1, consumer2), Duration.ofSeconds(20), received);

            Set<String> fresh = received.keySet().stream().filter(v -> v.startsWith(freshTopic + "-")).collect(Collectors.toSet());
            long old = fresh.stream().filter(v -> v.contains("-old")).count();
            long newer = fresh.stream().filter(v -> v.contains("-new")).count();
            System.out.println("XBUG initRetry coordinator=" + coordinator + " old=" + old + " new=" + newer
                + " startOffset=" + offsetInfo(admin, new TopicPartition(freshTopic, 0)));
            assertEquals(0, old, "records written before the subscription were delivered");
            assertEquals(10, newer, "records written after the subscription were not all delivered");
        }
    }

    @FunctionalInterface
    private interface BrokerToStop {
        int pick(Admin admin, int newPartitionLeader) throws Exception;
    }

    private void runExpansion(BrokerToStop brokerToStop) throws Exception {
        alterShareAutoOffsetReset(GROUP, "latest");
        createTopic(TOPIC, 1, 3);
        Map<String, Boolean> received = new ConcurrentHashMap<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Admin admin = createAdminClient();
             Producer<byte[], byte[]> producer = createProducer()) {
            // Two share consumers keep polling for the whole test.
            List<Future<?>> pollers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                pollers.add(executor.submit(() -> {
                    try (ShareConsumer<byte[], byte[]> consumer = createShareConsumer(GROUP)) {
                        consumer.subscribe(Set.of(TOPIC));
                        while (!stop.get()) {
                            consumer.poll(Duration.ofMillis(500)).forEach(r -> received.put(value(r), true));
                        }
                    }
                }));
            }

            // The group knows the topic once partition 0 delivers records.
            waitForAssignment(GROUP, List.of(new TopicPartition(TOPIC, 0)));
            send(producer, TOPIC, 0, "p0-before", 20);
            waitForCondition(() -> count(received, "p0-before") == 20, 30000, "partition 0 did not deliver");

            // Expand, then immediately stop the chosen broker and keep writing to the new partition.
            admin.createPartitions(Map.of(TOPIC, NewPartitions.increaseTo(2))).all().get();
            cluster.waitTopicCreation(TOPIC, 2);
            int newPartitionLeader = leader(admin, TOPIC, 1);
            int stopped = brokerToStop.pick(admin, newPartitionLeader);
            String roles = "shareCoordinator=" + leader(admin, Topic.SHARE_GROUP_STATE_TOPIC_NAME, 0)
                + " groupCoordinator=" + leader(admin, Topic.GROUP_METADATA_TOPIC_NAME, 0)
                + " p1Leader=" + newPartitionLeader;
            send(producer, TOPIC, 1, "p1-before-stop", 25);
            cluster.shutdownBroker(stopped);
            send(producer, TOPIC, 1, "p1-after-stop", 25);
            send(producer, TOPIC, 0, "p0-after-stop", 20);
            Thread.sleep(5000);
            cluster.startBroker(stopped);
            send(producer, TOPIC, 1, "p1-after-start", 25);

            boolean done = waitFor(() -> count(received, "p1-") == 75 && count(received, "p0-after-stop") == 20, 60000);
            System.out.println("XBUG expansion stopped=" + stopped + " " + roles
                + " p1-before-stop=" + count(received, "p1-before-stop")
                + " p1-after-stop=" + count(received, "p1-after-stop")
                + " p1-after-start=" + count(received, "p1-after-start")
                + " p0-after-stop=" + count(received, "p0-after-stop")
                + " p1StartOffset=" + offsetInfo(admin, new TopicPartition(TOPIC, 1)));
            stop.set(true);
            for (Future<?> poller : pollers) {
                poller.get(30, TimeUnit.SECONDS);
            }
            assertEquals(25, count(received, "p1-before-stop"), "records written to the new partition before the broker stopped were lost");
            assertEquals(25, count(received, "p1-after-stop"), "records written to the new partition while the broker was stopped were lost");
            assertEquals(25, count(received, "p1-after-start"), "records written to the new partition after the broker restarted were lost");
            assertEquals(20, count(received, "p0-after-stop"), "records written to partition 0 were lost");
            assertTrue(done, "not all records were delivered in time");
        } finally {
            stop.set(true);
            shutdownExecutorService(executor);
        }
    }

    private String offsetInfo(Admin admin, TopicPartition partition) {
        try {
            return String.valueOf(sharePartitionOffsetInfo(admin, GROUP, partition));
        } catch (Exception e) {
            return "unavailable: " + e;
        }
    }

    private void pollUntil(List<ShareConsumer<byte[], byte[]>> consumers, BooleanSupplier condition) throws InterruptedException {
        waitForCondition(() -> {
            for (ShareConsumer<byte[], byte[]> consumer : consumers) {
                consumer.poll(Duration.ofMillis(200));
            }
            return condition.getAsBoolean();
        }, 60000, "the partitions were not assigned");
    }

    private int leader(Admin admin, String topic, int partition) throws Exception {
        return topicPartitionLeader(admin, topic, partition).get(0);
    }

    private static void send(Producer<byte[], byte[]> producer, String topic, int partition, String prefix, int count) {
        for (int i = 0; i < count; i++) {
            producer.send(new ProducerRecord<>(topic, partition, null, (topic + "-" + prefix + "-" + i).getBytes()));
        }
        producer.flush();
    }

    private static String value(ConsumerRecord<byte[], byte[]> record) {
        return new String(record.value());
    }

    private static long count(Map<String, Boolean> received, String prefix) {
        return received.keySet().stream().filter(v -> v.startsWith(TOPIC + "-" + prefix)).count();
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return condition.getAsBoolean();
    }

    private static void pollFor(List<ShareConsumer<byte[], byte[]>> consumers, Duration duration, Map<String, Boolean> received) {
        long deadline = System.currentTimeMillis() + duration.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ShareConsumer<byte[], byte[]> consumer : consumers) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> received.put(value(r), true));
            }
        }
    }
}
