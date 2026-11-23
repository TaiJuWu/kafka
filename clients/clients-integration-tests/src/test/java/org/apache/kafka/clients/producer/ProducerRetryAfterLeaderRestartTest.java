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
package org.apache.kafka.clients.producer;

import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.ClientRequest;
import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.clients.ClientUtils;
import org.apache.kafka.clients.KafkaClient;
import org.apache.kafka.clients.LeastLoadedNode;
import org.apache.kafka.clients.RequestCompletionHandler;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewPartitionReassignment;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.internals.ProducerMetadata;
import org.apache.kafka.common.ElectionType;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.internals.ClusterResourceListeners;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.test.ClusterInstance;
import org.apache.kafka.common.test.api.ClusterConfigProperty;
import org.apache.kafka.common.test.api.ClusterTest;
import org.apache.kafka.common.test.api.Type;
import org.apache.kafka.common.utils.internals.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.server.config.ReplicationConfigs;
import org.apache.kafka.test.TestUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ProducerRetryAfterLeaderRestartTest {

    private static final String TOPIC = "topic";
    private static final TopicPartition TP = new TopicPartition(TOPIC, 0);

    private final ClusterInstance cluster;

    public ProducerRetryAfterLeaderRestartTest(ClusterInstance cluster) {
        this.cluster = cluster;
    }

    @ClusterTest(types = {Type.KRAFT}, brokers = 2, serverProperties = {
        @ClusterConfigProperty(key = ReplicationConfigs.AUTO_LEADER_REBALANCE_ENABLE_CONFIG, value = "false")
    })
    public void testNoDuplicateWhenRetryingToRestartedFollowerThatBecameLeader() throws Exception {
        // The responses of two batches are lost, while the follower replicates them, restarts and takes over the
        // leadership (a rolling restart followed by a preferred leader election). The producer then retries the
        // batches to the new leader, which must recognise them as duplicates instead of writing them again.
        cluster.createTopic(TOPIC, 1, (short) 2);
        int oldLeader = leader();
        int newLeader = replicas().stream().filter(id -> id != oldLeader).findFirst().orElseThrow();

        List<Future<RecordMetadata>> futures = new ArrayList<>();
        ResponseDroppingClient client = new ResponseDroppingClient();
        try (KafkaProducer<String, String> producer = createProducer(client)) {
            futures.add(producer.send(new ProducerRecord<>(TOPIC, 0, null, "0")));
            futures.get(0).get();

            client.dropProduceResponsesFrom(oldLeader);
            for (int i = 1; i <= 2; i++) {
                futures.add(producer.send(new ProducerRecord<>(TOPIC, 0, null, String.valueOf(i))));
                long expectedHighWatermark = i + 1;
                TestUtils.waitForCondition(() -> highWatermark() == expectedHighWatermark,
                    "record " + i + " was not replicated");
            }

            cluster.shutdownBroker(newLeader);
            cluster.startBroker(newLeader);
            TestUtils.waitForCondition(() -> isr().contains(newLeader), "broker " + newLeader + " did not rejoin the ISR");
            try (Admin admin = cluster.admin()) {
                admin.alterPartitionReassignments(Map.of(TP,
                    Optional.of(new NewPartitionReassignment(List.of(newLeader, oldLeader))))).all().get();
                TestUtils.waitForCondition(() -> replicas().equals(List.of(newLeader, oldLeader)),
                    "the replicas of " + TP + " were not reordered");
                admin.electLeaders(ElectionType.PREFERRED, Set.of(TP)).all().get();
            }
            TestUtils.waitForCondition(() -> leader() == newLeader, "broker " + newLeader + " did not become the leader");

            client.stopDropping();
            for (Future<RecordMetadata> future : futures) {
                future.get();
            }
        }

        assertEquals(List.of("0", "1", "2"), readAll());
    }

    private KafkaProducer<String, String> createProducer(ResponseDroppingClient client) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 300_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
        ProducerConfig config = new ProducerConfig(
            ProducerConfig.appendSerializerToConfig(props, new StringSerializer(), new StringSerializer()));

        LogContext logContext = new LogContext();
        ProducerMetadata metadata = new ProducerMetadata(
            config.getLong(ProducerConfig.RETRY_BACKOFF_MS_CONFIG),
            config.getLong(ProducerConfig.RETRY_BACKOFF_MAX_MS_CONFIG),
            config.getLong(ProducerConfig.METADATA_MAX_AGE_CONFIG),
            config.getLong(ProducerConfig.METADATA_MAX_IDLE_CONFIG),
            logContext,
            new ClusterResourceListeners());
        List<String> bootstrapServers = config.getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG);
        ClientUtils.maybeBootstrapMetadataSynchronously(config, bootstrapServers, metadata);
        ApiVersions apiVersions = new ApiVersions();
        Uuid clientInstanceId = Uuid.randomUuid();
        KafkaClient networkClient = ClientUtils.createNetworkClient(config, bootstrapServers, clientInstanceId,
            new Metrics(), "producer", logContext, apiVersions, Time.SYSTEM,
            config.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION), metadata, null, null);
        client.setDelegate(networkClient);
        return new KafkaProducer<>(config, new StringSerializer(), new StringSerializer(), metadata, client,
            clientInstanceId, null, apiVersions, Time.SYSTEM);
    }

    private List<String> readAll() throws ExecutionException, InterruptedException {
        long endOffset;
        try (Admin admin = cluster.admin()) {
            endOffset = admin.listOffsets(Map.of(TP, OffsetSpec.latest())).partitionResult(TP).get().offset();
        }
        List<String> values = new ArrayList<>();
        try (Consumer<String, String> consumer = cluster.consumer(Map.of(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer",
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer"))) {
            consumer.assign(List.of(TP));
            consumer.seekToBeginning(List.of(TP));
            TestUtils.waitForCondition(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                    values.add(record.value());
                }
                return consumer.position(TP) >= endOffset;
            }, "did not read up to offset " + endOffset);
        }
        return values;
    }

    private long highWatermark() throws ExecutionException, InterruptedException {
        try (Admin admin = cluster.admin()) {
            return admin.listOffsets(Map.of(TP, OffsetSpec.latest())).partitionResult(TP).get().offset();
        }
    }

    private int leader() throws ExecutionException, InterruptedException {
        Node leader = partitionInfo().leader();
        return leader == null ? -1 : leader.id();
    }

    private List<Integer> replicas() throws ExecutionException, InterruptedException {
        return partitionInfo().replicas().stream().map(Node::id).toList();
    }

    private List<Integer> isr() throws ExecutionException, InterruptedException {
        return partitionInfo().isr().stream().map(Node::id).toList();
    }

    private TopicPartitionInfo partitionInfo() throws ExecutionException, InterruptedException {
        try (Admin admin = cluster.admin()) {
            TopicDescription description = admin.describeTopics(List.of(TOPIC)).topicNameValues().get(TOPIC).get();
            return description.partitions().get(0);
        }
    }

    /**
     * Delivers produce requests to the broker as usual, but replaces the responses from the chosen broker with a
     * disconnection, so the producer does not know whether the batches were written and retries them.
     */
    private static class ResponseDroppingClient implements KafkaClient {
        private KafkaClient delegate;
        private volatile String droppedNode;

        void setDelegate(KafkaClient delegate) {
            this.delegate = delegate;
        }

        void dropProduceResponsesFrom(int brokerId) {
            droppedNode = String.valueOf(brokerId);
        }

        void stopDropping() {
            droppedNode = null;
        }

        @Override
        public ClientRequest newClientRequest(String nodeId, AbstractRequest.Builder<?> requestBuilder,
                                              long createdTimeMs, boolean expectResponse, int requestTimeoutMs,
                                              RequestCompletionHandler callback) {
            RequestCompletionHandler handler = callback;
            if (requestBuilder.apiKey() == ApiKeys.PRODUCE && callback != null) {
                handler = response -> {
                    if (response.destination().equals(droppedNode) && !response.wasDisconnected()) {
                        callback.onComplete(new ClientResponse(response.requestHeader(), callback,
                            response.destination(), response.receivedTimeMs() - response.requestLatencyMs(),
                            response.receivedTimeMs(),
                            true, null, null, null));
                    } else {
                        callback.onComplete(response);
                    }
                };
            }
            return delegate.newClientRequest(nodeId, requestBuilder, createdTimeMs, expectResponse, requestTimeoutMs,
                handler);
        }

        @Override
        public ClientRequest newClientRequest(String nodeId, AbstractRequest.Builder<?> requestBuilder,
                                              long createdTimeMs, boolean expectResponse) {
            return delegate.newClientRequest(nodeId, requestBuilder, createdTimeMs, expectResponse);
        }

        @Override
        public boolean isReady(Node node, long now) {
            return delegate.isReady(node, now);
        }

        @Override
        public boolean ready(Node node, long now) {
            return delegate.ready(node, now);
        }

        @Override
        public long connectionDelay(Node node, long now) {
            return delegate.connectionDelay(node, now);
        }

        @Override
        public long pollDelayMs(Node node, long now) {
            return delegate.pollDelayMs(node, now);
        }

        @Override
        public boolean connectionFailed(Node node) {
            return delegate.connectionFailed(node);
        }

        @Override
        public AuthenticationException authenticationException(Node node) {
            return delegate.authenticationException(node);
        }

        @Override
        public void send(ClientRequest request, long now) {
            delegate.send(request, now);
        }

        @Override
        public List<ClientResponse> poll(long timeout, long now) {
            return delegate.poll(timeout, now);
        }

        @Override
        public void disconnect(String nodeId) {
            delegate.disconnect(nodeId);
        }

        @Override
        public void close(String nodeId) {
            delegate.close(nodeId);
        }

        @Override
        public LeastLoadedNode leastLoadedNode(long now) {
            return delegate.leastLoadedNode(now);
        }

        @Override
        public int inFlightRequestCount() {
            return delegate.inFlightRequestCount();
        }

        @Override
        public boolean hasInFlightRequests() {
            return delegate.hasInFlightRequests();
        }

        @Override
        public int inFlightRequestCount(String nodeId) {
            return delegate.inFlightRequestCount(nodeId);
        }

        @Override
        public boolean hasInFlightRequests(String nodeId) {
            return delegate.hasInFlightRequests(nodeId);
        }

        @Override
        public boolean hasReadyNodes(long now) {
            return delegate.hasReadyNodes(now);
        }

        @Override
        public void wakeup() {
            delegate.wakeup();
        }

        @Override
        public void initiateClose() {
            delegate.initiateClose();
        }

        @Override
        public boolean active() {
            return delegate.active();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
