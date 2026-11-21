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
package org.apache.kafka.controller;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.message.CreateClusterMirrorRequestData;
import org.apache.kafka.common.message.CreateClusterMirrorResponseData;
import org.apache.kafka.common.message.DeleteClusterMirrorRequestData;
import org.apache.kafka.common.message.DeleteClusterMirrorResponseData;
import org.apache.kafka.common.message.StartMirrorTopicsRequestData;
import org.apache.kafka.common.metadata.ConfigRecord;
import org.apache.kafka.common.metadata.MirrorTopicStateChangeRecord;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.ApiError;
import org.apache.kafka.server.common.ApiMessageAndVersion;
import org.apache.kafka.server.common.MetadataVersion;
import org.apache.kafka.server.mirror.MirrorPartitionState;
import org.apache.kafka.timeline.SnapshotRegistry;
import org.apache.kafka.timeline.TimelineHashMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks the cluster mirror state of the topics on the controller (KIP-1279): the cluster mirror of each mirror
 * topic, its desired state, and the offset of the record which last changed it, which the lifecycle operations use to
 * reject requests validated on a stale metadata image.
 */
public class ClusterMirrorControlManager {

    /**
     * The cluster mirror of a topic, the desired state of its partitions, and the offset of its last change.
     */
    public record MirrorTopic(String mirrorName, byte desiredState, long lastChangeOffset) { }

    static final String BOOTSTRAP_SERVERS_CONFIG = "bootstrap.servers";

    private final TimelineHashMap<String, MirrorTopic> topics;

    public ClusterMirrorControlManager(SnapshotRegistry snapshotRegistry) {
        this.topics = new TimelineHashMap<>(snapshotRegistry, 0);
    }

    public void replay(MirrorTopicStateChangeRecord record, long offset) {
        if (record.mirrorName() == null) {
            topics.remove(record.topicName());
        } else {
            topics.put(record.topicName(), new MirrorTopic(record.mirrorName(), record.desiredState(), offset));
        }
    }

    public Optional<MirrorTopic> mirrorTopic(String topicName) {
        return Optional.ofNullable(topics.get(topicName));
    }

    public List<String> topicsOf(String mirrorName) {
        List<String> result = new ArrayList<>();
        topics.forEach((topic, mirrorTopic) -> {
            if (mirrorTopic.mirrorName().equals(mirrorName)) result.add(topic);
        });
        return result;
    }

    private static ConfigResource resource(String mirrorName) {
        return new ConfigResource(ConfigResource.Type.CLUSTER_MIRROR, mirrorName);
    }

    static boolean mirrorExists(ConfigurationControlManager configs, String mirrorName) {
        return !configs.getConfigs(resource(mirrorName)).isEmpty();
    }

    private static Optional<ApiError> checkSupported(MetadataVersion metadataVersion) {
        if (!metadataVersion.isClusterMirroringSupported()) {
            return Optional.of(new ApiError(Errors.UNSUPPORTED_VERSION,
                "Cluster mirroring is not supported by metadata version " + metadataVersion));
        }
        return Optional.empty();
    }

    /**
     * Rejects an operation validated by a broker on a metadata image older than the last change of the topic.
     */
    private Optional<ApiError> checkStateOffset(String topic, long stateOffset) {
        if (stateOffset < 0) return Optional.empty();
        return mirrorTopic(topic).filter(t -> t.lastChangeOffset() > stateOffset).map(t -> new ApiError(
            Errors.INVALID_CLUSTER_MIRROR_STATE, "The mirror state of topic " + topic + " changed at offset " +
                t.lastChangeOffset() + " after the validation at offset " + stateOffset));
    }

    private static ApiMessageAndVersion stateRecord(String topic, String mirrorName, MirrorPartitionState state) {
        return new ApiMessageAndVersion(new MirrorTopicStateChangeRecord()
            .setTopicName(topic).setMirrorName(mirrorName).setDesiredState(state.value()), (short) 0);
    }

    ControllerResult<CreateClusterMirrorResponseData> createClusterMirror(CreateClusterMirrorRequestData request,
                                                                          ConfigurationControlManager configs,
                                                                          MetadataVersion metadataVersion) {
        Optional<ApiError> error = checkSupported(metadataVersion);
        if (error.isEmpty() && (request.mirrorName().isEmpty() || request.mirrorName().contains("."))) {
            error = Optional.of(new ApiError(Errors.INVALID_REQUEST, "Invalid cluster mirror name " + request.mirrorName()));
        }
        if (error.isEmpty() && mirrorExists(configs, request.mirrorName())) {
            error = Optional.of(new ApiError(Errors.CLUSTER_MIRROR_ALREADY_EXISTS, "Cluster mirror " + request.mirrorName() + " already exists"));
        }
        Map<String, String> mirrorConfigs = new LinkedHashMap<>();
        request.config().forEach(config -> mirrorConfigs.put(config.name(), config.value()));
        if (error.isEmpty()) {
            error = mirrorConfigs.keySet().stream()
                .filter(name -> !configs.isSupportedConfig(ConfigResource.Type.CLUSTER_MIRROR, name)).findFirst()
                .map(name -> new ApiError(Errors.INVALID_CONFIG, "Unknown cluster mirror config " + name));
        }
        if (error.isEmpty() && mirrorConfigs.get(BOOTSTRAP_SERVERS_CONFIG) == null) {
            error = Optional.of(new ApiError(Errors.INVALID_CONFIG, BOOTSTRAP_SERVERS_CONFIG + " is required"));
        }
        if (error.isPresent()) {
            return ControllerResult.of(List.of(), new CreateClusterMirrorResponseData()
                .setErrorCode(error.get().error().code()).setErrorMessage(error.get().message()));
        }
        List<ApiMessageAndVersion> records = new ArrayList<>();
        mirrorConfigs.forEach((name, value) -> records.add(new ApiMessageAndVersion(new ConfigRecord()
            .setResourceType(ConfigResource.Type.CLUSTER_MIRROR.id())
            .setResourceName(request.mirrorName())
            .setName(name)
            .setValue(value), (short) 0)));
        return ControllerResult.atomicOf(records, new CreateClusterMirrorResponseData());
    }

    /**
     * Deletes a cluster mirror: its topics are disassociated from it and its configs are removed, atomically.
     */
    ControllerResult<DeleteClusterMirrorResponseData> deleteClusterMirror(DeleteClusterMirrorRequestData request,
                                                                          ConfigurationControlManager configs,
                                                                          MetadataVersion metadataVersion) {
        Optional<ApiError> error = checkSupported(metadataVersion);
        if (error.isEmpty() && !mirrorExists(configs, request.mirrorName())) {
            error = Optional.of(new ApiError(Errors.UNKNOWN_CLUSTER_MIRROR, "Cluster mirror " + request.mirrorName() + " does not exist"));
        }
        List<String> topics = topicsOf(request.mirrorName());
        if (error.isEmpty()) {
            error = topics.stream().map(topic -> checkStateOffset(topic, request.stateOffset()))
                .flatMap(Optional::stream).findFirst();
        }
        if (error.isPresent()) {
            return ControllerResult.of(List.of(), new DeleteClusterMirrorResponseData()
                .setErrorCode(error.get().error().code()).setErrorMessage(error.get().message()));
        }
        List<ApiMessageAndVersion> records = new ArrayList<>();
        topics.forEach(topic -> records.add(stateRecord(topic, null, MirrorPartitionState.UNKNOWN)));
        configs.getConfigs(resource(request.mirrorName())).keySet().forEach(name ->
            records.add(new ApiMessageAndVersion(new ConfigRecord()
                .setResourceType(ConfigResource.Type.CLUSTER_MIRROR.id())
                .setResourceName(request.mirrorName())
                .setName(name)
                .setValue(null), (short) 0)));
        return ControllerResult.atomicOf(records, new DeleteClusterMirrorResponseData());
    }

    /**
     * Starts the mirroring of topics: a topic which does not exist is created with the topic id and the number of
     * partitions of its source topic, and its desired state becomes MIRRORING.
     *
     * @return the error of each topic, or NONE
     */
    ControllerResult<Map<String, ApiError>> startMirrorTopics(ControllerRequestContext context,
                                                              StartMirrorTopicsRequestData request,
                                                              ConfigurationControlManager configs,
                                                              ReplicationControlManager replication,
                                                              MetadataVersion metadataVersion) {
        Map<String, ApiError> results = new LinkedHashMap<>();
        List<ApiMessageAndVersion> records = new ArrayList<>();
        Optional<ApiError> error = checkSupported(metadataVersion);
        if (error.isEmpty() && !mirrorExists(configs, request.mirrorName())) {
            error = Optional.of(new ApiError(Errors.UNKNOWN_CLUSTER_MIRROR, "Cluster mirror " + request.mirrorName() + " does not exist"));
        }
        if (request.topics() == null) {
            return ControllerResult.of(List.of(), results);
        }
        for (StartMirrorTopicsRequestData.TopicMetadata topic : request.topics()) {
            if (error.isPresent()) {
                results.put(topic.topicName(), error.get());
                continue;
            }
            Optional<ApiError> topicError = mirrorTopic(topic.topicName())
                .filter(t -> !t.mirrorName().equals(request.mirrorName()))
                .map(t -> new ApiError(Errors.TOPIC_ALREADY_IN_CLUSTER_MIRROR,
                    "Topic " + topic.topicName() + " is already in cluster mirror " + t.mirrorName()));
            if (topicError.isEmpty()) topicError = checkStateOffset(topic.topicName(), request.stateOffset());
            if (topicError.isEmpty()) {
                Optional<Map.Entry<Uuid, Integer>> existing = replication.topicIdAndPartitions(topic.topicName());
                if (existing.isEmpty()) {
                    List<ApiMessageAndVersion> topicRecords = new ArrayList<>();
                    ApiError created = replication.createMirrorTopic(context, topic.topicName(), topic.topicId(),
                        topic.numPartitions(), topicRecords);
                    if (created.isFailure()) {
                        topicError = Optional.of(created);
                    } else {
                        records.addAll(topicRecords);
                    }
                } else if (existing.get().getValue() != topic.numPartitions()) {
                    topicError = Optional.of(new ApiError(Errors.INVALID_PARTITIONS, "Topic " + topic.topicName() +
                        " has " + existing.get().getValue() + " partitions, but its source topic has " + topic.numPartitions()));
                }
            }
            if (topicError.isPresent()) {
                results.put(topic.topicName(), topicError.get());
            } else {
                records.add(stateRecord(topic.topicName(), request.mirrorName(), MirrorPartitionState.MIRRORING));
                results.put(topic.topicName(), ApiError.NONE);
            }
        }
        return ControllerResult.of(records, results);
    }

    /**
     * Changes the desired state of mirror topics, for the stop, pause and resume operations.
     *
     * @return the error of each topic, or NONE
     */
    ControllerResult<Map<String, ApiError>> changeDesiredState(String mirrorName, List<String> topics, long stateOffset,
                                                               MirrorPartitionState state, ConfigurationControlManager configs,
                                                               MetadataVersion metadataVersion) {
        Map<String, ApiError> results = new LinkedHashMap<>();
        List<ApiMessageAndVersion> records = new ArrayList<>();
        Optional<ApiError> error = checkSupported(metadataVersion);
        if (error.isEmpty() && !mirrorExists(configs, mirrorName)) {
            error = Optional.of(new ApiError(Errors.UNKNOWN_CLUSTER_MIRROR, "Cluster mirror " + mirrorName + " does not exist"));
        }
        for (String topic : topics) {
            Optional<ApiError> topicError = error;
            if (topicError.isEmpty() && mirrorTopic(topic).filter(t -> t.mirrorName().equals(mirrorName)).isEmpty()) {
                topicError = Optional.of(new ApiError(Errors.TOPIC_NOT_IN_CLUSTER_MIRROR,
                    "Topic " + topic + " is not in cluster mirror " + mirrorName));
            }
            if (topicError.isEmpty()) topicError = checkStateOffset(topic, stateOffset);
            if (topicError.isPresent()) {
                results.put(topic, topicError.get());
            } else {
                records.add(stateRecord(topic, mirrorName, state));
                results.put(topic, ApiError.NONE);
            }
        }
        return ControllerResult.of(records, results);
    }
}
