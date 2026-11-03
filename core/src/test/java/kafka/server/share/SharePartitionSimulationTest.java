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
package kafka.server.share;

import kafka.server.share.SharePartition.SharePartitionState;
import kafka.server.share.SharePartitionManager.SharePartitionListener;

import org.apache.kafka.clients.consumer.AcknowledgeType;
import org.apache.kafka.clients.consumer.internals.ShareAcquireMode;
import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.message.ShareFetchResponseData.AcquiredRecords;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.internal.MemoryRecords;
import org.apache.kafka.server.util.MockTime;
import org.apache.kafka.coordinator.group.GroupConfigManager;
import org.apache.kafka.coordinator.group.modern.share.ShareGroupConfigProvider;
import org.apache.kafka.coordinator.share.PersisterStateBatchCombiner;
import org.apache.kafka.server.share.PartitionMetadataProvider;
import org.apache.kafka.server.share.acknowledge.ShareAcknowledgementBatch;
import org.apache.kafka.server.share.dlq.NoOpShareGroupDLQManager;
import org.apache.kafka.server.share.fetch.InFlightBatch;
import org.apache.kafka.server.share.fetch.InFlightState;
import org.apache.kafka.server.share.fetch.RecordState;
import org.apache.kafka.server.share.fetch.ShareAcquiredRecords;
import org.apache.kafka.server.share.metrics.SharePartitionMetrics;
import org.apache.kafka.server.share.persister.DeleteShareGroupStateParameters;
import org.apache.kafka.server.share.persister.DeleteShareGroupStateResult;
import org.apache.kafka.server.share.persister.InitializeShareGroupStateParameters;
import org.apache.kafka.server.share.persister.InitializeShareGroupStateResult;
import org.apache.kafka.server.share.persister.PartitionFactory;
import org.apache.kafka.server.share.persister.PartitionStateBatchData;
import org.apache.kafka.server.share.persister.Persister;
import org.apache.kafka.server.share.persister.PersisterStateBatch;
import org.apache.kafka.server.share.persister.ReadShareGroupStateParameters;
import org.apache.kafka.server.share.persister.ReadShareGroupStateResult;
import org.apache.kafka.server.share.persister.ReadShareGroupStateSummaryParameters;
import org.apache.kafka.server.share.persister.ReadShareGroupStateSummaryResult;
import org.apache.kafka.server.share.persister.TopicData;
import org.apache.kafka.server.share.persister.WriteShareGroupStateParameters;
import org.apache.kafka.server.share.persister.WriteShareGroupStateResult;
import org.apache.kafka.server.storage.log.FetchIsolation;
import org.apache.kafka.server.storage.log.FetchPartitionData;
import org.apache.kafka.server.util.timer.MockTimer;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.apache.kafka.server.share.fetch.ShareFetchTestUtils.memoryRecordsBuilder;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Randomized, seeded simulation of share-partition leaders against a simulated share coordinator.
 * The coordinator applies writes with the same merge rule and leader epoch fencing as
 * ShareCoordinatorShard, but the test decides when each write completes, whether it fails, whether
 * it was applied before failing, and whether it is applied again later (a retry). Leadership moves
 * by creating a new SharePartition with a higher leader epoch; the old leader may keep serving.
 * Everything runs on the test thread, so a seed reproduces a run exactly.
 */
public class SharePartitionSimulationTest {

    private static final String GROUP_ID = "sim-group";
    private static final TopicIdPartition TIP = new TopicIdPartition(Uuid.fromString("q0kHM8-6TiyMkb3C4h7tDw"), 0, "sim-topic");
    private static final int LOCK_TIMEOUT_MS = 100;
    private static final int MAX_DELIVERY_COUNT = 3;
    // log: offsets 0..14 in batches of 1 to 3 records
    private static final int[][] LOG_BATCHES = {{0, 2}, {2, 1}, {3, 3}, {6, 1}, {7, 2}, {9, 3}, {12, 1}, {13, 2}};
    private static final long LOG_END = 15;

    @Test
    public void singleLeaderWithFailingWrites() {
        runSeeds(500, 300, false, false);
    }

    @Test
    public void leaderFailover() {
        runSeeds(500, 300, true, false);
    }

    @Test
    public void leaderFailoverWithOldLeaderStillServing() {
        runSeeds(500, 300, true, true);
    }

    private void runSeeds(int seeds, int steps, boolean failover, boolean zombies) {
        Stats stats = new Stats();
        for (int seed = 0; seed < seeds; seed++) {
            new Simulation(seed, failover, zombies, stats).run(steps);
        }
        System.out.println("simulation failover=" + failover + " zombies=" + zombies + ": " + stats);
    }

    private static final class Stats {
        long acceptOkWithWrite;
        long acceptOkWithoutWrite;
        long redeliveredAfterAcceptWithoutWrite;
        long writesApplied;
        long writesFenced;
        long writesFailed;

        @Override
        public String toString() {
            return "acceptOkWithWrite=" + acceptOkWithWrite + " acceptOkWithoutWrite=" + acceptOkWithoutWrite
                + " redeliveredAfterAcceptWithoutWrite=" + redeliveredAfterAcceptWithoutWrite + " writesApplied=" + writesApplied
                + " writesFenced=" + writesFenced + " writesFailed=" + writesFailed;
        }
    }

    private static final class PendingWrite {
        final PartitionStateBatchData data;
        final CompletableFuture<WriteShareGroupStateResult> future;

        PendingWrite(PartitionStateBatchData data, CompletableFuture<WriteShareGroupStateResult> future) {
            this.data = data;
            this.future = future;
        }
    }

    /** The share coordinator for one share partition: merge, start offset and leader epoch fencing. */
    private static final class SimulatedCoordinator implements Persister {
        int leaderEpoch = 0;
        long startOffset = 0;
        List<PersisterStateBatch> batches = List.of();
        final List<PendingWrite> pending = new ArrayList<>();
        // offsets some write persisted as ACKNOWLEDGED or ARCHIVED
        final Set<Long> persistedTerminal = new HashSet<>();

        @Override
        public CompletableFuture<WriteShareGroupStateResult> writeState(WriteShareGroupStateParameters request) {
            PartitionStateBatchData data = request.groupTopicPartitionData().topicsData().get(0).partitions().get(0);
            CompletableFuture<WriteShareGroupStateResult> future = new CompletableFuture<>();
            pending.add(new PendingWrite(data, future));
            return future;
        }

        boolean fenced(PartitionStateBatchData data) {
            return data.leaderEpoch() < leaderEpoch;
        }

        void apply(PartitionStateBatchData data) {
            leaderEpoch = Math.max(leaderEpoch, data.leaderEpoch());
            startOffset = Math.max(startOffset, data.startOffset());
            batches = new PersisterStateBatchCombiner(batches, data.stateBatches(), startOffset).combineStateBatches();
            for (PersisterStateBatch b : data.stateBatches()) {
                if (b.deliveryState() == RecordState.ACKNOWLEDGED.id || b.deliveryState() == RecordState.ARCHIVED.id) {
                    for (long o = b.firstOffset(); o <= b.lastOffset(); o++) {
                        persistedTerminal.add(o);
                    }
                }
            }
        }

        static WriteShareGroupStateResult result(Errors error) {
            return new WriteShareGroupStateResult.Builder().setTopicsData(List.of(new TopicData<>(TIP.topicId(),
                List.of(PartitionFactory.newPartitionErrorData(TIP.partition(), error.code(), error.message()))))).build();
        }

        Optional<RecordState> persistedState(long offset) {
            for (PersisterStateBatch b : batches) {
                if (b.firstOffset() <= offset && offset <= b.lastOffset()) {
                    return Optional.of(RecordState.forId(b.deliveryState()));
                }
            }
            return Optional.empty();
        }

        @Override
        public CompletableFuture<ReadShareGroupStateResult> readState(ReadShareGroupStateParameters request) {
            // readStateAndMaybeUpdateLeaderEpoch: the read raises the recorded leader epoch
            leaderEpoch = Math.max(leaderEpoch, request.groupTopicPartitionData().topicsData().get(0).partitions().get(0).leaderEpoch());
            return CompletableFuture.completedFuture(new ReadShareGroupStateResult.Builder().setTopicsData(List.of(
                new TopicData<>(TIP.topicId(), List.of(PartitionFactory.newPartitionAllData(TIP.partition(), 0, startOffset,
                    Errors.NONE.code(), Errors.NONE.message(), batches))))).build());
        }

        @Override
        public CompletableFuture<InitializeShareGroupStateResult> initializeState(InitializeShareGroupStateParameters request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<DeleteShareGroupStateResult> deleteState(DeleteShareGroupStateParameters request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<ReadShareGroupStateSummaryResult> readSummary(ReadShareGroupStateSummaryParameters request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stop() {
        }
    }

    private static final class Leader {
        final SharePartition sharePartition;
        final int epoch;

        Leader(SharePartition sharePartition, int epoch) {
            this.sharePartition = sharePartition;
            this.epoch = epoch;
        }
    }

    private static final class Simulation {
        final int seed;
        final boolean failover;
        final boolean zombies;
        final Random rng;
        final MockTime time = new MockTime();
        final MockTimer timer = new MockTimer(time);
        final SimulatedCoordinator coordinator = new SimulatedCoordinator();
        final List<Leader> serving = new ArrayList<>();
        Leader loading;
        int newestEpoch = 0;
        final List<String> members = List.of("m1", "m2");
        // what each member holds: offset -> leader that handed it out
        final Map<String, Map<Long, Leader>> held = new HashMap<>();
        final Set<Long> acceptOk = new HashSet<>();
        // accepts answered OK without any write: offsets already below the leader's start offset
        final Set<Long> acceptOkWithoutWrite = new HashSet<>();
        final Set<Long> archivedInMemory = new HashSet<>();
        final List<String> trace = new ArrayList<>();
        int failures = 0;

        final Stats stats;

        Simulation(int seed, boolean failover, boolean zombies, Stats stats) {
            this.seed = seed;
            this.stats = stats;
            this.failover = failover;
            this.zombies = zombies;
            this.rng = new Random(seed);
            members.forEach(m -> held.put(m, new HashMap<>()));
            Leader first = newLeader(0);
            first.sharePartition.maybeInitialize();
            serving.add(first);
        }

        Leader newLeader(int epoch) {
            SharePartition sp = new SharePartition(GROUP_ID, TIP, epoch, 200, MAX_DELIVERY_COUNT, LOCK_TIMEOUT_MS, timer, time,
                coordinator, Mockito.mock(PartitionMetadataProvider.class), key -> { },
                new ShareGroupConfigProvider(Mockito.mock(GroupConfigManager.class)), SharePartitionState.EMPTY,
                Mockito.mock(SharePartitionListener.class), Mockito.mock(SharePartitionMetrics.class), () -> false,
                new NoOpShareGroupDLQManager());
            return new Leader(sp, epoch);
        }

        void run(int steps) {
            for (int step = 0; step < steps; step++) {
                int action = rng.nextInt(100);
                if (action < 25) {
                    acquire();
                } else if (action < 45) {
                    acknowledge();
                } else if (action < 75) {
                    completeWrite();
                } else if (action < 82) {
                    lockTimeout();
                } else if (action < 86) {
                    releaseSession();
                } else if (action < 92 && failover) {
                    moveLeadership();
                } else if (action < 96 && loading != null) {
                    finishLoading();
                } else if (action < 100 && zombies && serving.size() > 1) {
                    trace("retire leader " + serving.get(0).epoch);
                    serving.remove(0);
                }
                collectArchived();
                check();
            }
        }

        void trace(String s) {
            trace.add(s);
        }

        Leader pickServing() {
            return serving.isEmpty() ? null : serving.get(rng.nextInt(serving.size()));
        }

        void acquire() {
            Leader leader = pickServing();
            if (leader == null) {
                return;
            }
            String member = members.get(rng.nextInt(members.size()));
            SharePartition sp = leader.sharePartition;
            if (sp.partitionState() != SharePartitionState.ACTIVE) {
                return;
            }
            long fetchOffset = sp.nextFetchOffset();
            if (fetchOffset >= LOG_END) {
                return;
            }
            ShareAcquiredRecords acquired = sp.acquire(member, ShareAcquireMode.BATCH_OPTIMIZED, 500, Integer.MAX_VALUE,
                fetchOffset, fetchData(fetchOffset), FetchIsolation.HIGH_WATERMARK);
            for (AcquiredRecords r : acquired.acquiredRecords()) {
                trace("leader " + leader.epoch + " gives " + member + " " + r.firstOffset() + "-" + r.lastOffset()
                    + " (delivery " + r.deliveryCount() + ")");
                for (long o = r.firstOffset(); o <= r.lastOffset(); o++) {
                    held.get(member).put(o, leader);
                    // the newest leader must never hand out a record whose accept was reported as successful
                    if (leader.epoch == newestEpoch && acceptOk.contains(o)) {
                        fail("offset " + o + " redelivered by the newest leader after a successful accept");
                    }
                    if (acceptOkWithoutWrite.contains(o)) {
                        stats.redeliveredAfterAcceptWithoutWrite++;
                    }
                }
            }
        }

        void acknowledge() {
            String member = members.get(rng.nextInt(members.size()));
            Map<Long, Leader> mine = held.get(member);
            if (mine.isEmpty()) {
                return;
            }
            List<Long> offsets = new ArrayList<>(mine.keySet());
            offsets.sort(Long::compare);
            long first = offsets.get(rng.nextInt(offsets.size()));
            Leader leader = mine.get(first);
            long last = first;
            while (rng.nextBoolean() && mine.get(last + 1) == leader) {
                last++;
            }
            AcknowledgeType type = switch (rng.nextInt(4)) {
                case 0 -> AcknowledgeType.RELEASE;
                case 1 -> AcknowledgeType.REJECT;
                default -> AcknowledgeType.ACCEPT;
            };
            for (long o = first; o <= last; o++) {
                mine.remove(o);
            }
            long f = first;
            long l = last;
            trace(member + " " + type + " " + f + "-" + l + " to leader " + leader.epoch);
            int pendingBefore = coordinator.pending.size();
            CompletableFuture<Void> result = leader.sharePartition.acknowledge(member,
                List.of(new ShareAcknowledgementBatch(f, l, List.of(type.id))));
            boolean noWrite = result.isDone() && coordinator.pending.size() == pendingBefore;
            result.whenComplete((v, e) -> onAckAnswer(member, type, f, l, noWrite, e));
        }

        void onAckAnswer(String member, AcknowledgeType type, long first, long last, boolean noWrite, Throwable e) {
            trace("  answer to " + member + " " + type + " " + first + "-" + last + ": "
                + (e == null ? "OK" : e.getClass().getSimpleName()) + (noWrite && e == null ? " (no write)" : ""));
            if (e != null || type != AcknowledgeType.ACCEPT) {
                return;
            }
            for (long o = first; o <= last; o++) {
                if (noWrite) {
                    stats.acceptOkWithoutWrite++;
                    acceptOkWithoutWrite.add(o);
                } else if (!acceptOk.add(o)) {
                    fail("offset " + o + " reported as successfully accepted twice");
                } else {
                    stats.acceptOkWithWrite++;
                }
            }
        }

        void completeWrite() {
            if (coordinator.pending.isEmpty()) {
                return;
            }
            // writes for one share partition may complete in any order (retries with backoff)
            PendingWrite w = coordinator.pending.remove(rng.nextInt(coordinator.pending.size()));
            int outcome = rng.nextInt(10);
            if (coordinator.fenced(w.data)) {
                trace("coordinator fences write from leader " + w.data.leaderEpoch());
                stats.writesFenced++;
                w.future.complete(SimulatedCoordinator.result(Errors.FENCED_LEADER_EPOCH));
            } else if (outcome < 7 || failures >= 3) {
                trace("coordinator applies " + describe(w.data));
                coordinator.apply(w.data);
                stats.writesApplied++;
                w.future.complete(SimulatedCoordinator.result(Errors.NONE));
            } else if (outcome == 7) {
                failures++;
                stats.writesFailed++;
                trace("write fails, not applied: " + describe(w.data));
                w.future.complete(SimulatedCoordinator.result(Errors.UNKNOWN_SERVER_ERROR));
            } else if (outcome == 8) {
                failures++;
                trace("write applied but the answer is lost: " + describe(w.data));
                coordinator.apply(w.data);
                w.future.complete(SimulatedCoordinator.result(Errors.REQUEST_TIMED_OUT));
            } else {
                failures++;
                // applied, answer lost, the persister will send it again later
                trace("write applied, will be retried: " + describe(w.data));
                coordinator.apply(w.data);
                coordinator.pending.add(w);
            }
        }

        static String describe(PartitionStateBatchData data) {
            return "epoch " + data.leaderEpoch() + " start " + data.startOffset() + " " + data.stateBatches();
        }

        void lockTimeout() {
            trace("time passes " + (LOCK_TIMEOUT_MS + 1) + " ms");
            try {
                timer.advanceClock(LOCK_TIMEOUT_MS + 1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        void releaseSession() {
            String member = members.get(rng.nextInt(members.size()));
            Leader leader = pickServing();
            if (leader == null) {
                return;
            }
            trace(member + " closes its session with leader " + leader.epoch);
            held.get(member).values().removeIf(l -> l == leader);
            leader.sharePartition.releaseAcquiredRecords(member);
        }

        void moveLeadership() {
            if (loading != null || newestEpoch >= 3) {
                return;
            }
            newestEpoch++;
            trace("leadership moves to epoch " + newestEpoch);
            if (!zombies) {
                serving.clear();
            }
            loading = newLeader(newestEpoch);
            if (rng.nextBoolean()) {
                finishLoading();
            }
        }

        void finishLoading() {
            trace("leader " + loading.epoch + " reads the persisted state");
            loading.sharePartition.maybeInitialize();
            serving.add(loading);
            loading = null;
        }

        void collectArchived() {
            for (Leader leader : serving) {
                for (InFlightBatch batch : leader.sharePartition.cachedState().values()) {
                    if (batch.offsetState() == null) {
                        if (batch.batchState() == RecordState.ARCHIVED) {
                            for (long o = batch.firstOffset(); o <= batch.lastOffset(); o++) {
                                archivedInMemory.add(o);
                            }
                        }
                    } else {
                        for (Map.Entry<Long, InFlightState> e : batch.offsetState().entrySet()) {
                            if (e.getValue().state() == RecordState.ARCHIVED) {
                                archivedInMemory.add(e.getKey());
                            }
                        }
                    }
                }
            }
        }

        void check() {
            for (long o : acceptOk) {
                if (o >= coordinator.startOffset) {
                    Optional<RecordState> st = coordinator.persistedState(o);
                    assertTrue(st.isPresent() && (st.get() == RecordState.ACKNOWLEDGED || st.get() == RecordState.ARCHIVED),
                        message("offset " + o + " was accepted but is persisted as " + st));
                }
            }
            for (long o = 0; o < coordinator.startOffset; o++) {
                assertTrue(coordinator.persistedTerminal.contains(o) || archivedInMemory.contains(o),
                    message("persisted start offset " + coordinator.startOffset + " skips offset " + o
                        + " that was never acknowledged or archived"));
            }
        }

        String message(String what) {
            int from = Math.max(0, trace.size() - 40);
            return "seed " + seed + ": " + what + "\n  " + String.join("\n  ", trace.subList(from, trace.size()));
        }

        void fail(String what) {
            org.junit.jupiter.api.Assertions.fail(message(what));
        }

        MemoryRecords records(long fetchOffset) {
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            for (int[] b : LOG_BATCHES) {
                if (b[0] + b[1] - 1 >= fetchOffset) {
                    memoryRecordsBuilder(buffer, b[0], b[1]).close();
                }
            }
            buffer.flip();
            return MemoryRecords.readableRecords(buffer);
        }

        FetchPartitionData fetchData(long fetchOffset) {
            return new FetchPartitionData(Errors.NONE, LOG_END, 0, records(fetchOffset), Optional.empty(),
                OptionalLong.empty(), Optional.empty(), OptionalInt.empty(), false);
        }
    }
}
