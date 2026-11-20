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
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetrics;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetricsShard;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRuntimeMetricsImpl;
import org.apache.kafka.timeline.SnapshotRegistry;

/**
 * The metrics of the cluster mirror coordinator. The runtime metrics are in the group
 * cluster-mirror-coordinator-metrics (KIP-1279); the shards have no metrics of their own yet.
 */
public final class MirrorCoordinatorMetrics extends CoordinatorMetrics {
    public static final String METRICS_GROUP = "cluster-mirror-coordinator-metrics";

    public static CoordinatorRuntimeMetricsImpl runtimeMetrics(Metrics metrics) {
        return new CoordinatorRuntimeMetricsImpl(metrics, METRICS_GROUP, false);
    }

    @Override
    public CoordinatorMetricsShard newMetricsShard(SnapshotRegistry snapshotRegistry, TopicPartition tp) {
        return new CoordinatorMetricsShard() {
            @Override
            public void record(String sensorName) { }

            @Override
            public void record(String sensorName, double val) { }

            @Override
            public TopicPartition topicPartition() {
                return tp;
            }

            @Override
            public void commitUpTo(long offset) { }
        };
    }

    @Override
    public void activateMetricsShard(CoordinatorMetricsShard shard) { }

    @Override
    public void deactivateMetricsShard(CoordinatorMetricsShard shard) { }

    @Override
    public void onUpdateLastCommittedOffset(TopicPartition tp, long offset) { }
}
