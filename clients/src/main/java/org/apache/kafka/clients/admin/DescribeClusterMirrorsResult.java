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

import org.apache.kafka.common.KafkaFuture;

import java.util.Map;

/**
 * The result of {@link Admin#describeClusterMirrors} (KIP-1279).
 */
public class DescribeClusterMirrorsResult {
    private final KafkaFuture<Map<String, ClusterMirrorDescription>> future;

    DescribeClusterMirrorsResult(KafkaFuture<Map<String, ClusterMirrorDescription>> future) {
        this.future = future;
    }

    public KafkaFuture<Map<String, ClusterMirrorDescription>> allDescriptions() {
        return future;
    }

    /**
     * @return the last mirror epoch of each partition, by topic, of the mirrors whose source cluster is the cluster
     *         given in {@link DescribeClusterMirrorsOptions#clusterId}, which a failback truncates to
     */
    public KafkaFuture<Map<String, Map<Integer, Integer>>> lookupEpochs() {
        return future.thenApply(descriptions -> {
            Map<String, Map<Integer, Integer>> epochs = new java.util.HashMap<>();
            descriptions.values().forEach(description -> description.leaderStates().forEach((topic, states) ->
                states.forEach(state -> {
                    if (state.lastMirrorEpoch() >= 0) {
                        epochs.computeIfAbsent(topic, k -> new java.util.HashMap<>())
                            .merge(state.topicPartition().partition(), state.lastMirrorEpoch(), Math::max);
                    }
                })));
            return epochs;
        });
    }
}
