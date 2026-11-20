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

import java.util.{Collections, Optional}

import kafka.server.{BlockingSend, ReplicaManager}
import kafka.utils.Logging
import org.apache.kafka.clients.FetchSessionHandler
import org.apache.kafka.common.{IsolationLevel, TopicPartition, Uuid}
import org.apache.kafka.common.errors.KafkaStorageException
import org.apache.kafka.common.message.{FetchResponseData, OffsetForLeaderEpochRequestData}
import org.apache.kafka.common.message.ListOffsetsRequestData.{ListOffsetsPartition, ListOffsetsTopic}
import org.apache.kafka.common.message.OffsetForLeaderEpochResponseData.EpochEndOffset
import org.apache.kafka.common.protocol.{ApiKeys, Errors}
import org.apache.kafka.common.record.internal.RecordBatch
import org.apache.kafka.common.requests.{FetchRequest, FetchResponse, ListOffsetsRequest, ListOffsetsResponse}
import org.apache.kafka.coordinator.mirror.ClusterMirrorConfig
import org.apache.kafka.server.{LeaderEndPoint, PartitionFetchState, ReplicaFetch, ResultWithPartitions}
import org.apache.kafka.server.common.OffsetAndEpoch
import org.apache.kafka.server.network.BrokerEndPoint

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/**
 * The leader end point of a mirror fetcher: a broker of the source cluster, which the destination leader fetches
 * from as a consumer rather than as a follower, so the source broker does not track it as a replica. The fetch only
 * returns the records up to the high watermark of the source partition.
 *
 * The fetch requests carry the last fetched epoch, which is an epoch of the source partition, so the source broker
 * returns the diverging epoch if its log diverged, which only happens on an unclean leader election, as only
 * committed records are fetched. The current leader epoch of the source partition is not sent.
 */
class MirrorLeaderEndPoint(logPrefix: String,
                           blockingSender: BlockingSend,
                           private[mirror] val fetchSessionHandler: FetchSessionHandler,
                           mirrorConfig: ClusterMirrorConfig,
                           replicaManager: ReplicaManager) extends LeaderEndPoint with Logging {

  this.logIdent = logPrefix

  override def initiateClose(): Unit = blockingSender.initiateClose()

  override def close(): Unit = blockingSender.close()

  override def brokerEndPoint(): BrokerEndPoint = blockingSender.brokerEndPoint()

  override def fetch(fetchRequest: FetchRequest.Builder): java.util.Map[TopicPartition, FetchResponseData.PartitionData] = {
    val clientResponse = try {
      blockingSender.sendRequest(fetchRequest)
    } catch {
      case t: Throwable =>
        fetchSessionHandler.handleError(t)
        throw t
    }
    val fetchResponse = clientResponse.responseBody.asInstanceOf[FetchResponse]
    if (!fetchSessionHandler.handleResponse(fetchResponse, clientResponse.requestHeader().apiVersion())) {
      if (fetchResponse.error == Errors.FETCH_SESSION_TOPIC_ID_ERROR)
        throw Errors.forCode(fetchResponse.error().code()).exception()
      else
        java.util.Map.of()
    } else {
      fetchResponse.responseData(fetchSessionHandler.sessionTopicNames, clientResponse.requestHeader().apiVersion())
    }
  }

  override def fetchEarliestOffset(topicPartition: TopicPartition, currentLeaderEpoch: Int): OffsetAndEpoch =
    fetchOffset(topicPartition, ListOffsetsRequest.EARLIEST_TIMESTAMP)

  override def fetchLatestOffset(topicPartition: TopicPartition, currentLeaderEpoch: Int): OffsetAndEpoch =
    fetchOffset(topicPartition, ListOffsetsRequest.LATEST_TIMESTAMP)

  // Tiered storage is not supported by cluster mirroring.
  override def fetchEarliestLocalOffset(topicPartition: TopicPartition, currentLeaderEpoch: Int): OffsetAndEpoch =
    throw new UnsupportedOperationException("Tiered storage is not supported by cluster mirroring")

  override def fetchEarliestPendingUploadOffset(topicPartition: TopicPartition, currentLeaderEpoch: Int): OffsetAndEpoch =
    throw new UnsupportedOperationException("Tiered storage is not supported by cluster mirroring")

  /**
   * The offsets are those of the source partition, which the destination partition copies. The current leader
   * epoch of the destination partition is not sent, as it does not relate to the epochs of the source partition.
   */
  private def fetchOffset(topicPartition: TopicPartition, timestamp: Long): OffsetAndEpoch = {
    val topic = new ListOffsetsTopic()
      .setName(topicPartition.topic)
      .setPartitions(Collections.singletonList(
        new ListOffsetsPartition()
          .setPartitionIndex(topicPartition.partition)
          .setCurrentLeaderEpoch(RecordBatch.NO_PARTITION_LEADER_EPOCH)
          .setTimestamp(timestamp)))
    val requestBuilder = ListOffsetsRequest.Builder.forConsumer(false, IsolationLevel.READ_UNCOMMITTED)
      .setTargetTimes(Collections.singletonList(topic))
    val response = blockingSender.sendRequest(requestBuilder).responseBody.asInstanceOf[ListOffsetsResponse]
    val responsePartition = response.topics.asScala.find(_.name == topicPartition.topic).get
      .partitions.asScala.find(_.partitionIndex == topicPartition.partition).get
    Errors.forCode(responsePartition.errorCode) match {
      case Errors.NONE => new OffsetAndEpoch(responsePartition.offset, responsePartition.leaderEpoch)
      case error => throw error.exception
    }
  }

  // The partitions with an epoch start in the fetching state and truncate on the diverging epochs of the fetch
  // responses, so the epoch end offsets are never asked for.
  override def fetchEpochEndOffsets(partitions: java.util.Map[TopicPartition, OffsetForLeaderEpochRequestData.OffsetForLeaderPartition]): java.util.Map[TopicPartition, EpochEndOffset] = {
    partitions.asScala.map { case (tp, _) =>
      tp -> new EpochEndOffset()
        .setPartition(tp.partition)
        .setErrorCode(Errors.UNSUPPORTED_VERSION.code)
    }.asJava
  }

  override def buildFetch(partitions: java.util.Map[TopicPartition, PartitionFetchState]): ResultWithPartitions[Optional[ReplicaFetch]] = {
    val partitionsWithError = mutable.Set[TopicPartition]()
    val builder = fetchSessionHandler.newBuilder(partitions.size, false)
    partitions.forEach { (topicPartition, fetchState) =>
      if (fetchState.isReadyForFetch) {
        try {
          // Make sure that the destination log is still online before fetching for it.
          replicaManager.localLogOrException(topicPartition)
          builder.add(topicPartition, new FetchRequest.PartitionData(
            fetchState.topicId().orElse(Uuid.ZERO_UUID),
            fetchState.fetchOffset(),
            FetchRequest.INVALID_LOG_START_OFFSET,
            mirrorConfig.fetchMaxBytes,
            Optional.empty[Integer](),
            fetchState.lastFetchedEpoch()))
        } catch {
          case _: KafkaStorageException =>
            partitionsWithError += topicPartition
        }
      }
    }

    val fetchData = builder.build()
    val fetchRequestOpt = if (fetchData.sessionPartitions.isEmpty && fetchData.toForget.isEmpty) {
      Optional.empty[ReplicaFetch]
    } else {
      val requestBuilder = FetchRequest.Builder
        .forConsumer(ApiKeys.FETCH.latestVersion, mirrorConfig.fetchWaitMaxMs, mirrorConfig.fetchMinBytes, fetchData.toSend)
        .isolationLevel(IsolationLevel.READ_UNCOMMITTED)
        .setMaxBytes(mirrorConfig.fetchResponseMaxBytes)
        .removed(fetchData.toForget)
        .replaced(fetchData.toReplace)
        .metadata(fetchData.metadata)
      Optional.of(new ReplicaFetch(fetchData.sessionPartitions(), requestBuilder))
    }
    new ResultWithPartitions(fetchRequestOpt, partitionsWithError.asJava)
  }

  override def toString: String = s"MirrorLeaderEndPoint(blockingSender=$blockingSender)"
}
