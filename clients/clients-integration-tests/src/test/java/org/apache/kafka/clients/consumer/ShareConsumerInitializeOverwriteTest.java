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
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicIdPartition;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * While the share coordinator is unavailable, the group coordinator keeps retrying the initialization of a newly
 * subscribed topic. When the share coordinator is back, the pending initialize requests arrive spread over the
 * persister backoff. A request which arrives after the share partition is in use must not reset its state.
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
        @ClusterConfigProperty(key = "share.coordinator.state.topic.replication.factor", value = "1"),
        @ClusterConfigProperty(key = "offsets.commit.timeout.ms", value = "2000"),
        @ClusterConfigProperty(key = "group.share.initialize.retry.interval.ms", value = "2000"),
        @ClusterConfigProperty(key = "transaction.state.log.min.isr", value = "1"),
        @ClusterConfigProperty(key = "transaction.state.log.replication.factor", value = "1")
    }
)
public class ShareConsumerInitializeOverwriteTest extends ShareConsumerTestBase {

    private static final String GROUP = "overwrite-group";
    private static final String TOPIC = "overwrite-topic";

    public ShareConsumerInitializeOverwriteTest(ClusterInstance cluster) {
        super(cluster);
    }

    @ClusterTest
    @Timeout(400)
    public void testLateInitializeRequestDoesNotResetShareState() throws Exception {
        alterShareAutoOffsetReset(GROUP, "latest");
        Map<Integer, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        Set<Integer> acknowledged = ConcurrentHashMap.newKeySet();
        Set<Integer> redeliveredAfterAck = ConcurrentHashMap.newKeySet();
        AtomicInteger produced = new AtomicInteger();
        AtomicBoolean stopProducing = new AtomicBoolean(false);
        AtomicBoolean stopConsuming = new AtomicBoolean(false);
        ExecutorService executor = Executors.newFixedThreadPool(3);

        try (Admin admin = createAdminClient()) {
            // The warmup has created the share state topic, which has a single partition on one broker.
            int shareCoordinator = topicPartitionLeader(admin, Topic.SHARE_GROUP_STATE_TOPIC_NAME, 0).get(0);
            List<Integer> replicas = new ArrayList<>(List.of(0, 1, 2));
            replicas.remove(Integer.valueOf(shareCoordinator));
            admin.createTopics(List.of(new NewTopic(TOPIC, Map.of(0, replicas)))).all().get();
            cluster.waitTopicCreation(TOPIC, 1);

            // The share coordinator is unavailable for longer than the persister backoff, so the group
            // coordinator retries the initialization many times and the requests pile up.
            cluster.shutdownBroker(shareCoordinator);
            List<Future<?>> consumers = new ArrayList<>();
            for (int c = 0; c < 2; c++) {
                consumers.add(executor.submit(() -> {
                    try (ShareConsumer<byte[], byte[]> consumer = createShareConsumer(GROUP)) {
                        consumer.subscribe(Set.of(TOPIC));
                        while (!stopConsuming.get()) {
                            List<Integer> polled = new ArrayList<>();
                            consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                                int id = Integer.parseInt(new String(r.value()));
                                deliveries.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                                if (acknowledged.contains(id)) {
                                    redeliveredAfterAck.add(id);
                                }
                                polled.add(id);
                            });
                            if (!polled.isEmpty()) {
                                Map<TopicIdPartition, Optional<KafkaException>> result = consumer.commitSync(Duration.ofSeconds(10));
                                if (result.values().stream().allMatch(Optional::isEmpty)) {
                                    acknowledged.addAll(polled);
                                }
                            }
                        }
                    }
                }));
            }
            Thread.sleep(60000);
            cluster.startBroker(shareCoordinator);

            // A steady stream of numbered records while the pending initialize requests arrive.
            Future<?> producer = executor.submit(() -> {
                try (Producer<byte[], byte[]> p = createProducer()) {
                    while (!stopProducing.get()) {
                        int id = produced.getAndIncrement();
                        p.send(new ProducerRecord<>(TOPIC, 0, null, String.valueOf(id).getBytes()));
                        p.flush();
                        Thread.sleep(50);
                    }
                }
                return null;
            });
            Thread.sleep(70000);
            stopProducing.set(true);
            producer.get(30, TimeUnit.SECONDS);
            int total = produced.get();

            // Let the consumers drain what is left.
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline && !deliveries.containsKey(total - 1)) {
                Thread.sleep(500);
            }
            Thread.sleep(5000);
            stopConsuming.set(true);
            for (Future<?> consumer : consumers) {
                consumer.get(60, TimeUnit.SECONDS);
            }

            int firstDelivered = deliveries.keySet().stream().min(Integer::compare).orElse(-1);
            Set<Integer> missing = new TreeSet<>();
            if (firstDelivered >= 0) {
                for (int id = firstDelivered; id < total; id++) {
                    if (!deliveries.containsKey(id)) {
                        missing.add(id);
                    }
                }
            }
            System.out.println("XBUG overwrite coordinator=" + shareCoordinator + " produced=" + total
                + " firstDelivered=" + firstDelivered + " delivered=" + deliveries.size()
                + " acked=" + acknowledged.size() + " redeliveredAfterAck=" + redeliveredAfterAck.size()
                + " missing=" + missing.size() + " missingSample=" + missing.stream().limit(10).toList()
                + " offsets=" + sharePartitionOffsetInfo(admin, GROUP, new TopicPartition(TOPIC, 0)));

            assertTrue(firstDelivered >= 0, "no record was delivered");
            assertEquals(Set.of(), redeliveredAfterAck, "acknowledged records were delivered again");
            assertEquals(Set.of(), missing, "records produced after the start offset were not delivered");
        } finally {
            stopProducing.set(true);
            stopConsuming.set(true);
            shutdownExecutorService(executor);
        }
    }
}
