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
package org.apache.kafka.coordinator.mirror;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.internals.Topic;
import org.apache.kafka.common.message.ReadMirrorStatesRequestData;
import org.apache.kafka.common.message.ReadMirrorStatesResponseData;
import org.apache.kafka.common.message.WriteMirrorStatesRequestData;
import org.apache.kafka.common.message.WriteMirrorStatesResponseData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.ApiError;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.internals.LogContext;
import org.apache.kafka.coordinator.common.runtime.CoordinatorEventProcessor;
import org.apache.kafka.coordinator.common.runtime.CoordinatorLoader;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetadataDelta;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetadataImage;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRecord;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRuntime;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRuntimeMetrics;
import org.apache.kafka.coordinator.common.runtime.MultiThreadedEventProcessor;
import org.apache.kafka.coordinator.common.runtime.PartitionWriter;
import org.apache.kafka.server.mirror.MirrorPartition;
import org.apache.kafka.server.mirror.MirrorPartitionState;
import org.apache.kafka.server.util.timer.Timer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.IntSupplier;

/**
 * The cluster mirror coordinator (KIP-1279): it keeps the states of the mirror partitions in the __mirror_state topic,
 * whose partitions are spread over the brokers. The state of a mirror partition is kept by the leader of the
 * __mirror_state partition that its key (mirror name, topic id, partition) hashes to.
 */
public class MirrorCoordinatorService {
    private final ClusterMirrorBrokerConfig config;
    private final CoordinatorRuntime<MirrorCoordinatorShard, CoordinatorRecord> runtime;
    private volatile IntSupplier numPartitions = () -> -1;
    private volatile CoordinatorMetadataImage metadataImage = CoordinatorMetadataImage.EMPTY;

    MirrorCoordinatorService(ClusterMirrorBrokerConfig config,
                             CoordinatorRuntime<MirrorCoordinatorShard, CoordinatorRecord> runtime) {
        this.config = config;
        this.runtime = runtime;
    }

    public void startup(IntSupplier numPartitions) {
        this.numPartitions = numPartitions;
    }

    public void shutdown() throws Exception {
        runtime.close();
    }

    /**
     * @return the configurations of the __mirror_state topic
     */
    public Properties mirrorStateTopicConfigs() {
        Properties properties = new Properties();
        properties.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT);
        properties.put(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(config.stateTopicMinISR()));
        properties.put(TopicConfig.SEGMENT_BYTES_CONFIG, String.valueOf(config.stateTopicSegmentBytes()));
        return properties;
    }

    public int partitionFor(MirrorPartition partition) {
        return partition.coordinatingPartition(numPartitions.getAsInt());
    }

    public void onElection(int partitionIndex, int partitionLeaderEpoch) {
        runtime.scheduleLoadOperation(new TopicPartition(Topic.MIRROR_STATE_TOPIC_NAME, partitionIndex), partitionLeaderEpoch);
    }

    public void onResignation(int partitionIndex, OptionalInt partitionLeaderEpoch) {
        runtime.scheduleUnloadOperation(new TopicPartition(Topic.MIRROR_STATE_TOPIC_NAME, partitionIndex), partitionLeaderEpoch);
    }

    public void onMetadataUpdate(CoordinatorMetadataDelta delta, CoordinatorMetadataImage newImage) {
        this.metadataImage = newImage;
        runtime.onMetadataUpdate(delta, newImage);
    }

    private Optional<MirrorPartition> mirrorPartition(String mirrorName, String topicName, int partition) {
        return metadataImage.topicMetadata(topicName).map(topic -> new MirrorPartition(mirrorName, topic.id(), partition));
    }

    private TopicPartition coordinatorPartition(MirrorPartition partition) {
        return new TopicPartition(Topic.MIRROR_STATE_TOPIC_NAME, partitionFor(partition));
    }

    public CompletableFuture<ReadMirrorStatesResponseData> readMirrorStates(ReadMirrorStatesRequestData request) {
        ReadMirrorStatesResponseData response = new ReadMirrorStatesResponseData();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (ReadMirrorStatesRequestData.TopicMetadata topic : request.topics()) {
            ReadMirrorStatesResponseData.TopicResult topicResult = new ReadMirrorStatesResponseData.TopicResult()
                .setTopicName(topic.topicName());
            response.topics().add(topicResult);
            for (ReadMirrorStatesRequestData.PartitionData partitionData : topic.partitions()) {
                ReadMirrorStatesResponseData.PartitionResult result = new ReadMirrorStatesResponseData.PartitionResult()
                    .setPartitionIndex(partitionData.partitionIndex())
                    .setState(MirrorPartitionState.UNKNOWN.value());
                topicResult.partitions().add(result);
                Optional<MirrorPartition> key = mirrorPartition(request.mirrorName(), topic.topicName(), partitionData.partitionIndex());
                if (key.isEmpty()) {
                    result.setErrorCode(Errors.UNKNOWN_TOPIC_OR_PARTITION.code());
                    continue;
                }
                futures.add(runtime.scheduleReadOperation("read-mirror-states", coordinatorPartition(key.get()),
                        (shard, offset) -> shard.read(key.get(), offset))
                    .handle((state, exception) -> {
                        if (exception != null) {
                            ApiError error = ApiError.fromThrowable(exception);
                            result.setErrorCode(error.error().code()).setErrorMessage(error.message());
                        } else {
                            state.ifPresent(s -> {
                                result.setState(s.state().state())
                                    .setLeaderEpoch(s.state().leaderEpoch())
                                    .setStateEpoch(s.state().stateEpoch())
                                    .setRetryAttempt(s.state().retryAttempt())
                                    .setPreviousState(s.state().previousState())
                                    .setErrorMessage(s.state().errorMessage());
                                s.lastMirror().ifPresent(lastMirror -> result
                                    .setLastMirrorEpoch(lastMirror.lastMirrorEpoch())
                                    .setLastMirrorOffset(lastMirror.lastMirrorOffset()));
                            });
                        }
                        return null;
                    }));
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).thenApply(v -> response);
    }

    public CompletableFuture<WriteMirrorStatesResponseData> writeMirrorStates(WriteMirrorStatesRequestData request) {
        WriteMirrorStatesResponseData response = new WriteMirrorStatesResponseData();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (WriteMirrorStatesRequestData.TopicMetadata topic : request.topics()) {
            WriteMirrorStatesResponseData.TopicResult topicResult = new WriteMirrorStatesResponseData.TopicResult()
                .setTopicName(topic.topicName());
            response.topics().add(topicResult);
            for (WriteMirrorStatesRequestData.PartitionData partitionData : topic.partitions()) {
                WriteMirrorStatesResponseData.PartitionResult result = new WriteMirrorStatesResponseData.PartitionResult()
                    .setPartitionIndex(partitionData.partitionIndex());
                topicResult.partitions().add(result);
                Optional<MirrorPartition> key = mirrorPartition(request.mirrorName(), topic.topicName(), partitionData.partitionIndex());
                if (key.isEmpty()) {
                    result.setErrorCode(Errors.UNKNOWN_TOPIC_OR_PARTITION.code());
                    continue;
                }
                futures.add(runtime.scheduleWriteOperation("write-mirror-states", coordinatorPartition(key.get()),
                        shard -> shard.write(key.get(), partitionData))
                    .handle((writeResult, exception) -> {
                        if (exception != null) {
                            ApiError error = ApiError.fromThrowable(exception);
                            result.setErrorCode(error.error().code()).setErrorMessage(error.message());
                        } else {
                            result.setErrorCode(writeResult.error().code())
                                .setErrorMessage(writeResult.message())
                                .setStateEpoch(writeResult.stateEpoch());
                        }
                        return null;
                    }));
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).thenApply(v -> response);
    }

    /**
     * Removes the states of the given mirror partitions, for instance when their cluster mirror is deleted.
     */
    public CompletableFuture<Void> deleteMirrorStates(List<MirrorPartition> partitions) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (MirrorPartition partition : partitions) {
            futures.add(runtime.scheduleWriteOperation("delete-mirror-states", coordinatorPartition(partition),
                shard -> shard.delete(partition)));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    /**
     * Removes the states of all the partitions of a deleted cluster mirror, in the shards which this broker leads.
     */
    public CompletableFuture<Void> deleteClusterMirrorStates(String mirrorName) {
        List<CompletableFuture<Void>> futures = runtime.scheduleWriteAllOperation("delete-cluster-mirror-states",
            shard -> shard.deleteMirror(mirrorName));
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    public static class Builder {
        private final int nodeId;
        private final ClusterMirrorBrokerConfig config;
        private PartitionWriter writer;
        private CoordinatorLoader<CoordinatorRecord> loader;
        private Time time;
        private Timer timer;
        private CoordinatorRuntimeMetrics runtimeMetrics;

        public Builder(int nodeId, ClusterMirrorBrokerConfig config) {
            this.nodeId = nodeId;
            this.config = config;
        }

        public Builder withWriter(PartitionWriter writer) {
            this.writer = writer;
            return this;
        }

        public Builder withLoader(CoordinatorLoader<CoordinatorRecord> loader) {
            this.loader = loader;
            return this;
        }

        public Builder withTime(Time time) {
            this.time = time;
            return this;
        }

        public Builder withTimer(Timer timer) {
            this.timer = timer;
            return this;
        }

        public Builder withRuntimeMetrics(CoordinatorRuntimeMetrics runtimeMetrics) {
            this.runtimeMetrics = runtimeMetrics;
            return this;
        }

        public MirrorCoordinatorService build() {
            String logPrefix = String.format("MirrorCoordinator id=%d", nodeId);
            LogContext logContext = new LogContext(String.format("[%s] ", logPrefix));
            CoordinatorEventProcessor processor = new MultiThreadedEventProcessor(
                logContext,
                "mirror-coordinator-event-processor-",
                config.coordinatorNumThreads(),
                time,
                runtimeMetrics
            );
            CoordinatorRuntime<MirrorCoordinatorShard, CoordinatorRecord> runtime =
                new CoordinatorRuntime.Builder<MirrorCoordinatorShard, CoordinatorRecord>()
                    .withTime(time)
                    .withTimer(timer)
                    .withLogPrefix(logPrefix)
                    .withLogContext(logContext)
                    .withEventProcessor(processor)
                    .withPartitionWriter(writer)
                    .withLoader(loader)
                    .withCoordinatorShardBuilderSupplier(MirrorCoordinatorShard.Builder::new)
                    .withWriteTimeout(Duration.ofMillis(config.coordinatorWriteTimeoutMs()))
                    .withCoordinatorRuntimeMetrics(runtimeMetrics)
                    .withCoordinatorMetrics(new MirrorCoordinatorMetrics())
                    .withSerializer(new MirrorCoordinatorRecordSerde())
                    .withAppendLingerMs(config.coordinatorAppendLingerMs() < 0 ? OptionalInt.empty() : OptionalInt.of(config.coordinatorAppendLingerMs()))
                    .withExecutorService(Executors.newSingleThreadExecutor())
                    .withCachedBufferMaxBytesSupplier(() -> config.coordinatorLoadBufferSize())
                    .build();
            return new MirrorCoordinatorService(config, runtime);
        }
    }
}
