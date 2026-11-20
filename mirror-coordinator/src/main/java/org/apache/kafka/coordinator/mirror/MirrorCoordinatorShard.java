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
import org.apache.kafka.common.message.WriteMirrorStatesRequestData;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.utils.internals.LogContext;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetrics;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRecord;
import org.apache.kafka.coordinator.common.runtime.CoordinatorResult;
import org.apache.kafka.coordinator.common.runtime.CoordinatorShard;
import org.apache.kafka.coordinator.common.runtime.CoordinatorShardBuilder;
import org.apache.kafka.coordinator.common.runtime.CoordinatorExecutor;
import org.apache.kafka.coordinator.common.runtime.CoordinatorTimer;
import org.apache.kafka.coordinator.mirror.generated.CoordinatorRecordType;
import org.apache.kafka.coordinator.mirror.generated.LastMirrorEpochsKey;
import org.apache.kafka.coordinator.mirror.generated.LastMirrorEpochsValue;
import org.apache.kafka.coordinator.mirror.generated.MirrorPartitionStateKey;
import org.apache.kafka.coordinator.mirror.generated.MirrorPartitionStateValue;
import org.apache.kafka.server.common.ApiMessageAndVersion;
import org.apache.kafka.server.mirror.MirrorPartition;
import org.apache.kafka.server.mirror.MirrorPartitionState;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.timeline.SnapshotRegistry;
import org.apache.kafka.timeline.TimelineHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A shard of the cluster mirror coordinator: the states of the mirror partitions which hash to one partition of the
 * __mirror_state topic, rebuilt by replaying it (KIP-1279).
 */
public class MirrorCoordinatorShard implements CoordinatorShard<CoordinatorRecord> {

    /**
     * The state of a mirror partition, as read by the brokers.
     */
    public record PartitionState(MirrorPartitionStateValue state, Optional<LastMirrorEpochsValue> lastMirror) { }

    /**
     * The result of a state transition: an error, or the new state epoch.
     */
    public record WriteResult(Errors error, String message, int stateEpoch) { }

    private final TimelineHashMap<MirrorPartition, MirrorPartitionStateValue> states;
    private final TimelineHashMap<MirrorPartition, LastMirrorEpochsValue> lastMirrors;

    MirrorCoordinatorShard(SnapshotRegistry snapshotRegistry) {
        this.states = new TimelineHashMap<>(snapshotRegistry, 0);
        this.lastMirrors = new TimelineHashMap<>(snapshotRegistry, 0);
    }

    @Override
    public void replay(long offset, long producerId, short producerEpoch, CoordinatorRecord record) {
        ApiMessage key = record.key();
        ApiMessage value = record.value() == null ? null : record.value().message();
        switch (CoordinatorRecordType.fromId(key.apiKey())) {
            case MIRROR_PARTITION_STATE -> {
                MirrorPartitionStateKey stateKey = (MirrorPartitionStateKey) key;
                MirrorPartition partition = new MirrorPartition(stateKey.mirrorName(), stateKey.topicId(), stateKey.partition());
                if (value == null) {
                    states.remove(partition);
                } else {
                    states.put(partition, (MirrorPartitionStateValue) value);
                }
            }
            case LAST_MIRROR_EPOCHS -> {
                LastMirrorEpochsKey lastMirrorKey = (LastMirrorEpochsKey) key;
                MirrorPartition partition = new MirrorPartition(lastMirrorKey.mirrorName(), lastMirrorKey.topicId(), lastMirrorKey.partition());
                if (value == null) {
                    lastMirrors.remove(partition);
                } else {
                    lastMirrors.put(partition, (LastMirrorEpochsValue) value);
                }
            }
            default -> { }
        }
    }

    /**
     * @return the state of the mirror partition at the given committed offset, if any
     */
    public Optional<PartitionState> read(MirrorPartition partition, long committedOffset) {
        MirrorPartitionStateValue state = states.get(partition, committedOffset);
        if (state == null) {
            return Optional.empty();
        }
        return Optional.of(new PartitionState(state, Optional.ofNullable(lastMirrors.get(partition, committedOffset))));
    }

    /**
     * Applies a state transition of a mirror partition. The leader epoch is checked first, so that a broker which no
     * longer leads the partition cannot overwrite a decision of the current leader, and then the state epoch, so
     * that a broker which acted on a stale state cannot overwrite a newer one. A transition to FAILED bypasses both
     * checks, so the error handling is never blocked. Every transition increments the state epoch.
     */
    public CoordinatorResult<WriteResult, CoordinatorRecord> write(MirrorPartition partition,
                                                                   WriteMirrorStatesRequestData.PartitionData data) {
        MirrorPartitionStateValue current = states.get(partition);
        boolean toFailed = data.state() == MirrorPartitionState.FAILED.value();
        if (current != null && !toFailed) {
            Optional<WriteResult> fenced = fence(current, data);
            if (fenced.isPresent()) {
                return new CoordinatorResult<>(List.of(), fenced.get());
            }
        }
        int stateEpoch = (current == null ? 0 : current.stateEpoch()) + 1;
        int leaderEpoch = Math.max(current == null ? -1 : current.leaderEpoch(), data.leaderEpoch());

        List<CoordinatorRecord> records = new ArrayList<>();
        records.add(stateRecord(partition, data, current, leaderEpoch, stateEpoch, toFailed));
        if (data.lastMirrorEpoch() >= 0) {
            records.add(lastMirrorRecord(partition, data, leaderEpoch, stateEpoch));
        }
        return new CoordinatorResult<>(records, new WriteResult(Errors.NONE, null, stateEpoch));
    }

    private static Optional<WriteResult> fence(MirrorPartitionStateValue current, WriteMirrorStatesRequestData.PartitionData data) {
        if (data.leaderEpoch() < current.leaderEpoch()) {
            return Optional.of(new WriteResult(Errors.FENCED_LEADER_EPOCH,
                "The leader epoch " + data.leaderEpoch() + " is older than " + current.leaderEpoch(), current.stateEpoch()));
        }
        if (data.stateEpoch() < current.stateEpoch()) {
            return Optional.of(new WriteResult(Errors.FENCED_STATE_EPOCH,
                "The state epoch " + data.stateEpoch() + " is older than " + current.stateEpoch(), current.stateEpoch()));
        }
        return Optional.empty();
    }

    /**
     * @return the state before entering FAILED: the current state, or the state before the current FAILED state
     */
    private static byte previousState(MirrorPartitionStateValue current) {
        if (current == null) {
            return MirrorPartitionState.UNKNOWN.value();
        }
        return current.state() == MirrorPartitionState.FAILED.value() ? current.previousState() : current.state();
    }

    private static CoordinatorRecord stateRecord(MirrorPartition partition, WriteMirrorStatesRequestData.PartitionData data,
                                                 MirrorPartitionStateValue current, int leaderEpoch, int stateEpoch,
                                                 boolean toFailed) {
        MirrorPartitionStateValue value = new MirrorPartitionStateValue()
            .setState(data.state())
            .setLeaderEpoch(leaderEpoch)
            .setStateEpoch(stateEpoch);
        if (toFailed) {
            value.setErrorMessage(data.errorMessage())
                .setRetryAttempt(data.nonRetryable() ? (short) -1 : (short) Math.max(data.retryAttempt(), 0))
                .setPreviousState(previousState(current));
        }
        return CoordinatorRecord.record(
            new MirrorPartitionStateKey().setMirrorName(partition.mirrorName()).setTopicId(partition.topicId()).setPartition(partition.partition()),
            new ApiMessageAndVersion(value, (short) 0));
    }

    private static CoordinatorRecord lastMirrorRecord(MirrorPartition partition, WriteMirrorStatesRequestData.PartitionData data,
                                                      int leaderEpoch, int stateEpoch) {
        return CoordinatorRecord.record(
            new LastMirrorEpochsKey().setMirrorName(partition.mirrorName()).setTopicId(partition.topicId()).setPartition(partition.partition()),
            new ApiMessageAndVersion(new LastMirrorEpochsValue()
                .setLastMirrorEpoch(data.lastMirrorEpoch())
                .setLastMirrorOffset(data.lastMirrorOffset())
                .setLeaderEpoch(leaderEpoch)
                .setStateEpoch(stateEpoch), (short) 0));
    }

    /**
     * @return the records which remove the state of a mirror partition, for instance when its cluster mirror is deleted
     */
    public CoordinatorResult<Void, CoordinatorRecord> delete(MirrorPartition partition) {
        List<CoordinatorRecord> records = new ArrayList<>();
        if (states.containsKey(partition)) {
            records.add(CoordinatorRecord.tombstone(new MirrorPartitionStateKey()
                .setMirrorName(partition.mirrorName()).setTopicId(partition.topicId()).setPartition(partition.partition())));
        }
        if (lastMirrors.containsKey(partition)) {
            records.add(CoordinatorRecord.tombstone(new LastMirrorEpochsKey()
                .setMirrorName(partition.mirrorName()).setTopicId(partition.topicId()).setPartition(partition.partition())));
        }
        return new CoordinatorResult<>(records, null);
    }

    /**
     * @return the mirror partitions of the given cluster mirror which have a state in this shard
     */
    public List<MirrorPartition> partitionsOf(String mirrorName, long committedOffset) {
        List<MirrorPartition> partitions = new ArrayList<>();
        states.keySet(committedOffset).forEach(partition -> {
            if (partition.mirrorName().equals(mirrorName)) partitions.add(partition);
        });
        return partitions;
    }

    public static class Builder implements CoordinatorShardBuilder<MirrorCoordinatorShard, CoordinatorRecord> {
        private SnapshotRegistry snapshotRegistry;

        @Override
        public Builder withSnapshotRegistry(SnapshotRegistry snapshotRegistry) {
            this.snapshotRegistry = snapshotRegistry;
            return this;
        }

        @Override
        public Builder withLogContext(LogContext logContext) {
            return this;
        }

        @Override
        public Builder withTime(Time time) {
            return this;
        }

        @Override
        public Builder withTimer(CoordinatorTimer<CoordinatorRecord> timer) {
            return this;
        }

        @Override
        public Builder withExecutor(CoordinatorExecutor<CoordinatorRecord> executor) {
            return this;
        }

        @Override
        public Builder withCoordinatorMetrics(CoordinatorMetrics coordinatorMetrics) {
            return this;
        }

        @Override
        public Builder withTopicPartition(TopicPartition topicPartition) {
            return this;
        }

        @Override
        public MirrorCoordinatorShard build() {
            if (snapshotRegistry == null) {
                throw new IllegalArgumentException("SnapshotRegistry must be set.");
            }
            return new MirrorCoordinatorShard(snapshotRegistry);
        }
    }
}
