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
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.test.ClusterInstance;
import org.apache.kafka.common.test.JaasUtils;
import org.apache.kafka.common.test.api.ClusterTest;
import org.apache.kafka.common.test.api.ClusterTestDefaults;
import org.apache.kafka.common.test.api.Type;

import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.kafka.test.TestUtils.waitForCondition;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ClusterTestDefaults(
    types = {Type.KRAFT},
    brokers = 3
)
public class ShareConsumerAuthorizationTest extends ShareConsumerTestBase {

    private static final String GROUP = "acl-group";

    public ShareConsumerAuthorizationTest(ClusterInstance cluster) {
        super(cluster);
    }

    /**
     * The READ ACL of the topic is removed while two share consumers consume it, and added back. The consumers
     * must then receive the records again, without being recreated.
     */
    @ClusterTest(
        brokerSecurityProtocol = SecurityProtocol.SASL_PLAINTEXT,
        controllerSecurityProtocol = SecurityProtocol.SASL_PLAINTEXT
    )
    @Timeout(300)
    public void testShareConsumerRecoversAfterTopicReadAclIsRestored() throws Exception {
        alterShareAutoOffsetReset(GROUP, "earliest");
        AclBinding topicRead = allowUser1(ResourceType.TOPIC, tp.topic());
        try (Admin admin = createAdminClient()) {
            admin.createAcls(List.of(topicRead, allowUser1(ResourceType.GROUP, GROUP))).all().get();
        }
        produce("before", 100);

        Map<String, Object> user1 = Map.of(SaslConfigs.SASL_JAAS_CONFIG,
            "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"" + JaasUtils.KAFKA_PLAIN_USER1
                + "\" password=\"" + JaasUtils.KAFKA_PLAIN_USER1_PASSWORD + "\";");
        Set<String> received = new HashSet<>();
        try (Admin admin = createAdminClient();
             ShareConsumer<byte[], byte[]> consumer1 = createShareConsumer(GROUP, user1);
             ShareConsumer<byte[], byte[]> consumer2 = createShareConsumer(GROUP, user1)) {
            List<ShareConsumer<byte[], byte[]>> consumers = List.of(consumer1, consumer2);
            consumers.forEach(consumer -> consumer.subscribe(List.of(tp.topic())));
            waitForCondition(() -> poll(consumers, received) == 0 && !received.isEmpty(), 60000,
                "no record was delivered before the ACL was removed");

            // The topic READ ACL is removed for a while, and the consumers get authorization errors.
            admin.deleteAcls(List.of(topicRead.toFilter())).all().get();
            long deadline = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < deadline) {
                poll(consumers, received);
            }

            // Once the ACL is back, the consumers must receive the remaining records and the new ones, without
            // throwing authorization errors from before the ACL was restored.
            admin.createAcls(List.of(topicRead)).all().get();
            produce("after", 100);
            int[] errorsAfterRestore = {0};
            waitForCondition(() -> {
                errorsAfterRestore[0] += poll(consumers, received);
                return received.size() == 200;
            }, 120000, () -> "only " + received.size() + " of 200 records were delivered after the ACL was restored");
            assertTrue(errorsAfterRestore[0] <= 10, errorsAfterRestore[0]
                + " authorization errors were thrown after the ACL was restored");
        }
    }

    /**
     * Polls each consumer once and adds the values of the records to {@code received}. Returns the number of
     * consumers which threw a {@link TopicAuthorizationException}.
     */
    private static int poll(List<ShareConsumer<byte[], byte[]>> consumers, Set<String> received) {
        int errors = 0;
        for (ShareConsumer<byte[], byte[]> consumer : consumers) {
            try {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> received.add(new String(record.value())));
            } catch (TopicAuthorizationException e) {
                errors++;
            }
        }
        return errors;
    }

    private void produce(String prefix, int count) {
        try (Producer<byte[], byte[]> producer = createProducer()) {
            for (int i = 0; i < count; i++) {
                producer.send(new ProducerRecord<>(tp.topic(), tp.partition(), null, (prefix + "-" + i).getBytes()));
            }
            producer.flush();
        }
    }

    private static AclBinding allowUser1(ResourceType resourceType, String name) {
        return new AclBinding(new ResourcePattern(resourceType, name, PatternType.LITERAL),
            new AccessControlEntry("User:" + JaasUtils.KAFKA_PLAIN_USER1, "*", AclOperation.READ, AclPermissionType.ALLOW));
    }
}
