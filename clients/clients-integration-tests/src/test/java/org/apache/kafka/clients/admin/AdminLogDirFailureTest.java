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
package org.apache.kafka.clients.admin;

import org.apache.kafka.clients.ClientsTestUtils;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.test.ClusterInstance;
import org.apache.kafka.common.test.api.ClusterTest;
import org.apache.kafka.common.test.api.ClusterTestDefaults;
import org.apache.kafka.common.test.api.Type;

import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The partition-leader APIs of the Admin client cache the partition leaders. When the log directory of a leader
 * fails, the broker keeps running and returns KAFKA_STORAGE_ERROR for the partition while the leadership moves
 * to another broker. An Admin client that cached the old leader must still complete the next call.
 */
@ClusterTestDefaults(
    types = {Type.KRAFT},
    brokers = 3,
    disksPerBroker = 2
)
public class AdminLogDirFailureTest {
    private static final String TOPIC = "logdir";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final int RECORDS = 10;

    private final ClusterInstance cluster;

    public AdminLogDirFailureTest(ClusterInstance cluster) {
        this.cluster = cluster;
    }

    @BeforeEach
    public void setUp() throws Exception {
        cluster.createTopic(TOPIC, 1, (short) 3);
        try (Producer<byte[], byte[]> producer = cluster.producer()) {
            for (int i = 0; i < RECORDS; i++) {
                producer.send(new ProducerRecord<>(TOPIC, PARTITION.partition(), null, ("value" + i).getBytes())).get();
            }
        }
    }

    private Admin createAdmin() {
        return cluster.admin(Map.of(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15000));
    }

    private static long latestOffset(Admin admin) throws Exception {
        return admin.listOffsets(Map.of(PARTITION, OffsetSpec.latest())).partitionResult(PARTITION).get().offset();
    }

    private static int activeProducers(Admin admin) throws Exception {
        return admin.describeProducers(List.of(PARTITION)).partitionResult(PARTITION).get().activeProducers().size();
    }

    @ClusterTest
    public void testListOffsetsAfterLeaderLogDirFailure() throws Exception {
        try (Admin admin = createAdmin()) {
            // The first call caches the leader.
            assertEquals(RECORDS, latestOffset(admin));

            ClientsTestUtils.failLeaderLogDir(cluster, admin, PARTITION);

            assertEquals(RECORDS, latestOffset(admin));
        }
    }

    @ClusterTest
    public void testDescribeProducersAfterLeaderLogDirFailure() throws Exception {
        try (Admin admin = createAdmin()) {
            // The first call caches the leader.
            assertEquals(1, activeProducers(admin));

            ClientsTestUtils.failLeaderLogDir(cluster, admin, PARTITION);

            assertEquals(1, activeProducers(admin));
        }
    }
}
