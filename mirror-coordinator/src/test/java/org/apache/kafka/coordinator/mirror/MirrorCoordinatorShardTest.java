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

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.message.WriteMirrorStatesRequestData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.utils.internals.LogContext;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRecord;
import org.apache.kafka.coordinator.common.runtime.CoordinatorResult;
import org.apache.kafka.server.mirror.MirrorPartition;
import org.apache.kafka.server.mirror.MirrorPartitionState;
import org.apache.kafka.timeline.SnapshotRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorCoordinatorShardTest {
    private static final MirrorPartition PARTITION = new MirrorPartition("m1", Uuid.randomUuid(), 0);

    private MirrorCoordinatorShard shard;
    private long offset;

    @BeforeEach
    void setUp() {
        shard = new MirrorCoordinatorShard.Builder().withSnapshotRegistry(new SnapshotRegistry(new LogContext())).build();
        offset = 0;
    }

    private MirrorCoordinatorShard.WriteResult write(MirrorPartitionState state, int leaderEpoch, int stateEpoch) {
        return write(new WriteMirrorStatesRequestData.PartitionData().setPartitionIndex(0).setState(state.value())
            .setLeaderEpoch(leaderEpoch).setStateEpoch(stateEpoch));
    }

    private MirrorCoordinatorShard.WriteResult write(WriteMirrorStatesRequestData.PartitionData data) {
        CoordinatorResult<MirrorCoordinatorShard.WriteResult, CoordinatorRecord> result = shard.write(PARTITION, data);
        result.records().forEach(record -> shard.replay(offset++, -1, (short) -1, record));
        return result.response();
    }

    private MirrorCoordinatorShard.PartitionState read() {
        return shard.read(PARTITION, Long.MAX_VALUE).orElseThrow();
    }

    @Test
    void testTransitionsIncrementStateEpoch() {
        assertTrue(shard.read(PARTITION, Long.MAX_VALUE).isEmpty());
        assertEquals(new MirrorCoordinatorShard.WriteResult(Errors.NONE, null, 1), write(MirrorPartitionState.LOG_ALIGNMENT, 5, -1));
        assertEquals(new MirrorCoordinatorShard.WriteResult(Errors.NONE, null, 2), write(MirrorPartitionState.MIRRORING, 5, 1));
        assertEquals(MirrorPartitionState.MIRRORING.value(), read().state().state());
        assertEquals(2, read().state().stateEpoch());
        assertEquals(5, read().state().leaderEpoch());
    }

    @Test
    void testStaleLeaderEpochIsFenced() {
        write(MirrorPartitionState.LOG_ALIGNMENT, 5, -1);
        assertEquals(Errors.FENCED_LEADER_EPOCH, write(MirrorPartitionState.MIRRORING, 4, 1).error());
        assertEquals(MirrorPartitionState.LOG_ALIGNMENT.value(), read().state().state());
        // A newer leader epoch is accepted.
        assertEquals(Errors.NONE, write(MirrorPartitionState.MIRRORING, 6, 1).error());
        assertEquals(6, read().state().leaderEpoch());
    }

    @Test
    void testStaleStateEpochIsFenced() {
        write(MirrorPartitionState.LOG_ALIGNMENT, 5, -1);
        write(MirrorPartitionState.MIRRORING, 5, 1);
        assertEquals(Errors.FENCED_STATE_EPOCH, write(MirrorPartitionState.STOPPING, 5, 1).error());
        assertEquals(MirrorPartitionState.MIRRORING.value(), read().state().state());
    }

    @Test
    void testFailedBypassesFencingAndKeepsPreviousState() {
        write(MirrorPartitionState.LOG_ALIGNMENT, 5, -1);
        write(MirrorPartitionState.MIRRORING, 5, 1);
        assertEquals(Errors.NONE, write(new WriteMirrorStatesRequestData.PartitionData().setPartitionIndex(0)
            .setState(MirrorPartitionState.FAILED.value()).setLeaderEpoch(1).setStateEpoch(0)
            .setErrorMessage("error").setRetryAttempt((short) 2)).error());
        assertEquals(MirrorPartitionState.FAILED.value(), read().state().state());
        assertEquals(MirrorPartitionState.MIRRORING.value(), read().state().previousState());
        assertEquals(2, read().state().retryAttempt());
        assertEquals("error", read().state().errorMessage());

        // A second failure keeps the state before the first one, and a non-retryable failure is marked with -1.
        write(new WriteMirrorStatesRequestData.PartitionData().setPartitionIndex(0)
            .setState(MirrorPartitionState.FAILED.value()).setLeaderEpoch(5).setStateEpoch(3).setNonRetryable(true));
        assertEquals(MirrorPartitionState.MIRRORING.value(), read().state().previousState());
        assertEquals(-1, read().state().retryAttempt());
    }

    @Test
    void testLastMirrorEpochIsStored() {
        write(MirrorPartitionState.LOG_ALIGNMENT, 5, -1);
        write(new WriteMirrorStatesRequestData.PartitionData().setPartitionIndex(0)
            .setState(MirrorPartitionState.STOPPED.value()).setLeaderEpoch(5).setStateEpoch(1)
            .setLastMirrorEpoch(3).setLastMirrorOffset(100L));
        assertEquals(3, read().lastMirror().orElseThrow().lastMirrorEpoch());
        assertEquals(100L, read().lastMirror().orElseThrow().lastMirrorOffset());
    }

    @Test
    void testDeleteRemovesState() {
        write(new WriteMirrorStatesRequestData.PartitionData().setPartitionIndex(0)
            .setState(MirrorPartitionState.STOPPED.value()).setLeaderEpoch(5).setStateEpoch(-1)
            .setLastMirrorEpoch(3).setLastMirrorOffset(100L));
        assertEquals(2, shard.delete(PARTITION).records().size());
        shard.delete(PARTITION).records().forEach(record -> shard.replay(offset++, -1, (short) -1, record));
        assertTrue(shard.read(PARTITION, Long.MAX_VALUE).isEmpty());
        assertEquals(0, shard.partitionsOf("m1", Long.MAX_VALUE).size());
    }
}
