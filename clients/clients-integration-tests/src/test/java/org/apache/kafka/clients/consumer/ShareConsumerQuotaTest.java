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
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.quota.ClientQuotaAlteration;
import org.apache.kafka.common.quota.ClientQuotaEntity;
import org.apache.kafka.common.test.ClusterInstance;
import org.apache.kafka.common.test.api.ClusterTest;
import org.apache.kafka.common.test.api.ClusterTestDefaults;
import org.apache.kafka.common.test.api.Type;

import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.apache.kafka.test.TestUtils.waitForCondition;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ClusterTestDefaults(
    types = {Type.KRAFT},
    brokers = 3
)
public class ShareConsumerQuotaTest extends ShareConsumerTestBase {

    private static final String GROUP = "quota-group";
    private static final String CLIENT_ID = "throttled-client";
    private static final int RECORDS = 400;

    public ShareConsumerQuotaTest(ClusterInstance cluster) {
        super(cluster);
    }

    /**
     * When a ShareFetch is throttled by a client quota, the records which it acquired must not count as delivered.
     * Each record must reach a consumer, and the first time it does, its delivery count must be 1.
     */
    @ClusterTest
    @Timeout(300)
    public void testThrottledShareFetchDoesNotConsumeDeliveryAttempts() throws Exception {
        alterShareAutoOffsetReset(GROUP, "earliest");
        try (Admin admin = createAdminClient()) {
            admin.alterClientQuotas(List.of(new ClientQuotaAlteration(
                new ClientQuotaEntity(Map.of(ClientQuotaEntity.CLIENT_ID, CLIENT_ID)),
                List.of(new ClientQuotaAlteration.Op("consumer_byte_rate", 20000.0))))).all().get();
        }

        byte[] value = new byte[1000];
        try (Producer<byte[], byte[]> producer = createProducer()) {
            for (int i = 0; i < RECORDS; i++) {
                producer.send(new ProducerRecord<>(tp.topic(), tp.partition(), null, value));
            }
            producer.flush();
        }

        // The delivery count of each record the first time that a consumer receives it.
        Map<Long, Short> firstDeliveryCount = new TreeMap<>();
        Map<String, Object> config = Map.of(ConsumerConfig.CLIENT_ID_CONFIG, CLIENT_ID);
        try (ShareConsumer<byte[], byte[]> consumer1 = createShareConsumer(GROUP, config);
             ShareConsumer<byte[], byte[]> consumer2 = createShareConsumer(GROUP, config)) {
            List<ShareConsumer<byte[], byte[]>> consumers = List.of(consumer1, consumer2);
            consumers.forEach(consumer -> consumer.subscribe(List.of(tp.topic())));
            waitForCondition(() -> {
                for (ShareConsumer<byte[], byte[]> consumer : consumers) {
                    consumer.poll(Duration.ofMillis(500)).forEach(record ->
                        firstDeliveryCount.putIfAbsent(record.offset(), record.deliveryCount().orElseThrow()));
                }
                return firstDeliveryCount.size() == RECORDS;
            }, 240000, () -> "only " + firstDeliveryCount.size() + " of " + RECORDS + " records were delivered");
        }

        Map<Long, Short> redelivered = new TreeMap<>();
        firstDeliveryCount.forEach((offset, count) -> {
            if (count != 1) {
                redelivered.put(offset, count);
            }
        });
        assertEquals(Map.of(), redelivered, redelivered.size() + " records were first delivered with a delivery count above 1");
    }
}
