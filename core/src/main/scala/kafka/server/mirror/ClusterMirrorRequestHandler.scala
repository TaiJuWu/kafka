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

import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.regex.Pattern

import kafka.server.{KafkaConfig, ReplicaManager}
import kafka.utils.Logging
import org.apache.kafka.clients.ClientResponse
import org.apache.kafka.clients.admin.{ListTopicsOptions, OffsetSpec}
import org.apache.kafka.common.{TopicPartition, Uuid}
import org.apache.kafka.common.errors.UnknownClusterMirrorException
import org.apache.kafka.common.message._
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.requests._
import org.apache.kafka.server.common.{ControllerRequestCompletionHandler, NodeToControllerChannelManager}
import org.apache.kafka.server.mirror.MirrorPartitionState
import org.apache.kafka.server.mirror.MirrorPartitionState._
import org.apache.kafka.server.network.BrokerEndPoint

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/**
 * Handles the cluster mirroring requests of the clients on a broker (KIP-1279). The lifecycle operations are validated
 * against the states of the mirror partitions, which the cluster mirror coordinators keep, and then sent to the
 * controller with the offset of the metadata image they were validated on, so that the controller rejects them if
 * the mirror state of their topics changed since. The work runs on the thread of the MirrorMetadataManager.
 */
class ClusterMirrorRequestHandler(manager: () => MirrorMetadataManager,
                                  controllerChannel: NodeToControllerChannelManager,
                                  replicaManager: ReplicaManager,
                                  config: KafkaConfig) extends Logging {
  this.logIdent = s"[ClusterMirrorRequestHandler id=${config.brokerId}] "

  private def timeoutMs: Int = config.requestTimeoutMs

  private def sendToController[T <: AbstractResponse](builder: AbstractRequest.Builder[_ <: AbstractRequest]): CompletableFuture[T] = {
    val future = new CompletableFuture[T]()
    controllerChannel.sendRequest(builder, new ControllerRequestCompletionHandler {
      override def onComplete(response: ClientResponse): Unit = {
        if (response.authenticationException != null) future.completeExceptionally(response.authenticationException)
        else if (response.versionMismatch != null) future.completeExceptionally(response.versionMismatch)
        else if (response.wasDisconnected) future.completeExceptionally(Errors.NETWORK_EXCEPTION.exception)
        else future.complete(response.responseBody.asInstanceOf[T])
      }

      override def onTimeout(): Unit = future.completeExceptionally(Errors.REQUEST_TIMED_OUT.exception)
    })
    future
  }

  private def checkMirror(name: String): Unit = {
    if (!manager().mirrorConfigs.contains(name))
      throw new UnknownClusterMirrorException(s"Cluster mirror $name does not exist")
  }

  /**
   * @return the topics of a cluster mirror in the metadata image, with their desired states
   */
  private def mirrorTopics(name: String): Map[String, MirrorPartitionState] =
    manager().image.clusterMirrors.topics.asScala.collect {
      case (topic, mirrorTopic) if mirrorTopic.mirrorName == name => topic -> MirrorPartitionState.fromValue(mirrorTopic.desiredState)
    }.toMap

  private def matching(candidates: Iterable[String], names: Iterable[String], patterns: java.util.List[String]): Seq[String] = {
    val compiled = Option(patterns).map(_.asScala.map(p => Pattern.compile(p.trim)).toSeq).getOrElse(Seq.empty)
    (names ++ candidates.filter(topic => compiled.exists(_.matcher(topic).matches()))).toSeq.distinct
  }

  /**
   * @return the partitions of a topic of the destination cluster, with the topic id
   */
  private def localPartitions(topic: String): Seq[(TopicPartition, Uuid)] =
    Option(manager().image.topics.getTopic(topic)).map { image =>
      image.partitions.keySet.asScala.toSeq.map(p => (new TopicPartition(topic, p.intValue), image.id))
    }.getOrElse(Seq.empty)

  /**
   * @return the topics, among the given ones, with a partition whose state is not one of the allowed states
   */
  private def invalidStates(name: String, topics: Seq[String], allowed: Set[MirrorPartitionState]): Map[String, String] = {
    val partitions = topics.flatMap(localPartitions)
    val states = manager().readStates(name, partitions)
    partitions.flatMap { case (tp, _) =>
      val state = states.get(tp).map(_.state)
      if (state.exists(allowed.contains)) None
      else Some(tp.topic -> s"Partition $tp is in state ${state.getOrElse("unavailable")}, not ${allowed.mkString(" or ")}")
    }.toMap
  }

  def startMirrorTopics(request: StartMirrorTopicsRequestData): CompletableFuture[StartMirrorTopicsResponseData] =
    manager().runOnManagerThread { () =>
      checkMirror(request.mirrorName)
      val admin = manager().adminFor(request.mirrorName)
      val requested = Option(request.topics).map(_.asScala.map(_.topicName)).getOrElse(Seq.empty)
      val sourceTopics = admin.listTopics(new ListTopicsOptions().timeoutMs(timeoutMs)).names.get(timeoutMs, TimeUnit.MILLISECONDS).asScala
        .filterNot(_.startsWith("__"))
      val topics = matching(sourceTopics, requested, request.topicPatterns)
      val response = new StartMirrorTopicsResponseData()
      if (topics.isEmpty) {
        CompletableFuture.completedFuture(response)
      } else {
        val descriptions = admin.describeTopics(topics.asJava).topicNameValues.asScala.flatMap { case (topic, future) =>
          try Some(topic -> future.get(timeoutMs, TimeUnit.MILLISECONDS)) catch {
            case e: Throwable =>
              response.topics.add(new StartMirrorTopicsResponseData.TopicResult().setTopicName(topic)
                .setErrorCode(Errors.forException(e.getCause).code).setErrorMessage(s"Failed to describe the source topic: ${e.getMessage}"))
              None
          }
        }.toMap
        val invalid = invalidStates(request.mirrorName, descriptions.keys.toSeq, Set(STOPPED, UNKNOWN))
        invalid.foreachEntry((topic, message) => response.topics.add(new StartMirrorTopicsResponseData.TopicResult()
          .setTopicName(topic).setErrorCode(Errors.INVALID_CLUSTER_MIRROR_STATE.code).setErrorMessage(message)))
        val data = new StartMirrorTopicsRequestData().setMirrorName(request.mirrorName)
          .setStateOffset(manager().image.offset).setTimeoutMs(timeoutMs)
        descriptions.foreachEntry { (topic, description) =>
          if (!invalid.contains(topic)) data.topics.add(new StartMirrorTopicsRequestData.TopicMetadata()
            .setTopicName(topic).setTopicId(description.topicId).setNumPartitions(description.partitions.size))
        }
        if (data.topics.isEmpty) CompletableFuture.completedFuture(response)
        else sendToController[StartMirrorTopicsResponse](new StartMirrorTopicsRequest.Builder(data)).thenApply { r =>
          r.data.topics.forEach(t => response.topics.add(t.duplicate))
          response.setErrorCode(r.data.errorCode).setErrorMessage(r.data.errorMessage)
        }
      }
    }

  /**
   * Validates and sends a stop, pause or resume operation, which changes the desired state of mirror topics.
   */
  private def changeDesiredState(mirrorName: String, requested: Seq[String], patterns: java.util.List[String],
                                    allowed: Set[MirrorPartitionState],
                                    send: (Seq[String], Long) => CompletableFuture[java.util.Map[String, (Short, String)]]): CompletableFuture[java.util.Map[String, (Short, String)]] =
    manager().runOnManagerThread { () =>
      checkMirror(mirrorName)
      val topics = matching(mirrorTopics(mirrorName).keys, requested, patterns)
      val results = new java.util.LinkedHashMap[String, (Short, String)]()
      val invalid = invalidStates(mirrorName, topics.filter(mirrorTopics(mirrorName).contains), allowed)
      invalid.foreachEntry((topic, message) => results.put(topic, (Errors.INVALID_CLUSTER_MIRROR_STATE.code, message)))
      val valid = topics.filterNot(invalid.contains)
      if (valid.isEmpty) CompletableFuture.completedFuture(results)
      else send(valid, manager().image.offset).thenApply { r =>
        results.putAll(r)
        manager().wakeup()
        results
      }
    }

  def stopMirrorTopics(request: StopMirrorTopicsRequestData): CompletableFuture[StopMirrorTopicsResponseData] =
    changeDesiredState(request.mirrorName, Option(request.topics).map(_.asScala.map(_.topicName).toSeq).getOrElse(Seq.empty),
      request.topicPatterns, Set(MIRRORING, PAUSED), (topics, offset) => {
        val data = new StopMirrorTopicsRequestData().setMirrorName(request.mirrorName).setStateOffset(offset).setTimeoutMs(timeoutMs)
        topics.foreach(t => data.topics.add(new StopMirrorTopicsRequestData.TopicMetadata().setTopicName(t)))
        sendToController[StopMirrorTopicsResponse](new StopMirrorTopicsRequest.Builder(data)).thenApply(r =>
          r.data.topics.asScala.map(t => t.topicName -> (t.errorCode, t.errorMessage)).toMap.asJava)
      }).thenApply { results =>
      val response = new StopMirrorTopicsResponseData()
      results.forEach((topic, error) => response.topics.add(new StopMirrorTopicsResponseData.TopicResult()
        .setTopicName(topic).setErrorCode(error._1).setErrorMessage(error._2)))
      response
    }

  def pauseMirrorTopics(request: PauseMirrorTopicsRequestData): CompletableFuture[PauseMirrorTopicsResponseData] =
    changeDesiredState(request.mirrorName, Option(request.topics).map(_.asScala.map(_.topicName).toSeq).getOrElse(Seq.empty),
      request.topicPatterns, Set(MIRRORING), (topics, offset) => {
        val data = new PauseMirrorTopicsRequestData().setMirrorName(request.mirrorName).setStateOffset(offset).setTimeoutMs(timeoutMs)
        topics.foreach(t => data.topics.add(new PauseMirrorTopicsRequestData.TopicMetadata().setTopicName(t)))
        sendToController[PauseMirrorTopicsResponse](new PauseMirrorTopicsRequest.Builder(data)).thenApply(r =>
          r.data.topics.asScala.map(t => t.topicName -> (t.errorCode, t.errorMessage)).toMap.asJava)
      }).thenApply { results =>
      val response = new PauseMirrorTopicsResponseData()
      results.forEach((topic, error) => response.topics.add(new PauseMirrorTopicsResponseData.TopicResult()
        .setTopicName(topic).setErrorCode(error._1).setErrorMessage(error._2)))
      response
    }

  def resumeMirrorTopics(request: ResumeMirrorTopicsRequestData): CompletableFuture[ResumeMirrorTopicsResponseData] =
    changeDesiredState(request.mirrorName, Option(request.topics).map(_.asScala.map(_.topicName).toSeq).getOrElse(Seq.empty),
      request.topicPatterns, Set(PAUSED), (topics, offset) => {
        val data = new ResumeMirrorTopicsRequestData().setMirrorName(request.mirrorName).setStateOffset(offset).setTimeoutMs(timeoutMs)
        topics.foreach(t => data.topics.add(new ResumeMirrorTopicsRequestData.TopicMetadata().setTopicName(t)))
        sendToController[ResumeMirrorTopicsResponse](new ResumeMirrorTopicsRequest.Builder(data)).thenApply(r =>
          r.data.topics.asScala.map(t => t.topicName -> (t.errorCode, t.errorMessage)).toMap.asJava)
      }).thenApply { results =>
      val response = new ResumeMirrorTopicsResponseData()
      results.forEach((topic, error) => response.topics.add(new ResumeMirrorTopicsResponseData.TopicResult()
        .setTopicName(topic).setErrorCode(error._1).setErrorMessage(error._2)))
      response
    }

  /**
   * Recovers the FAILED partitions of mirror topics: they go back to the state before the failure, and their retries
   * are reset. Not validated nor sent to the controller, as only the coordinators know which partitions failed.
   */
  def recoverMirrorTopics(request: RecoverMirrorTopicsRequestData): CompletableFuture[RecoverMirrorTopicsResponseData] =
    manager().runOnManagerThread { () =>
      checkMirror(request.mirrorName)
      val requested = Option(request.topics).map(_.asScala.map(_.topicName).toSeq).getOrElse(Seq.empty)
      val topics = matching(mirrorTopics(request.mirrorName).keys, requested, request.topicPatterns)
      val response = new RecoverMirrorTopicsResponseData()
      topics.foreach { topic =>
        val partitions = localPartitions(topic)
        val states = manager().readStates(request.mirrorName, partitions)
        val errors = partitions.flatMap { case (tp, topicId) =>
          states.get(tp).filter(_.state == FAILED).map { view =>
            val target = view.previousState match {
              case UNKNOWN | FAILED | STOPPING | STOPPED => LOG_ALIGNMENT
              case state => state
            }
            val leaderEpoch = Option(manager().image.topics.getTopic(topic)).map(_.partitions.get(tp.partition).leaderEpoch).getOrElse(-1)
            manager().writeStateFor(request.mirrorName, tp, topicId, leaderEpoch, view, target)
          }
        }.filter(_ != Errors.NONE)
        val error = errors.headOption.getOrElse(Errors.NONE)
        response.topics.add(new RecoverMirrorTopicsResponseData.TopicResult().setTopicName(topic).setErrorCode(error.code))
      }
      manager().wakeup()
      CompletableFuture.completedFuture(response)
    }

  def createClusterMirror(request: CreateClusterMirrorRequestData): CompletableFuture[CreateClusterMirrorResponseData] =
    sendToController[CreateClusterMirrorResponse](new CreateClusterMirrorRequest.Builder(request.setTimeoutMs(timeoutMs))).thenApply(_.data)

  /**
   * Deletes a cluster mirror, once all the partitions of its topics are STOPPED.
   */
  def deleteClusterMirror(request: DeleteClusterMirrorRequestData): CompletableFuture[DeleteClusterMirrorResponseData] =
    manager().runOnManagerThread { () =>
      checkMirror(request.mirrorName)
      val invalid = invalidStates(request.mirrorName, mirrorTopics(request.mirrorName).keys.toSeq, Set(STOPPED))
      if (invalid.nonEmpty) {
        CompletableFuture.completedFuture(new DeleteClusterMirrorResponseData()
          .setErrorCode(Errors.INVALID_CLUSTER_MIRROR_STATE.code).setErrorMessage(invalid.values.head))
      } else {
        sendToController[DeleteClusterMirrorResponse](new DeleteClusterMirrorRequest.Builder(new DeleteClusterMirrorRequestData()
          .setMirrorName(request.mirrorName).setStateOffset(manager().image.offset).setTimeoutMs(timeoutMs))).thenApply(_.data)
      }
    }

  private def sourceClusterId(name: String, mirrorConfig: Map[String, String]): String =
    mirrorConfig.get("source.cluster.id").orElse(
      try Some(manager().adminFor(name).describeCluster().clusterId.get(timeoutMs, TimeUnit.MILLISECONDS)) catch { case _: Throwable => None }
    ).orNull

  def listClusterMirrors(request: ListClusterMirrorsRequestData): CompletableFuture[ListClusterMirrorsResponseData] =
    manager().runOnManagerThread { () =>
      val response = new ListClusterMirrorsResponseData()
      val nameFilter = Option(request.mirrorNameFilter).map(_.asScala.toSet).filter(_.nonEmpty)
      val clusterFilter = Option(request.sourceClusterIdFilter).map(_.asScala.toSet).filter(_.nonEmpty)
      val stateFilter = Option(request.desiredStateFilter).map(_.asScala.map(_.toUpperCase).toSet).filter(_.nonEmpty)
      manager().mirrorConfigs.foreachEntry { (name, mirrorConfig) =>
        if (nameFilter.forall(_.contains(name))) {
          val clusterId = sourceClusterId(name, mirrorConfig)
          val topics = mirrorTopics(name).filter { case (_, state) => stateFilter.forall(_.contains(state.name)) }
          if (clusterFilter.forall(f => clusterId != null && f.contains(clusterId)) && (stateFilter.isEmpty || topics.nonEmpty)) {
            response.mirrors.add(new ListClusterMirrorsResponseData.ListedMirror().setMirrorName(name)
              .setSourceBootstrap(mirrorConfig.getOrElse("bootstrap.servers", "")).setSourceClusterId(clusterId)
              .setTopicNames(topics.keys.toSeq.sorted.asJava))
          }
        }
      }
      CompletableFuture.completedFuture(response)
    }

  /**
   * Answers the source and destination offsets of the mirror partitions which this broker leads.
   */
  def readMirrorOffsets(request: ReadMirrorOffsetsRequestData): CompletableFuture[ReadMirrorOffsetsResponseData] =
    manager().runOnManagerThread { () =>
      val requested = request.topics.asScala.flatMap(t => t.partitions.asScala.map(p => new TopicPartition(t.topicName, p.partitionIndex))).toSeq
      val response = new ReadMirrorOffsetsResponseData()
      localMirrorOffsets(request.mirrorName, requested).groupBy(_._1.topic).foreachEntry { (topic, offsets) =>
        val topicResult = new ReadMirrorOffsetsResponseData.TopicResult().setTopicName(topic)
        offsets.foreachEntry { case (tp, (source, destination)) =>
          topicResult.partitions.add(new ReadMirrorOffsetsResponseData.PartitionLag().setPartitionIndex(tp.partition)
            .setSourceOffset(source).setDestinationOffset(destination))
        }
        response.topics.add(topicResult)
      }
      CompletableFuture.completedFuture(response)
    }

  /**
   * @return the source and destination offsets of the given partitions, which this broker leads; runs on the thread
   *         of the manager
   */
  private def localMirrorOffsets(name: String, partitions: Seq[TopicPartition]): Map[TopicPartition, (Long, Long)] = {
    val sourceOffsets: Map[TopicPartition, Long] = try {
      manager().adminFor(name).listOffsets(partitions.map(_ -> OffsetSpec.latest()).toMap.asJava).all
        .get(timeoutMs, TimeUnit.MILLISECONDS).asScala.map { case (tp, info) => tp -> info.offset }.toMap
    } catch { case _: Throwable => Map.empty }
    partitions.map { tp =>
      val destination = replicaManager.onlinePartition(tp).flatMap(_.leaderLogIfLocal).map(_.logEndOffset).getOrElse(-1L)
      tp -> (sourceOffsets.getOrElse(tp, -1L), destination)
    }.toMap
  }

  private def readOffsetsFromLeaders(name: String, partitions: Seq[TopicPartition]): Map[TopicPartition, (Long, Long)] = {
    val byLeader = partitions.groupBy { tp =>
      replicaManager.metadataCache.getPartitionLeaderEndpoint(tp.topic, tp.partition, config.interBrokerListenerName).toScala
    }
    val result = mutable.Map[TopicPartition, (Long, Long)]()
    byLeader.foreachEntry { (leader, leaderPartitions) =>
      // The partitions which this broker leads are read locally: a request to this broker would wait for the thread
      // of the manager, which runs this method.
      leader.filter(_.id == config.brokerId).foreach(_ => result ++= localMirrorOffsets(name, leaderPartitions))
      leader.filter(_.id != config.brokerId).foreach { node =>
        val data = new ReadMirrorOffsetsRequestData().setMirrorName(name)
        leaderPartitions.groupBy(_.topic).foreachEntry { (topic, tps) =>
          val topicData = new ReadMirrorOffsetsRequestData.TopicMetadata().setTopicName(topic)
          tps.foreach(tp => topicData.partitions.add(new ReadMirrorOffsetsRequestData.PartitionData().setPartitionIndex(tp.partition)))
          data.topics.add(topicData)
        }
        manager().sendToCoordinator(new BrokerEndPoint(node.id, node.host, node.port), new ReadMirrorOffsetsRequest.Builder(data))
          .map(_.responseBody).foreach {
            case r: ReadMirrorOffsetsResponse => r.data.topics.forEach(t => t.partitions.forEach(p =>
              result(new TopicPartition(t.topicName, p.partitionIndex)) = (p.sourceOffset, p.destinationOffset)))
            case _ =>
          }
      }
    }
    result.toMap
  }

  def describeClusterMirrors(request: DescribeClusterMirrorsRequestData): CompletableFuture[DescribeClusterMirrorsResponseData] =
    manager().runOnManagerThread { () =>
      val response = new DescribeClusterMirrorsResponseData()
      val configs = manager().mirrorConfigs
      val names = Option(request.mirrorNames).map(_.asScala.toSeq).filter(_.nonEmpty).getOrElse(configs.keys.toSeq.sorted)
      names.foreach { name =>
        val mirror = new DescribeClusterMirrorsResponseData.DescribedMirror().setMirrorName(name)
        response.mirrors.add(mirror)
        configs.get(name) match {
          case None =>
            mirror.setErrorCode(Errors.UNKNOWN_CLUSTER_MIRROR.code).setErrorMessage(s"Cluster mirror $name does not exist")
          case Some(mirrorConfig) =>
            mirror.setSourceBootstrap(mirrorConfig.getOrElse("bootstrap.servers", null)).setSourceClusterId(sourceClusterId(name, mirrorConfig))
            val requestedTopics = Option(request.topics).map(_.asScala.map(t => t.topicName -> Option(t.partitions).map(_.asScala.map(_.intValue).toSet))).toSeq.flatten.toMap
            val topics = mirrorTopics(name).keys.toSeq.sorted.filter(t => requestedTopics.isEmpty || requestedTopics.contains(t))
            val partitions = topics.flatMap(localPartitions).filter { case (tp, _) =>
              requestedTopics.get(tp.topic).flatten.forall(_.contains(tp.partition))
            }
            val states = if (request.includeMirrorState) manager().readStates(name, partitions) else Map.empty[TopicPartition, MirrorMetadataManager.StateView]
            val offsets = if (request.includeMirrorOffset) readOffsetsFromLeaders(name, partitions.map(_._1)) else Map.empty[TopicPartition, (Long, Long)]
            partitions.groupBy(_._1.topic).toSeq.sortBy(_._1).foreach { case (topic, topicPartitions) =>
              val topicResult = new DescribeClusterMirrorsResponseData.TopicResult().setTopicName(topic)
              topicPartitions.sortBy(_._1.partition).foreach { case (tp, _) =>
                val detail = new DescribeClusterMirrorsResponseData.PartitionDetail().setPartitionIndex(tp.partition)
                  .setStateValue(states.get(tp).map(_.state.name).getOrElse(UNKNOWN.name))
                states.get(tp).foreach { view =>
                  detail.setRetryAttempt(view.retryAttempt.toShort)
                  view.lastMirror.foreach { case (epoch, offset) => detail.setLastMirrorEpoch(epoch).setLastMirrorOffset(offset) }
                }
                offsets.get(tp).foreach { case (source, destination) => detail.setSourceOffset(source).setDestinationOffset(destination) }
                topicResult.partitions.add(detail)
              }
              mirror.topics.add(topicResult)
            }
        }
      }
      CompletableFuture.completedFuture(response)
    }
}
