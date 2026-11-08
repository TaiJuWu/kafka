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

import org.apache.kafka.clients.ClientsTestUtils;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.test.ClusterInstance;
import org.apache.kafka.common.test.api.ClusterConfigProperty;
import org.apache.kafka.common.test.api.ClusterTest;
import org.apache.kafka.common.test.api.ClusterTestDefaults;
import org.apache.kafka.common.test.api.Type;
import org.apache.kafka.test.TestUtils;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * When the log directory of the partition leader fails, the broker keeps running and returns KAFKA_STORAGE_ERROR
 * for the partition while the leadership moves to another broker. Consumers that were already fetching from the
 * old leader must move to the new leader.
 */
@ClusterTestDefaults(
    types = {Type.KRAFT},
    brokers = 3,
    disksPerBroker = 2,
    serverProperties = {
        @ClusterConfigProperty(key = "offsets.topic.replication.factor", value = "3")
    }
)
public class ConsumerLogDirFailureTest {
    private static final String TOPIC = "logdir";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    private final ClusterInstance cluster;

    public ConsumerLogDirFailureTest(ClusterInstance cluster) {
        this.cluster = cluster;
    }

    @ClusterTest
    public void testClassicConsumerMovesToNewLeaderAfterLogDirFailure() throws Exception {
        testConsumerMovesToNewLeaderAfterLogDirFailure(GroupProtocol.CLASSIC);
    }

    @ClusterTest
    public void testAsyncConsumerMovesToNewLeaderAfterLogDirFailure() throws Exception {
        testConsumerMovesToNewLeaderAfterLogDirFailure(GroupProtocol.CONSUMER);
    }

    private void testConsumerMovesToNewLeaderAfterLogDirFailure(GroupProtocol groupProtocol) throws Exception {
        cluster.createTopic(TOPIC, 1, (short) 3);
        try (Producer<byte[], byte[]> producer = cluster.producer();
             Consumer<byte[], byte[]> consumer = cluster.consumer(Map.of(
                 ConsumerConfig.GROUP_PROTOCOL_CONFIG, groupProtocol.name().toLowerCase(Locale.ROOT),
                 ConsumerConfig.GROUP_ID_CONFIG, "group",
                 ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
             Admin admin = cluster.admin()) {
            consumer.subscribe(List.of(TOPIC));

            produceValues(producer, "before", 10);
            assertEquals(values("before", 10), receiveValues(consumer, 10));

            ClientsTestUtils.failLeaderLogDir(cluster, admin, PARTITION);

            produceValues(producer, "after", 10);
            assertEquals(values("after", 10), receiveValues(consumer, 10));
        }
    }

    private static void produceValues(Producer<byte[], byte[]> producer, String prefix, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            producer.send(new ProducerRecord<>(TOPIC, PARTITION.partition(), null, (prefix + i).getBytes())).get();
        }
    }

    private static Set<String> values(String prefix, int count) {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < count; i++) {
            values.add(prefix + i);
        }
        return values;
    }

    private static Set<String> receiveValues(Consumer<byte[], byte[]> consumer, int count) {
        Set<String> values = new HashSet<>();
        long deadline = System.currentTimeMillis() + TestUtils.DEFAULT_MAX_WAIT_MS;
        while (values.size() < count && System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach(record -> values.add(new String(record.value())));
        }
        return values;
    }
}
