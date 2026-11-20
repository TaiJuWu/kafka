/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.server.mirror

import java.util.Optional

import kafka.server.{AbstractFetcherThread, FailedPartitions, ReplicaManager, TierStateMachine}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.requests.FetchResponse
import org.apache.kafka.server.LeaderEndPoint
import org.apache.kafka.server.common.OffsetAndEpoch
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.record.internal.{MemoryRecords, RecordBatch}
import org.apache.kafka.storage.internals.log.{LogAppendInfo, LogStartOffsetIncrementReason, MirrorLeaderEpochExceededException}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/**
 * Asks the controller to bump the leader epoch of a mirror partition to at least the given value.
 */
trait MirrorLeaderEpochBumper {
  def requestLeaderEpochBump(topicPartition: TopicPartition, minLeaderEpoch: Int): Unit
}

object MirrorFetcherThread {
  /**
   * The leader epoch of a mirror partition is bumped to the source epoch plus this increment, so the source
   * partition can elect several leaders before the next bump is needed.
   */
  val LeaderEpochBumpIncrement = 10

  /**
   * The leader epoch of a mirror partition is bumped ahead of time when the source epoch gets within this distance.
   */
  val LeaderEpochBumpThreshold = 3
}

/**
 * Copies the record batches of source partitions to the destination partitions, of which this broker is the leader.
 * The batches are appended as they are, so the destination partitions have the same offsets, partition leader
 * epochs and timestamps as the source partitions.
 */
class MirrorFetcherThread(name: String,
                          leader: LeaderEndPoint,
                          fetchBackOffMs: Int,
                          failedPartitions: FailedPartitions,
                          replicaMgr: ReplicaManager,
                          epochBumper: MirrorLeaderEpochBumper,
                          logPrefix: String)
  extends AbstractFetcherThread(name = name,
                                clientId = name,
                                leader = leader,
                                failedPartitions,
                                fetchTierStateMachine = new TierStateMachine(leader, replicaMgr, false),
                                fetchBackOffMs = fetchBackOffMs,
                                isInterruptible = false,
                                replicaMgr.brokerTopicStats) {

  this.logIdent = logPrefix

  private val appendedPartitions = mutable.Buffer[TopicPartition]()

  override protected def latestEpoch(topicPartition: TopicPartition): Optional[Integer] =
    replicaMgr.localLogOrException(topicPartition).latestEpoch

  override protected def logStartOffset(topicPartition: TopicPartition): Long =
    replicaMgr.localLogOrException(topicPartition).logStartOffset

  override protected def logEndOffset(topicPartition: TopicPartition): Long =
    replicaMgr.localLogOrException(topicPartition).logEndOffset

  override protected def endOffsetForEpoch(topicPartition: TopicPartition, epoch: Int): Optional[OffsetAndEpoch] =
    replicaMgr.localLogOrException(topicPartition).endOffsetForEpoch(epoch)

  // Tiered storage is not supported by cluster mirroring.
  override protected[server] def shouldFetchFromLastTieredOffset(topicPartition: TopicPartition, leaderEndOffset: Long,
                                                                 replicaEndOffset: Long): Boolean = false

  override def initiateShutdown(): Boolean = {
    val justShutdown = super.initiateShutdown()
    if (justShutdown) {
      try {
        leader.initiateClose()
      } catch {
        case t: Throwable =>
          error(s"Failed to initiate shutdown of $leader after initiating mirror fetcher thread shutdown", t)
      }
    }
    justShutdown
  }

  override def awaitShutdown(): Unit = {
    super.awaitShutdown()
    try {
      leader.close()
    } catch {
      case t: Throwable =>
        error(s"Failed to close $leader after shutting down mirror fetcher thread", t)
    }
  }

  override def doWork(): Unit = {
    super.doWork()
    completeDelayedFetchRequests()
  }

  override def processPartitionData(topicPartition: TopicPartition,
                                    fetchOffset: Long,
                                    partitionLeaderEpoch: Int,
                                    partitionData: FetchData): Option[LogAppendInfo] = {
    val partition = replicaMgr.getPartitionOrException(topicPartition)
    val log = partition.localLogOrException
    val records = toMemoryRecords(FetchResponse.recordsOrFail(partitionData))

    if (fetchOffset != log.logEndOffset)
      throw new IllegalStateException(s"Offset mismatch for mirror partition $topicPartition: fetched offset = " +
        s"$fetchOffset, log end offset = ${log.logEndOffset}.")

    // The leader epoch of the destination partition must stay at least the leader epoch of the source partition
    // (DLE >= SLE). A bump is requested ahead of time when the source epoch gets close, and the partition stops on a
    // batch with a higher epoch until the bump is done (EPOCH_FENCING).
    val sourceEpoch = maxPartitionLeaderEpoch(records)
    val leaderEpoch = partition.getLeaderEpoch
    if (sourceEpoch >= leaderEpoch - MirrorFetcherThread.LeaderEpochBumpThreshold)
      epochBumper.requestLeaderEpochBump(topicPartition, sourceEpoch + MirrorFetcherThread.LeaderEpochBumpIncrement)
    if (sourceEpoch > leaderEpoch)
      throw new MirrorLeaderEpochExceededException(s"The source leader epoch $sourceEpoch of partition $topicPartition " +
        s"is higher than the destination leader epoch $leaderEpoch")

    val (logAppendInfo, _) = partition.appendRecordsToMirrorLeader(records)

    // The records below the log start offset of the source partition were deleted there, so they are deleted here too.
    log.maybeIncrementLogStartOffset(partitionData.logStartOffset, LogStartOffsetIncrementReason.LeaderOffsetIncremented)

    if (records.sizeInBytes > 0)
      appendedPartitions += topicPartition
    brokerTopicStats.topicStats(topicPartition.topic).bytesInRate.mark(records.sizeInBytes)
    brokerTopicStats.allTopicsStats.bytesInRate.mark(records.sizeInBytes)
    Some(logAppendInfo)
  }

  // The followers of the destination partitions, and the consumers if the high watermark moved, may wait for the
  // appended records.
  private def completeDelayedFetchRequests(): Unit = {
    if (appendedPartitions.nonEmpty) {
      replicaMgr.completeDelayedFetchRequests(appendedPartitions.toSeq)
      appendedPartitions.clear()
    }
  }

  private def maxPartitionLeaderEpoch(records: MemoryRecords): Int = {
    var epoch = RecordBatch.NO_PARTITION_LEADER_EPOCH
    records.batches.asScala.foreach(batch => epoch = math.max(epoch, batch.partitionLeaderEpoch))
    epoch
  }

  /**
   * Truncates on a diverging epoch returned by the source. As only committed records are fetched, the source log
   * only diverges on an unclean leader election, which is followed only if mirror.unclean.leader.election.enable is
   * set for the topic. Otherwise the mirroring of the partition fails.
   */
  override def truncate(topicPartition: TopicPartition, offset: Long): Unit = {
    val partition = replicaMgr.getPartitionOrException(topicPartition)
    val log = partition.localLogOrException
    if (offset < log.logEndOffset && !log.config.mirrorUncleanLeaderElectionEnable)
      throw new KafkaException(s"The source partition of $topicPartition diverged at offset $offset, which " +
        "means that it had an unclean leader election, and mirror.unclean.leader.election.enable is not set")
    if (offset < log.logEndOffset)
      warn(s"Truncating mirror partition $topicPartition from ${log.logEndOffset} to $offset after an unclean " +
        "leader election of the source partition")
    partition.truncateTo(offset, isFuture = false)
  }

  override protected def truncateFullyAndStartAt(topicPartition: TopicPartition, offset: Long): Unit =
    replicaMgr.getPartitionOrException(topicPartition).truncateFullyAndStartAt(offset, isFuture = false)
}
