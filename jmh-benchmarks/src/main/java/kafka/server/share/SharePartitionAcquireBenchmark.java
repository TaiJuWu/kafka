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

import org.apache.kafka.clients.consumer.internals.ShareAcquireMode;
import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.message.FetchResponseData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.internal.ControlRecordType;
import org.apache.kafka.common.record.internal.EndTransactionMarker;
import org.apache.kafka.common.record.internal.FileRecords;
import org.apache.kafka.common.record.internal.MemoryRecords;
import org.apache.kafka.common.record.internal.RecordBatch;
import org.apache.kafka.common.record.internal.SimpleRecord;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.coordinator.group.GroupConfigManager;
import org.apache.kafka.coordinator.group.modern.share.ShareGroupConfigProvider;
import org.apache.kafka.server.share.PartitionMetadataProvider;
import org.apache.kafka.server.share.dlq.NoOpShareGroupDLQManager;
import org.apache.kafka.server.share.fetch.ShareAcquiredRecords;
import org.apache.kafka.server.share.metrics.SharePartitionMetrics;
import org.apache.kafka.server.share.persister.NoOpStatePersister;
import org.apache.kafka.server.storage.log.FetchIsolation;
import org.apache.kafka.server.storage.log.FetchPartitionData;
import org.apache.kafka.server.util.timer.MockTimer;

import org.mockito.Mockito;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * Acquires records read from a log file, which starts with an aborted transaction followed by single record batches.
 */
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SharePartitionAcquireBenchmark {

    @Param({"1000", "10000"})
    private int numBatches;

    @Param({"10", "500"})
    private int maxFetchRecords;

    @Param({"TXN_COMMITTED", "HIGH_WATERMARK"})
    private FetchIsolation isolation;

    private final TopicIdPartition topicIdPartition = new TopicIdPartition(Uuid.randomUuid(), 0, "benchmark");
    private File file;
    private FileRecords records;
    private FetchPartitionData fetchPartitionData;
    private ShareGroupConfigProvider configProvider;
    private SharePartitionMetrics metrics;
    private SharePartition sharePartition;

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        file = Files.createTempFile("share-acquire", ".log").toFile();
        records = FileRecords.open(file);
        long producerId = 1;
        records.append(MemoryRecords.withTransactionalRecords(0, Compression.NONE, producerId, (short) 0, 0,
            RecordBatch.NO_PARTITION_LEADER_EPOCH, new SimpleRecord("value".getBytes())));
        records.append(MemoryRecords.withEndTransactionMarker(1, 0, RecordBatch.NO_PARTITION_LEADER_EPOCH, producerId,
            (short) 0, new EndTransactionMarker(ControlRecordType.ABORT, 0)));
        for (long offset = 2; offset < numBatches; offset++) {
            records.append(MemoryRecords.withRecords(offset, Compression.NONE, new SimpleRecord("value".getBytes())));
        }
        records.flush();
        List<FetchResponseData.AbortedTransaction> abortedTransactions = List.of(
            new FetchResponseData.AbortedTransaction().setFirstOffset(0).setProducerId(producerId));
        fetchPartitionData = new FetchPartitionData(Errors.NONE, numBatches, 0, records, Optional.empty(),
            OptionalLong.empty(), Optional.of(abortedTransactions), OptionalInt.empty(), false);
        configProvider = new ShareGroupConfigProvider(Mockito.mock(GroupConfigManager.class));
        metrics = new SharePartitionMetrics("group", topicIdPartition.topic(), topicIdPartition.partition());
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        sharePartition = new SharePartition("group", topicIdPartition, 0, Integer.MAX_VALUE, 5, 30000,
            new MockTimer(), Time.SYSTEM, new NoOpStatePersister(), Mockito.mock(PartitionMetadataProvider.class),
            key -> { }, configProvider, SharePartition.SharePartitionState.ACTIVE,
            Mockito.mock(SharePartitionManager.SharePartitionListener.class), metrics, () -> false,
            new NoOpShareGroupDLQManager());
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        metrics.close();
        records.close();
        Files.deleteIfExists(file.toPath());
    }

    @Benchmark
    public ShareAcquiredRecords acquire() {
        return sharePartition.acquire("member", ShareAcquireMode.BATCH_OPTIMIZED, maxFetchRecords, maxFetchRecords, 0,
            fetchPartitionData, isolation);
    }
}
