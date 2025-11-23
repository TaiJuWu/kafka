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
import org.apache.kafka.clients.admin.NewPartitionReassignment;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.internals.AbstractHeartbeatRequestManager;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.test.ClusterInstance;
import org.apache.kafka.common.test.api.ClusterConfigProperty;
import org.apache.kafka.common.test.api.ClusterTest;
import org.apache.kafka.common.test.api.ClusterTests;
import org.apache.kafka.common.test.api.Type;
import org.apache.kafka.coordinator.group.GroupCoordinatorConfig;
import org.apache.kafka.test.TestUtils;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConsumerIntegrationTest {

    @ClusterTests({
        @ClusterTest(serverProperties = {
            @ClusterConfigProperty(key = "offsets.topic.num.partitions", value = "1"),
            @ClusterConfigProperty(key = "offsets.topic.replication.factor", value = "1"),
            @ClusterConfigProperty(key = "group.coordinator.rebalance.protocols", value = "classic")
        })
    })
    public void testAsyncConsumerWithConsumerProtocolDisabled(ClusterInstance clusterInstance) throws Exception {
        String topic = "test-topic";
        clusterInstance.createTopic(topic, 1, (short) 1);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, clusterInstance.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "test-group",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
            ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()))) {
            consumer.subscribe(Collections.singletonList(topic));
            TestUtils.waitForCondition(() -> {
                try {
                    consumer.poll(Duration.ofMillis(1000));
                    return false;
                } catch (UnsupportedVersionException e) {
                    return e.getMessage().equals(AbstractHeartbeatRequestManager.CONSUMER_PROTOCOL_NOT_SUPPORTED_MSG);
                }
            }, "Should get UnsupportedVersionException and how to revert to classic protocol");
        }
    }

    @ClusterTest(serverProperties = {
        @ClusterConfigProperty(key = "offsets.topic.num.partitions", value = "1"),
        @ClusterConfigProperty(key = "offsets.topic.replication.factor", value = "1"),
    })
    public void testFetchPartitionsAfterFailedListenerWithGroupProtocolClassic(ClusterInstance clusterInstance)
            throws InterruptedException {
        testFetchPartitionsAfterFailedListener(clusterInstance, GroupProtocol.CLASSIC);
    }

    @ClusterTest(serverProperties = {
        @ClusterConfigProperty(key = "offsets.topic.num.partitions", value = "1"),
        @ClusterConfigProperty(key = "offsets.topic.replication.factor", value = "1"),
    })
    public void testFetchPartitionsAfterFailedListenerWithGroupProtocolConsumer(ClusterInstance clusterInstance)
            throws InterruptedException {
        testFetchPartitionsAfterFailedListener(clusterInstance, GroupProtocol.CONSUMER);
    }

    private static void testFetchPartitionsAfterFailedListener(ClusterInstance clusterInstance, GroupProtocol groupProtocol)
            throws InterruptedException {
        var topic = "topic";
        try (var producer = clusterInstance.producer(Map.of(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>(topic, "key".getBytes(), "value".getBytes()));
        }

        try (var consumer = clusterInstance.consumer(Map.of(
                ConsumerConfig.GROUP_PROTOCOL_CONFIG, groupProtocol.name()))) {
            consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                private int count = 0;
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    count++;
                    if (count == 1) throw new IllegalArgumentException("temporary error");
                }
            });

            TestUtils.waitForCondition(() -> consumer.poll(Duration.ofSeconds(1)).count() == 1,
                    5000,
                    "failed to poll data");
        }
    }

    @ClusterTest(serverProperties = {
        @ClusterConfigProperty(key = "offsets.topic.num.partitions", value = "1"),
        @ClusterConfigProperty(key = "offsets.topic.replication.factor", value = "1"),
    })
    public void testFetchPartitionsWithAlwaysFailedListenerWithGroupProtocolClassic(ClusterInstance clusterInstance)
            throws InterruptedException {
        testFetchPartitionsWithAlwaysFailedListener(clusterInstance, GroupProtocol.CLASSIC);
    }

    @ClusterTest(serverProperties = {
        @ClusterConfigProperty(key = "offsets.topic.num.partitions", value = "1"),
        @ClusterConfigProperty(key = "offsets.topic.replication.factor", value = "1"),
    })
    public void testFetchPartitionsWithAlwaysFailedListenerWithGroupProtocolConsumer(ClusterInstance clusterInstance)
            throws InterruptedException {
        testFetchPartitionsWithAlwaysFailedListener(clusterInstance, GroupProtocol.CONSUMER);
    }

    private static void testFetchPartitionsWithAlwaysFailedListener(ClusterInstance clusterInstance, GroupProtocol groupProtocol)
            throws InterruptedException {
        var topic = "topic";
        try (var producer = clusterInstance.producer(Map.of(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>(topic, "key".getBytes(), "value".getBytes()));
        }

        try (var consumer = clusterInstance.consumer(Map.of(
                ConsumerConfig.GROUP_PROTOCOL_CONFIG, groupProtocol.name()))) {
            consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    throw new IllegalArgumentException("always failed");
                }
            });

            long startTimeMillis = System.currentTimeMillis();
            long currentTimeMillis = System.currentTimeMillis();
            while (currentTimeMillis < startTimeMillis + 3000) {
                currentTimeMillis = System.currentTimeMillis();
                try {
                    // In the async consumer, there is a possibility that the ConsumerRebalanceListenerCallbackCompletedEvent
                    // has not yet reached the application thread. And a poll operation might still succeed, but it
                    // should not return any records since none of the assigned topic partitions are marked as fetchable.
                    assertEquals(0, consumer.poll(Duration.ofSeconds(1)).count());
                } catch (KafkaException ex) {
                    assertEquals("User rebalance callback throws an error", ex.getMessage());
                }
                Thread.sleep(300);
            }
        }
    }

    @ClusterTest(types = {Type.KRAFT}, brokers = 3)
    public void testLeaderEpoch(ClusterInstance clusterInstance) throws Exception {
        String topic = "test-topic";
        clusterInstance.createTopic(topic, 1, (short) 2);
        var msgNum = 10;
        sendMsg(clusterInstance, topic, msgNum);

        try (var consumer = clusterInstance.consumer()) {
            TopicPartition targetTopicPartition = new TopicPartition(topic, 0);
            List<TopicPartition> topicPartitions = List.of(targetTopicPartition);
            consumer.assign(topicPartitions);
            consumer.seekToBeginning(List.of(targetTopicPartition));

            int consumed = 0;
            while (consumed < msgNum) {
                ConsumerRecords<Object, Object> records = consumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<Object, Object> record : records) {
                    assertTrue(record.leaderEpoch().isPresent());
                    assertEquals(0, record.leaderEpoch().get());
                }
                consumed += records.count();
            }

            // make the leader epoch increment by shutdown the leader broker
            clusterInstance.shutdownBroker(clusterInstance.getLeaderBrokerId(targetTopicPartition));

            sendMsg(clusterInstance, topic, msgNum);

            consumed = 0;
            while (consumed < msgNum) {
                ConsumerRecords<Object, Object> records = consumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<Object, Object> record : records) {
                    assertTrue(record.leaderEpoch().isPresent());
                    assertEquals(1, record.leaderEpoch().get());
                }
                consumed += records.count();
            }
        }
    }

    @ClusterTest(
        types = {Type.KRAFT},
        brokers = 3,
        serverProperties = {
            @ClusterConfigProperty(id = 0, key = "broker.rack", value = "rack0"),
            @ClusterConfigProperty(id = 1, key = "broker.rack", value = "rack1"),
            @ClusterConfigProperty(id = 2, key = "broker.rack", value = "rack2"),
            @ClusterConfigProperty(key = GroupCoordinatorConfig.CONSUMER_GROUP_ASSIGNORS_CONFIG, value = "org.apache.kafka.clients.consumer.RackAwareAssignor")
        }
    )
    public void testRackAwareAssignment(ClusterInstance clusterInstance) throws ExecutionException, InterruptedException {
        String topic = "test-topic";
        try (Admin admin = clusterInstance.admin();
             Producer<byte[], byte[]> producer = clusterInstance.producer();
             Consumer<byte[], byte[]> consumer0 = clusterInstance.consumer(Map.of(
                 ConsumerConfig.GROUP_ID_CONFIG, "group0",
                 ConsumerConfig.CLIENT_RACK_CONFIG, "rack0",
                 ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ));
             Consumer<byte[], byte[]> consumer1 = clusterInstance.consumer(Map.of(
                 ConsumerConfig.GROUP_ID_CONFIG, "group0",
                 ConsumerConfig.CLIENT_RACK_CONFIG, "rack1",
                 ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ));
             Consumer<byte[], byte[]> consumer2 = clusterInstance.consumer(Map.of(
                 ConsumerConfig.GROUP_ID_CONFIG, "group0",
                 ConsumerConfig.CLIENT_RACK_CONFIG, "rack2",
                 ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ))
        ) {
            // Create a new topic with 1 partition on broker 0.
            admin.createTopics(List.of(new NewTopic(topic, Map.of(0, List.of(0)))));
            clusterInstance.waitTopicCreation(topic, 1);

            producer.send(new ProducerRecord<>(topic, "key".getBytes(), "value".getBytes()));
            producer.flush();

            consumer0.subscribe(List.of(topic));
            consumer1.subscribe(List.of(topic));
            consumer2.subscribe(List.of(topic));

            TestUtils.waitForCondition(() -> {
                consumer0.poll(Duration.ofMillis(1000));
                consumer1.poll(Duration.ofMillis(1000));
                consumer2.poll(Duration.ofMillis(1000));
                return consumer0.assignment().equals(Set.of(new TopicPartition(topic, 0))) &&
                    consumer1.assignment().isEmpty() &&
                    consumer2.assignment().isEmpty();
            }, "Consumer 0 should be assigned to topic partition 0");

            // Add a new partition 1 and 2 to broker 1.
            admin.createPartitions(
                Map.of(
                    topic,
                    NewPartitions.increaseTo(3, List.of(List.of(1), List.of(1)))
                )
            );
            clusterInstance.waitTopicCreation(topic, 3);
            TestUtils.waitForCondition(() -> {
                consumer0.poll(Duration.ofMillis(1000));
                consumer1.poll(Duration.ofMillis(1000));
                consumer2.poll(Duration.ofMillis(1000));
                return consumer0.assignment().equals(Set.of(new TopicPartition(topic, 0))) &&
                    consumer1.assignment().equals(Set.of(new TopicPartition(topic, 1), new TopicPartition(topic, 2))) &&
                    consumer2.assignment().isEmpty();
            }, "Consumer 1 should be assigned to topic partition 1 and 2");

            // Add a new partition 3, 4, and 5 to broker 2.
            admin.createPartitions(
                Map.of(
                    topic,
                    NewPartitions.increaseTo(6, List.of(List.of(2), List.of(2), List.of(2)))
                )
            );
            clusterInstance.waitTopicCreation(topic, 6);
            TestUtils.waitForCondition(() -> {
                consumer0.poll(Duration.ofMillis(1000));
                consumer1.poll(Duration.ofMillis(1000));
                consumer2.poll(Duration.ofMillis(1000));
                return consumer0.assignment().equals(Set.of(new TopicPartition(topic, 0))) &&
                    consumer1.assignment().equals(Set.of(new TopicPartition(topic, 1), new TopicPartition(topic, 2))) &&
                    consumer2.assignment().equals(Set.of(new TopicPartition(topic, 3), new TopicPartition(topic, 4), new TopicPartition(topic, 5)));
            }, "Consumer 2 should be assigned to topic partition 3, 4, and 5");

            // Change partitions to different brokers.
            // partition 0 -> broker 2
            // partition 1 -> broker 2
            // partition 2 -> broker 2
            // partition 3 -> broker 1
            // partition 4 -> broker 1
            // partition 5 -> broker 0
            admin.alterPartitionReassignments(Map.of(
                new TopicPartition(topic, 0), Optional.of(new NewPartitionReassignment(List.of(2))),
                new TopicPartition(topic, 1), Optional.of(new NewPartitionReassignment(List.of(2))),
                new TopicPartition(topic, 2), Optional.of(new NewPartitionReassignment(List.of(2))),
                new TopicPartition(topic, 3), Optional.of(new NewPartitionReassignment(List.of(1))),
                new TopicPartition(topic, 4), Optional.of(new NewPartitionReassignment(List.of(1))),
                new TopicPartition(topic, 5), Optional.of(new NewPartitionReassignment(List.of(0)))
            )).all().get();
            TestUtils.waitForCondition(() -> {
                consumer0.poll(Duration.ofMillis(1000));
                consumer1.poll(Duration.ofMillis(1000));
                consumer2.poll(Duration.ofMillis(1000));
                return consumer0.assignment().equals(Set.of(new TopicPartition(topic, 5))) &&
                    consumer1.assignment().equals(Set.of(new TopicPartition(topic, 3), new TopicPartition(topic, 4))) &&
                    consumer2.assignment().equals(Set.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1), new TopicPartition(topic, 2)));
            }, "Consumer with topic partition mapping should be 0 -> 5 | 1 -> 3, 4 | 2 -> 0, 1, 2");
        }
    }

    private void verifyRackAwareAssignmentWith3Partitions(
            String topic,
            Consumer<byte[], byte[]> consumer0,
            Consumer<byte[], byte[]> consumer1,
            Consumer<byte[], byte[]> consumer2
    ) throws InterruptedException {
        TestUtils.waitForCondition(() -> {
            consumer0.poll(Duration.ofMillis(1000));
            consumer1.poll(Duration.ofMillis(1000));
            consumer2.poll(Duration.ofMillis(1000));

            Set<TopicPartition> assignment0 = consumer0.assignment();
            Set<TopicPartition> assignment1 = consumer1.assignment();
            Set<TopicPartition> assignment2 = consumer2.assignment();

            int totalAssigned = assignment0.size() + assignment1.size() + assignment2.size();
            boolean consumer0HasRack0Partition = assignment0.contains(new TopicPartition(topic, 0));
            boolean consumer1HasRack1Partition = assignment1.contains(new TopicPartition(topic, 1)) ||
                    assignment1.contains(new TopicPartition(topic, 2));
            boolean isBalance = assignment0.size() == 1 && assignment1.size() == 1 && assignment2.size() == 1;

            return isBalance && totalAssigned == 3 && consumer0HasRack0Partition && consumer1HasRack1Partition;
        }, "Partitions should be distributed with rack awareness");
    }

    private void verifyRackAwareAssignmentWith6Partitions(
            String topic,
            Consumer<byte[], byte[]> consumer0,
            Consumer<byte[], byte[]> consumer1,
            Consumer<byte[], byte[]> consumer2
    ) throws InterruptedException {
        TestUtils.waitForCondition(() -> {
            consumer0.poll(Duration.ofMillis(1000));
            consumer1.poll(Duration.ofMillis(1000));
            consumer2.poll(Duration.ofMillis(1000));

            Set<TopicPartition> assignment0 = consumer0.assignment();
            Set<TopicPartition> assignment1 = consumer1.assignment();
            Set<TopicPartition> assignment2 = consumer2.assignment();

            int totalAssigned = assignment0.size() + assignment1.size() + assignment2.size();
            boolean balancedAssignment = assignment0.size() == 2 && assignment1.size() == 2 && assignment2.size() == 2;
            boolean consumer0PrefersRack0 = assignment0.contains(new TopicPartition(topic, 0));
            boolean consumer1PrefersRack1 = assignment1.contains(new TopicPartition(topic, 1)) ||
                    assignment1.contains(new TopicPartition(topic, 2));
            boolean consumer2PrefersRack2 = assignment2.contains(new TopicPartition(topic, 3)) ||
                    assignment2.contains(new TopicPartition(topic, 4)) ||
                    assignment2.contains(new TopicPartition(topic, 5));

            return totalAssigned == 6 && balancedAssignment &&
                    consumer0PrefersRack0 && consumer1PrefersRack1 && consumer2PrefersRack2;
        }, "Partitions should be balanced and rack-aware");
    }

    private void verifyRackAwareAssignmentAfterReassignment(
            String topic,
            Consumer<byte[], byte[]> consumer0,
            Consumer<byte[], byte[]> consumer1,
            Consumer<byte[], byte[]> consumer2
    ) throws InterruptedException {
        TestUtils.waitForCondition(() -> {
            consumer0.poll(Duration.ofMillis(1000));
            consumer1.poll(Duration.ofMillis(1000));
            consumer2.poll(Duration.ofMillis(1000));

            Set<TopicPartition> assignment0 = consumer0.assignment();
            Set<TopicPartition> assignment1 = consumer1.assignment();
            Set<TopicPartition> assignment2 = consumer2.assignment();

            int totalAssigned = assignment0.size() + assignment1.size() + assignment2.size();
            boolean balancedAssignment = assignment0.size() == 2 && assignment1.size() == 2 && assignment2.size() == 2;
            boolean consumer0PrefersRack0 = assignment0.contains(new TopicPartition(topic, 5));
            boolean consumer1PrefersRack1 = assignment1.contains(new TopicPartition(topic, 3)) ||
                    assignment1.contains(new TopicPartition(topic, 4));
            boolean consumer2PrefersRack2 = assignment2.contains(new TopicPartition(topic, 0)) ||
                    assignment2.contains(new TopicPartition(topic, 1)) ||
                    assignment2.contains(new TopicPartition(topic, 2));

            return totalAssigned == 6 && balancedAssignment &&
                    consumer0PrefersRack0 && consumer1PrefersRack1 && consumer2PrefersRack2;
        }, 30000, "Partitions should be reassigned with rack awareness");
    }

    @ClusterTest(
        types = {Type.KRAFT},
        brokers = 3,
        serverProperties = {
            @ClusterConfigProperty(id = 0, key = "broker.rack", value = "rack0"),
            @ClusterConfigProperty(id = 1, key = "broker.rack", value = "rack1"),
            @ClusterConfigProperty(id = 2, key = "broker.rack", value = "rack2"),
            @ClusterConfigProperty(key = GroupCoordinatorConfig.CONSUMER_GROUP_ASSIGNORS_CONFIG, value = "org.apache.kafka.coordinator.group.assignor.RangeAssignor")
        }
    )
    public void testRangeAssignorRackAwareAssignment(ClusterInstance clusterInstance) throws ExecutionException, InterruptedException {
        String topic = "test-topic";
        try (Admin admin = clusterInstance.admin();
             Producer<byte[], byte[]> producer = clusterInstance.producer();
             Consumer<byte[], byte[]> consumer0 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack0",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ));
             Consumer<byte[], byte[]> consumer1 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack1",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ));
             Consumer<byte[], byte[]> consumer2 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack2",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ))
        ) {
            // Create a new topic with 1 partition on broker 0.
            admin.createTopics(List.of(new NewTopic(topic, Map.of(0, List.of(0)))));
            clusterInstance.waitTopicCreation(topic, 1);

            producer.send(new ProducerRecord<>(topic, "key".getBytes(), "value".getBytes()));
            producer.flush();

            consumer0.subscribe(List.of(topic));
            consumer1.subscribe(List.of(topic));
            consumer2.subscribe(List.of(topic));

            TestUtils.waitForCondition(() -> {
                consumer0.poll(Duration.ofMillis(1000));
                consumer1.poll(Duration.ofMillis(1000));
                consumer2.poll(Duration.ofMillis(1000));
                return consumer0.assignment().equals(Set.of(new TopicPartition(topic, 0))) &&
                        consumer1.assignment().isEmpty() &&
                        consumer2.assignment().isEmpty();
            }, "Consumer 0 should be assigned to topic partition 0");

            // Add a new partition 1 and 2 to broker 1.
            admin.createPartitions(
                    Map.of(
                            topic,
                            NewPartitions.increaseTo(3, List.of(List.of(1), List.of(1)))
                    )
            );
            clusterInstance.waitTopicCreation(topic, 3);
            verifyRackAwareAssignmentWith3Partitions(topic, consumer0, consumer1, consumer2);

            // Add a new partition 3, 4, and 5 to broker 2.
            admin.createPartitions(
                    Map.of(
                            topic,
                            NewPartitions.increaseTo(6, List.of(List.of(2), List.of(2), List.of(2)))
                    )
            );
            clusterInstance.waitTopicCreation(topic, 6);
            verifyRackAwareAssignmentWith6Partitions(topic, consumer0, consumer1, consumer2);

            // Change partitions to different brokers.
            // partition 0 -> broker 2 (rack2)
            // partition 1 -> broker 2 (rack2)
            // partition 2 -> broker 2 (rack2)
            // partition 3 -> broker 1 (rack1)
            // partition 4 -> broker 1 (rack1)
            // partition 5 -> broker 0 (rack0)
            admin.alterPartitionReassignments(Map.of(
                    new TopicPartition(topic, 0), Optional.of(new NewPartitionReassignment(List.of(2))),
                    new TopicPartition(topic, 1), Optional.of(new NewPartitionReassignment(List.of(2))),
                    new TopicPartition(topic, 2), Optional.of(new NewPartitionReassignment(List.of(2))),
                    new TopicPartition(topic, 3), Optional.of(new NewPartitionReassignment(List.of(1))),
                    new TopicPartition(topic, 4), Optional.of(new NewPartitionReassignment(List.of(1))),
                    new TopicPartition(topic, 5), Optional.of(new NewPartitionReassignment(List.of(0)))
            )).all().get();

            // Wait for reassignment to complete
            TestUtils.waitForCondition(() -> {
                try {
                    var partitionInfo = admin.describeTopics(List.of(topic)).topicNameValues().get(topic).get();
                    return partitionInfo.partitions().get(5).replicas().get(0).id() == 0;
                } catch (Exception e) {
                    return false;
                }
            }, 30000, "Waiting for partition reassignment to complete");

            verifyRackAwareAssignmentAfterReassignment(topic, consumer0, consumer1, consumer2);
        }
    }


    @ClusterTest(
        types = {Type.KRAFT},
        brokers = 3,
        serverProperties = {
            @ClusterConfigProperty(id = 0, key = "broker.rack", value = "rack0"),
            @ClusterConfigProperty(id = 1, key = "broker.rack", value = "rack1"),
            @ClusterConfigProperty(id = 2, key = "broker.rack", value = "rack2"),
            @ClusterConfigProperty(key = GroupCoordinatorConfig.CONSUMER_GROUP_ASSIGNORS_CONFIG, value = "org.apache.kafka.coordinator.group.assignor.RangeAssignor")
        }
    )
    public void testRangeAssignorRackAwareWithCoPartitionedTopics(ClusterInstance clusterInstance) throws ExecutionException, InterruptedException {
        String topic1 = "test-topic1";
        String topic2 = "test-topic2";
        try (Admin admin = clusterInstance.admin();
             Producer<byte[], byte[]> producer = clusterInstance.producer();
             Consumer<byte[], byte[]> consumer0 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack0",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ));
             Consumer<byte[], byte[]> consumer1 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack1",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ));
             Consumer<byte[], byte[]> consumer2 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack2",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ))
        ) {
            // Create two co-partitioned topics with 6 partitions each
            // Partitions distributed across racks: 0,1 on rack0, 2,3 on rack1, 4,5 on rack2
            admin.createTopics(List.of(
                    new NewTopic(topic1, Map.of(
                            0, List.of(0), 1, List.of(0),
                            2, List.of(1), 3, List.of(1),
                            4, List.of(2), 5, List.of(2)
                    )),
                    new NewTopic(topic2, Map.of(
                            0, List.of(0), 1, List.of(0),
                            2, List.of(1), 3, List.of(1),
                            4, List.of(2), 5, List.of(2)
                    ))
            ));
            clusterInstance.waitTopicCreation(topic1, 6);
            clusterInstance.waitTopicCreation(topic2, 6);

            // Produce some data
            for (String topic : List.of(topic1, topic2)) {
                for (int i = 0; i < 6; i++) {
                    producer.send(new ProducerRecord<>(topic, i, null, "value".getBytes()));
                }
            }
            producer.flush();

            // Subscribe all consumers to both topics
            consumer0.subscribe(List.of(topic1, topic2));
            consumer1.subscribe(List.of(topic1, topic2));
            consumer2.subscribe(List.of(topic1, topic2));

            // Verify rack-aware co-partitioned assignment
            // Each consumer should get the same partition numbers from both topics
            // that align with their rack
            TestUtils.waitForCondition(() -> {
                consumer0.poll(Duration.ofMillis(1000));
                consumer1.poll(Duration.ofMillis(1000));
                consumer2.poll(Duration.ofMillis(1000));

                Set<TopicPartition> assignment0 = consumer0.assignment();
                Set<TopicPartition> assignment1 = consumer1.assignment();
                Set<TopicPartition> assignment2 = consumer2.assignment();

                // Consumer 0 (rack0) should get partitions 0,1 from both topics
                boolean consumer0Correct = assignment0.equals(Set.of(
                        new TopicPartition(topic1, 0), new TopicPartition(topic1, 1),
                        new TopicPartition(topic2, 0), new TopicPartition(topic2, 1)
                ));

                // Consumer 1 (rack1) should get partitions 2,3 from both topics
                boolean consumer1Correct = assignment1.equals(Set.of(
                        new TopicPartition(topic1, 2), new TopicPartition(topic1, 3),
                        new TopicPartition(topic2, 2), new TopicPartition(topic2, 3)
                ));

                // Consumer 2 (rack2) should get partitions 4,5 from both topics
                boolean consumer2Correct = assignment2.equals(Set.of(
                        new TopicPartition(topic1, 4), new TopicPartition(topic1, 5),
                        new TopicPartition(topic2, 4), new TopicPartition(topic2, 5)
                ));

                return consumer0Correct && consumer1Correct && consumer2Correct;
            }, "Co-partitioned topics should maintain co-partitioning with rack awareness");
        }
    }

    @ClusterTest(
        types = {Type.KRAFT},
        brokers = 3,
        serverProperties = {
            @ClusterConfigProperty(id = 0, key = "broker.rack", value = "rack0"),
            @ClusterConfigProperty(id = 1, key = "broker.rack", value = "rack1"),
            @ClusterConfigProperty(id = 2, key = "broker.rack", value = "rack2"),
            @ClusterConfigProperty(key = GroupCoordinatorConfig.CONSUMER_GROUP_ASSIGNORS_CONFIG, value = "org.apache.kafka.coordinator.group.assignor.RangeAssignor")
        }
    )
    public void testRangeAssignorRackAwareWithDifferentPartitionCounts(ClusterInstance clusterInstance) throws ExecutionException, InterruptedException {
        String topic1 = "test-topic1";
        String topic2 = "test-topic2";
        try (Admin admin = clusterInstance.admin();
             Producer<byte[], byte[]> producer = clusterInstance.producer();
             Consumer<byte[], byte[]> consumer0 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack0",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ));
             Consumer<byte[], byte[]> consumer1 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack1",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ));
             Consumer<byte[], byte[]> consumer2 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack2",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ))
        ) {
            // Create topics with different partition counts
            // Topic1: 6 partitions (0,1 on rack0, 2,3 on rack1, 4,5 on rack2)
            // Topic2: 3 partitions (0 on rack0, 1 on rack1, 2 on rack2)
            admin.createTopics(List.of(
                    new NewTopic(topic1, Map.of(
                            0, List.of(0), 1, List.of(0),
                            2, List.of(1), 3, List.of(1),
                            4, List.of(2), 5, List.of(2)
                    )),
                    new NewTopic(topic2, Map.of(
                            0, List.of(0),
                            1, List.of(1),
                            2, List.of(2)
                    ))
            ));
            clusterInstance.waitTopicCreation(topic1, 6);
            clusterInstance.waitTopicCreation(topic2, 3);

            // Produce some data
            for (int i = 0; i < 6; i++) {
                producer.send(new ProducerRecord<>(topic1, i, null, "value".getBytes()));
            }
            for (int i = 0; i < 3; i++) {
                producer.send(new ProducerRecord<>(topic2, i, null, "value".getBytes()));
            }
            producer.flush();

            // Subscribe all consumers to both topics
            consumer0.subscribe(List.of(topic1, topic2));
            consumer1.subscribe(List.of(topic1, topic2));
            consumer2.subscribe(List.of(topic1, topic2));

            // Verify rack-aware assignment with different partition counts
            TestUtils.waitForCondition(() -> {
                consumer0.poll(Duration.ofMillis(1000));
                consumer1.poll(Duration.ofMillis(1000));
                consumer2.poll(Duration.ofMillis(1000));

                Set<TopicPartition> assignment0 = consumer0.assignment();
                Set<TopicPartition> assignment1 = consumer1.assignment();
                Set<TopicPartition> assignment2 = consumer2.assignment();

                // Consumer 0 (rack0) should get rack-aligned partitions
                boolean consumer0HasRack0Partitions = assignment0.contains(new TopicPartition(topic1, 0)) ||
                        assignment0.contains(new TopicPartition(topic1, 1)) ||
                        assignment0.contains(new TopicPartition(topic2, 0));

                // Consumer 1 (rack1) should get rack-aligned partitions
                boolean consumer1HasRack1Partitions = assignment1.contains(new TopicPartition(topic1, 2)) ||
                        assignment1.contains(new TopicPartition(topic1, 3)) ||
                        assignment1.contains(new TopicPartition(topic2, 1));

                // Consumer 2 (rack2) should get rack-aligned partitions
                boolean consumer2HasRack2Partitions = assignment2.contains(new TopicPartition(topic1, 4)) ||
                        assignment2.contains(new TopicPartition(topic1, 5)) ||
                        assignment2.contains(new TopicPartition(topic2, 2));

                // All partitions should be assigned
                int totalAssigned = assignment0.size() + assignment1.size() + assignment2.size();

                return consumer0HasRack0Partitions && consumer1HasRack1Partitions &&
                        consumer2HasRack2Partitions && totalAssigned == 9;
            }, "Topics with different partition counts should have rack-aware assignment");
        }
    }

    @ClusterTest(
        types = {Type.KRAFT},
        brokers = 3,
        serverProperties = {
            @ClusterConfigProperty(id = 0, key = "broker.rack", value = "rack0"),
            @ClusterConfigProperty(id = 1, key = "broker.rack", value = "rack1"),
            @ClusterConfigProperty(id = 2, key = "broker.rack", value = "rack2"),
            @ClusterConfigProperty(key = GroupCoordinatorConfig.CONSUMER_GROUP_ASSIGNORS_CONFIG, value = "org.apache.kafka.coordinator.group.assignor.RangeAssignor")
        }
    )
    public void testRangeAssignorRackAwareMixedConsumersWithAndWithoutRack(ClusterInstance clusterInstance) throws ExecutionException, InterruptedException {
        String topic = "test-topic";
        try (Admin admin = clusterInstance.admin();
             Producer<byte[], byte[]> producer = clusterInstance.producer();
             Consumer<byte[], byte[]> consumer0 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack0",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ));
             Consumer<byte[], byte[]> consumer1 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack1",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ));
             Consumer<byte[], byte[]> consumerNoRack = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ))
        ) {
            // Create a topic with 6 partitions distributed across racks
            admin.createTopics(List.of(
                    new NewTopic(topic, Map.of(
                            0, List.of(0), 1, List.of(0),
                            2, List.of(1), 3, List.of(1),
                            4, List.of(2), 5, List.of(2)
                    ))
            ));
            clusterInstance.waitTopicCreation(topic, 6);

            // Produce some data
            for (int i = 0; i < 6; i++) {
                producer.send(new ProducerRecord<>(topic, i, null, "value".getBytes()));
            }
            producer.flush();

            // Subscribe all consumers
            consumer0.subscribe(List.of(topic));
            consumer1.subscribe(List.of(topic));
            consumerNoRack.subscribe(List.of(topic));

            // Verify assignment with mixed rack/no-rack consumers
            TestUtils.waitForCondition(() -> {
                consumer0.poll(Duration.ofMillis(1000));
                consumer1.poll(Duration.ofMillis(1000));
                consumerNoRack.poll(Duration.ofMillis(1000));

                Set<TopicPartition> assignment0 = consumer0.assignment();
                Set<TopicPartition> assignment1 = consumer1.assignment();
                Set<TopicPartition> assignmentNoRack = consumerNoRack.assignment();

                // Each consumer should get 2 partitions (6 partitions / 3 consumers)
                int totalAssigned = assignment0.size() + assignment1.size() + assignmentNoRack.size();

                // Consumers with rack info should prefer their rack partitions when possible
                boolean consumer0PrefersRack0 = assignment0.isEmpty() ||
                        assignment0.contains(new TopicPartition(topic, 0)) ||
                        assignment0.contains(new TopicPartition(topic, 1));

                boolean consumer1PrefersRack1 = assignment1.isEmpty() ||
                        assignment1.contains(new TopicPartition(topic, 2)) ||
                        assignment1.contains(new TopicPartition(topic, 3));

                return totalAssigned == 6 && consumer0PrefersRack0 && consumer1PrefersRack1;
            }, "Mixed consumers with and without rack info should be assigned partitions");
        }
    }

    @ClusterTest(
        types = {Type.KRAFT},
        brokers = 3,
        serverProperties = {
            @ClusterConfigProperty(id = 0, key = "broker.rack", value = "rack0"),
            @ClusterConfigProperty(id = 1, key = "broker.rack", value = "rack1"),
            @ClusterConfigProperty(id = 2, key = "broker.rack", value = "rack2"),
            @ClusterConfigProperty(key = GroupCoordinatorConfig.CONSUMER_GROUP_ASSIGNORS_CONFIG, value = "org.apache.kafka.coordinator.group.assignor.RangeAssignor")
        }
    )
    public void testRangeAssignorRackAwareBalancedAssignmentWithUnevenRacks(ClusterInstance clusterInstance) throws ExecutionException, InterruptedException {
        String topic = "test-topic";
        try (Admin admin = clusterInstance.admin();
             Producer<byte[], byte[]> producer = clusterInstance.producer();
             Consumer<byte[], byte[]> consumer0 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack0",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ));
             Consumer<byte[], byte[]> consumer1 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack0",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ));
             Consumer<byte[], byte[]> consumer2 = clusterInstance.consumer(Map.of(
                     ConsumerConfig.GROUP_ID_CONFIG, "group0",
                     ConsumerConfig.CLIENT_RACK_CONFIG, "rack1",
                     ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name()
             ))
        ) {
            // Create a topic where partitions are unevenly distributed
            // Most partitions on rack0, some on rack1, some on rack2
            admin.createTopics(List.of(
                    new NewTopic(topic, Map.of(
                            0, List.of(0), 1, List.of(0), 2, List.of(0), 3, List.of(0),
                            4, List.of(1), 5, List.of(1),
                            6, List.of(2), 7, List.of(2), 8, List.of(2)
                    ))
            ));
            clusterInstance.waitTopicCreation(topic, 9);

            // Produce some data
            for (int i = 0; i < 9; i++) {
                producer.send(new ProducerRecord<>(topic, i, null, "value".getBytes()));
            }
            producer.flush();

            // Subscribe all consumers
            consumer0.subscribe(List.of(topic));
            consumer1.subscribe(List.of(topic));
            consumer2.subscribe(List.of(topic));

            // Verify balanced assignment prioritizing rack awareness
            TestUtils.waitForCondition(() -> {
                consumer0.poll(Duration.ofMillis(1000));
                consumer1.poll(Duration.ofMillis(1000));
                consumer2.poll(Duration.ofMillis(1000));

                Set<TopicPartition> assignment0 = consumer0.assignment();
                Set<TopicPartition> assignment1 = consumer1.assignment();
                Set<TopicPartition> assignment2 = consumer2.assignment();

                // All 9 partitions should be assigned
                int totalAssigned = assignment0.size() + assignment1.size() + assignment2.size();

                // Each consumer should get 3 partitions (9 / 3 = 3)
                boolean balanced = assignment0.size() == 3 && assignment1.size() == 3 && assignment2.size() == 3;

                return totalAssigned == 9 && balanced;
            }, "Assignment should be balanced even with uneven rack distribution");
        }
    }

    private void sendMsg(ClusterInstance clusterInstance, String topic, int sendMsgNum) {
        try (var producer = clusterInstance.producer(Map.of(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "-1"))) {
            for (int i = 0; i < sendMsgNum; i++) {
                producer.send(new ProducerRecord<>(topic, ("key_" + i), ("value_" + i)));
            }
            producer.flush();
        }
    }
}
