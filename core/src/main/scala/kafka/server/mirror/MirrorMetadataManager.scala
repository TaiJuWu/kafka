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

import java.util.Properties
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, ConcurrentLinkedQueue, Semaphore, ThreadLocalRandom, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import kafka.cluster.Partition
import kafka.server.{BrokerBlockingSender, InitialFetchState, KafkaConfig, ReplicaManager}
import kafka.utils.Logging
import org.apache.kafka.clients.ClientResponse
import org.apache.kafka.clients.admin.{Admin, AdminClientConfig}
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.internals.Topic
import org.apache.kafka.common.message.{BumpLeaderEpochsRequestData, CreateTopicsRequestData, ReadMirrorStatesRequestData, WriteMirrorStatesRequestData}
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.requests.{BumpLeaderEpochsRequest, BumpLeaderEpochsResponse, CreateTopicsRequest, CreateTopicsResponse, ReadMirrorStatesRequest, ReadMirrorStatesResponse, WriteMirrorStatesRequest, WriteMirrorStatesResponse}
import org.apache.kafka.common.{TopicPartition, Uuid}
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.utils.{Time, Utils}
import org.apache.kafka.common.utils.internals.LogContext
import org.apache.kafka.coordinator.mirror.{ClusterMirrorBrokerConfig, ClusterMirrorConfig}
import org.apache.kafka.image.{MetadataDelta, MetadataImage}
import org.apache.kafka.image.loader.LoaderManifest
import org.apache.kafka.image.publisher.MetadataPublisher
import org.apache.kafka.server.common.{ControllerRequestCompletionHandler, NodeToControllerChannelManager}
import org.apache.kafka.server.mirror.{MirrorPartition, MirrorPartitionState}
import org.apache.kafka.server.mirror.MirrorPartitionState._
import org.apache.kafka.server.network.BrokerEndPoint
import org.apache.kafka.server.util.ShutdownableThread

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

object MirrorMetadataManager {
  /**
   * The cluster mirrors are defined in the broker configuration, with the prefix cluster.mirror.<name>. followed by a
   * configuration of [[ClusterMirrorConfig]], such as cluster.mirror.m1.bootstrap.servers. The KIP stores them in the
   * metadata log instead.
   */
  val ClusterMirrorPrefix = "cluster.mirror."

  def clusterMirrors(config: KafkaConfig): Map[String, ClusterMirrorConfig] = {
    val byName = mutable.Map[String, Properties]()
    config.originalsWithPrefix(ClusterMirrorPrefix).forEach { (key, value) =>
      val separator = key.indexOf('.')
      if (separator > 0) {
        val props = byName.getOrElseUpdate(key.substring(0, separator), new Properties())
        props.put(key.substring(separator + 1), value)
      }
    }
    byName.map { case (name, props) => name -> ClusterMirrorConfig.fromProperties(props) }.toMap
  }

  def brokerConfig(config: KafkaConfig): ClusterMirrorBrokerConfig = {
    val props = new Properties()
    props.putAll(config.originals)
    new ClusterMirrorBrokerConfig(props)
  }

  private case class MirroredPartition(sourceLeader: BrokerEndPoint, sourceTopicId: Uuid, leaderEpoch: Int)

  /**
   * The state of a mirror partition kept by the cluster mirror coordinator.
   */
  private[mirror] case class StateView(state: MirrorPartitionState, stateEpoch: Int, retryAttempt: Int,
                                       previousState: MirrorPartitionState, lastMirror: Option[(Int, Long)])

  private sealed trait MirrorEvent
  private case object LeaderEpochExceeded extends MirrorEvent
  private case object UncleanTruncation extends MirrorEvent
  private case class DivergenceRejected(offset: Long) extends MirrorEvent

  // The fetcher id of the connections to the coordinators, so that their metrics differ from the replica fetchers'.
  private val CoordinatorClientId = -100
}

/**
 * Drives the mirroring of the partitions of which this broker is the leader, following the state machine of KIP-1279.
 * On every metadata update, and every mirror.metadata.refresh.interval.ms, it reads the states of these partitions
 * from the cluster mirror coordinator, applies the transitions which their desired states (the topic configuration
 * mirror.state) and the events of the mirror fetchers call for, and adds the MIRRORING partitions to the mirror
 * fetchers. The work runs on a thread of its own, so the calls to the coordinators and the source clusters do not
 * block the metadata publishers.
 */
class MirrorMetadataManager(config: KafkaConfig,
                            replicaManager: ReplicaManager,
                            controllerChannel: NodeToControllerChannelManager,
                            metrics: Metrics,
                            time: Time) extends MetadataPublisher with MirrorPartitionListener with Logging {
  import MirrorMetadataManager._

  this.logIdent = s"[MirrorMetadataManager id=${config.brokerId}] "

  private val brokerConfig = MirrorMetadataManager.brokerConfig(config)
  // The cluster mirrors defined in the broker configuration, and those created with CreateClusterMirror, which are
  // CLUSTER_MIRROR config resources of the metadata image.
  private val staticMirrors = clusterMirrors(config)
  @volatile private var latestImage: MetadataImage = MetadataImage.EMPTY
  private val fetcherManagers = mutable.Map[String, (Map[String, String], MirrorFetcherManager)]()
  private val tasks = new ConcurrentLinkedQueue[Runnable]()
  private val synchronizer = new MirrorSourceSynchronizer(this, replicaManager, config, time)
  private val admins = mutable.Map[String, Admin]()
  private val mirrored = mutable.Map[String, mutable.Map[TopicPartition, MirroredPartition]]()

  // The minimum leader epochs requested for the mirror partitions, until their leader epochs reach them.
  private val pendingEpochBumps = new ConcurrentHashMap[TopicPartition, Integer]()
  private val epochBumpInFlight = new AtomicBoolean(false)

  // The events of the mirror fetchers, consumed by the state machine.
  private val events = new ConcurrentHashMap[TopicPartition, MirrorEvent]()

  // Local state of the transitions in progress: the last mirror epoch and offset of a STOPPING partition, the leader
  // epoch it waits for, the retries of FAILED partitions.
  private val stoppingLastMirror = mutable.Map[TopicPartition, (Int, Long, Int)]()
  private val retryAttempts = mutable.Map[TopicPartition, Int]()
  private val nextRetryMs = mutable.Map[TopicPartition, Long]()

  private val coordinatorSenders = mutable.Map[Int, BrokerBlockingSender]()
  private val stateTopicCreationInFlight = new AtomicBoolean(false)

  // A permit is released on every metadata update; the thread also reconciles when none comes within the
  // refresh interval.
  private val updates = new Semaphore(0)

  private val thread = new ShutdownableThread(s"mirror-metadata-manager-${config.brokerId}", false) {
    override def doWork(): Unit = {
      if (updates.tryAcquire(brokerConfig.metadataRefreshIntervalMs, TimeUnit.MILLISECONDS))
        updates.drainPermits()
      var task = tasks.poll()
      while (task != null) {
        task.run()
        task = tasks.poll()
      }
      if (isRunning) {
        try {
          reconcile()
        } catch {
          case e: Throwable => warn("Failed to reconcile the mirror partitions", e)
        }
      }
    }
  }

  def startup(): Unit = {
    info(s"Starting with the cluster mirrors of the configuration ${staticMirrors.keys.mkString(", ")}")
    thread.start()
  }

  /**
   * @return the configurations of the cluster mirrors, by name
   */
  private[mirror] def mirrorConfigs: Map[String, Map[String, String]] = {
    val fromImage = latestImage.configs.resourceData.asScala.collect {
      case (resource, data) if resource.`type` == ConfigResource.Type.CLUSTER_MIRROR && !data.isEmpty =>
        resource.name -> data.toMap.asScala.toMap
    }
    staticMirrors.map { case (name, mirrorConfig) =>
      name -> mirrorConfig.originals.asScala.map { case (k, v) => k -> String.valueOf(v) }.toMap
    } ++ fromImage
  }

  private[mirror] def image: MetadataImage = latestImage

  /**
   * @return the cluster mirror and the desired state of a topic: from the metadata image, or from the topic
   *         configurations mirror.name and mirror.state of the first phase
   */
  private[mirror] def mirrorOf(topic: String, logConfig: Option[org.apache.kafka.storage.internals.log.LogConfig]): Option[(String, MirrorPartitionState)] = {
    Option(latestImage.clusterMirrors.topics.get(topic))
      .map(t => t.mirrorName -> MirrorPartitionState.fromValue(t.desiredState))
      .orElse(logConfig.filter(_.mirrorName.nonEmpty).map(c => c.mirrorName -> (if (c.isMirroring) MIRRORING else STOPPED)))
  }

  /**
   * Runs a task on the thread of the manager, which owns the connections to the coordinators and the source clusters.
   */
  private[mirror] def runOnManagerThread[T](task: () => CompletableFuture[T]): CompletableFuture[T] = {
    val result = new CompletableFuture[T]()
    tasks.add(() => {
      try {
        task().whenComplete((value, exception) => if (exception != null) result.completeExceptionally(exception) else result.complete(value))
      } catch {
        case e: Throwable => result.completeExceptionally(e)
      }
    })
    signal()
    result
  }

  /**
   * Creates, updates and removes the fetcher managers of the cluster mirrors.
   */
  private def syncFetcherManagers(): Unit = {
    val configs = mirrorConfigs
    fetcherManagers.keySet.toSet.foreach { (name: String) =>
      if (!configs.get(name).contains(fetcherManagers(name)._1)) {
        info(s"Cluster mirror $name was ${if (configs.contains(name)) "updated" else "deleted"}")
        fetcherManagers.remove(name).foreach(_._2.shutdown())
        mirrored.remove(name)
        admins.remove(name).foreach(admin => Utils.closeQuietly(admin, "mirror admin client"))
      }
    }
    configs.foreachEntry { (name, mirrorConfig) =>
      if (!fetcherManagers.contains(name)) {
        val props = new Properties()
        mirrorConfig.foreachEntry((k, v) => props.put(k, v))
        fetcherManagers(name) = mirrorConfig -> new MirrorFetcherManager(config.brokerId, name,
          ClusterMirrorConfig.fromProperties(props), brokerConfig.numReplicaFetchers, replicaManager, this, metrics, time)
      }
    }
  }

  override def name(): String = "MirrorMetadataManager"

  override def onMetadataUpdate(delta: MetadataDelta, newImage: MetadataImage, manifest: LoaderManifest): Unit = {
    latestImage = newImage
    if (delta.topicsDelta() != null || delta.clusterMirrorsDelta() != null) {
      // The partitions of mirror topics stay read-only until their state is known to be STOPPED. The followers are
      // marked too, so that a follower which becomes the leader does not cache its own leader epoch.
      val mirrorTopics = newImage.clusterMirrors.topics
      replicaManager.onlinePartitionsIterator.foreach { partition =>
        partition.mirrorTopic = mirrorTopics.containsKey(partition.topic)
      }
    }
    if (delta.topicsDelta() != null || delta.configsDelta() != null || delta.clusterMirrorsDelta() != null)
      signal()
  }

  private def signal(): Unit = updates.release()

  override def close(): Unit = {
    thread.initiateShutdown()
    signal()
    thread.awaitShutdown()
    fetcherManagers.values.foreach(_._2.shutdown())
    synchronizer.close()
    admins.values.foreach(admin => Utils.closeQuietly(admin, "mirror admin client"))
    admins.clear()
    coordinatorSenders.values.foreach(sender => Utils.closeQuietly(() => sender.close(), "mirror coordinator client"))
    coordinatorSenders.clear()
  }

  override def onLeaderEpochExceeded(topicPartition: TopicPartition, sourceEpoch: Int): Unit = {
    events.put(topicPartition, LeaderEpochExceeded)
    signal()
  }

  override def onUncleanTruncation(topicPartition: TopicPartition, offset: Long): Unit = {
    events.put(topicPartition, UncleanTruncation)
    signal()
  }

  override def onDivergenceRejected(topicPartition: TopicPartition, offset: Long): Unit = {
    events.put(topicPartition, DivergenceRejected(offset))
    signal()
  }

  override def requestLeaderEpochBump(topicPartition: TopicPartition, minLeaderEpoch: Int): Unit = {
    // The fetcher asks on every fetch while the source epoch is close, so only a new or higher request wakes up the
    // manager.
    val previous = pendingEpochBumps.get(topicPartition)
    if (previous == null || previous < minLeaderEpoch) {
      pendingEpochBumps.merge(topicPartition, minLeaderEpoch, (a, b) => math.max(a, b))
      signal()
    }
  }

  /**
   * Sends the requested leader epoch bumps of the partitions which this broker still leads to the controller, one
   * request at a time. A request is dropped once the leader epoch of the partition reaches it.
   */
  private def maybeBumpLeaderEpochs(): Unit = {
    val requests = mutable.Map[TopicPartition, Int]()
    pendingEpochBumps.forEach { (tp, minEpoch) =>
      replicaManager.onlinePartition(tp).filter(_.isLeader) match {
        case Some(partition) if partition.getLeaderEpoch < minEpoch => requests(tp) = minEpoch
        case _ => pendingEpochBumps.remove(tp, minEpoch)
      }
    }
    if (requests.isEmpty || !epochBumpInFlight.compareAndSet(false, true))
      return

    val data = new BumpLeaderEpochsRequestData().setTimeoutMs(config.requestTimeoutMs)
    requests.groupBy(_._1.topic).foreachEntry { (topic, partitions) =>
      val topicState = new BumpLeaderEpochsRequestData.TopicState().setTopicName(topic)
      partitions.foreachEntry { (tp, minEpoch) =>
        topicState.partitions.add(new BumpLeaderEpochsRequestData.LeaderEpochState()
          .setPartitionIndex(tp.partition).setMinLeaderEpoch(minEpoch))
      }
      data.topics.add(topicState)
    }
    info(s"Requesting leader epoch bumps ${requests.mkString(", ")}")
    controllerChannel.sendRequest(new BumpLeaderEpochsRequest.Builder(data), new ControllerRequestCompletionHandler {
      override def onComplete(response: ClientResponse): Unit = {
        epochBumpInFlight.set(false)
        if (response.authenticationException != null || response.versionMismatch != null || response.wasDisconnected) {
          warn(s"Failed to bump the leader epochs of mirror partitions: $response")
        } else {
          val body = response.responseBody.asInstanceOf[BumpLeaderEpochsResponse].data
          if (body.errorCode != Errors.NONE.code)
            warn(s"Failed to bump the leader epochs of mirror partitions: ${Errors.forCode(body.errorCode)} ${body.errorMessage}")
          body.topics.forEach(topic => topic.partitions.forEach { partition =>
            if (partition.errorCode != Errors.NONE.code)
              warn(s"Failed to bump the leader epoch of ${topic.topicName}-${partition.partitionIndex}: " +
                s"${Errors.forCode(partition.errorCode)} ${partition.errorMessage}")
          })
        }
        signal()
      }

      override def onTimeout(): Unit = {
        epochBumpInFlight.set(false)
        warn("Timed out bumping the leader epochs of mirror partitions")
        signal()
      }
    })
  }

  private[mirror] def reconcile(): Unit = {
    maybeBumpLeaderEpochs()

    syncFetcherManagers()
    fetcherManagers.foreachEntry { case (name, (_, manager)) =>
      synchronizer.maybeSync(name, manager.mirrorConfig, brokerConfig.metadataRefreshIntervalMs)
    }
    val desiredStates = mutable.Map[TopicPartition, MirrorPartitionState]()
    val partitionsByMirror = replicaManager.leaderPartitionsIterator.flatMap { partition =>
      mirrorOf(partition.topic, partition.log.map(_.config)).map { case (name, desired) =>
        desiredStates(partition.topicPartition) = desired
        name -> partition
      }
    }.toSeq.groupMap(_._1)(_._2)

    partitionsByMirror.keySet.diff(fetcherManagers.keySet).foreach { name =>
      warn(s"Partitions ${partitionsByMirror(name).map(_.topicPartition).mkString(", ")} are mirrored from " +
        s"unknown cluster mirror $name")
    }

    val stateTopicReady = partitionsByMirror.isEmpty || ensureStateTopic()
    fetcherManagers.foreachEntry { case (name, (_, manager)) =>
      try {
        val partitions = partitionsByMirror.getOrElse(name, Seq.empty)
        val mirroring = if (stateTopicReady) applyTransitions(name, manager, partitions, desiredStates) else Seq.empty
        reconcileMirror(name, manager, mirroring)
      } catch {
        case e: Throwable => warn(s"Failed to update the mirrored partitions of cluster mirror $name", e)
      }
    }
  }

  /**
   * Applies the state transitions of the given partitions of a cluster mirror.
   *
   * @return the partitions in the MIRRORING state, which must have a mirror fetcher
   */
  private def applyTransitions(name: String, manager: MirrorFetcherManager, partitions: Seq[Partition],
                               desiredStates: collection.Map[TopicPartition, MirrorPartitionState]): Seq[Partition] = {
    if (partitions.isEmpty)
      return Seq.empty
    val states = readStates(name, partitions)
    partitions.filter { partition =>
      states.get(partition.topicPartition) match {
        case None => false // The coordinator is not available yet, the partition is retried in the next round.
        case Some(view) =>
          try {
            transition(name, manager, partition, view, desiredStates(partition.topicPartition)) == MIRRORING
          } catch {
            case e: Throwable =>
              warn(s"Failed the state transition of mirror partition ${partition.topicPartition} in state ${view.state}", e)
              false
          }
      }
    }
  }

  /**
   * Applies the next state transition of a mirror partition.
   *
   * @return the state of the partition after the transition
   */
  private def transition(name: String, manager: MirrorFetcherManager, partition: Partition, view: StateView,
                         desired: MirrorPartitionState): MirrorPartitionState = {
    val tp = partition.topicPartition
    val log = partition.localLogOrException
    val desiredMirroring = desired == MIRRORING
    partition.mirrorWritable = desired == STOPPED && (view.state == STOPPED || view.state == UNKNOWN)

    def removeFetcher(): Unit = {
      manager.removeFetcherForPartitions(Set(tp))
      mirrored.get(name).foreach(_.remove(tp))
    }

    def write(state: MirrorPartitionState, lastMirror: Option[(Int, Long)] = None, error: Option[String] = None,
              nonRetryable: Boolean = false): MirrorPartitionState = {
      if (writeState(name, partition, view, state, lastMirror, error, nonRetryable)) state else view.state
    }

    if (desiredMirroring) {
      view.state match {
        case UNKNOWN | STOPPED =>
          write(LOG_ALIGNMENT)
        case LOG_ALIGNMENT =>
          // Only the source owns the leader epochs above the last mirror epoch, so the log is truncated to the last
          // mirror offset; without one, the mirror starts from scratch.
          val target = view.lastMirror.map(_._2).getOrElse(0L)
          if (log.logEndOffset > target) {
            info(s"Truncating mirror partition $tp from ${log.logEndOffset} to the last mirror offset $target")
            partition.truncateTo(target, isFuture = false)
          }
          if (replicasCaughtUp(partition, inSyncOnly = !log.config.mirrorUncleanLeaderElectionEnable)) write(MIRRORING) else LOG_ALIGNMENT
        case MIRRORING =>
          Option(events.remove(tp)) match {
            case Some(LeaderEpochExceeded) => write(EPOCH_FENCING)
            case Some(UncleanTruncation) => write(ULE_RECOVERY)
            case Some(DivergenceRejected(offset)) =>
              write(FAILED, error = Some(s"The source partition diverged at offset $offset after an unclean leader " +
                "election, and mirror.unclean.leader.election.enable is not set"), nonRetryable = true)
            case None if manager.failedPartitions.contains(tp) =>
              write(FAILED, error = Some("The mirror fetcher failed"))
            case None => MIRRORING
          }
        case EPOCH_FENCING =>
          if (pendingEpochBumps.containsKey(tp)) EPOCH_FENCING else write(MIRRORING)
        case ULE_RECOVERY =>
          if (replicasCaughtUp(partition, inSyncOnly = false)) write(MIRRORING) else ULE_RECOVERY
        case FAILED =>
          maybeRetry(name, partition, view, write)
        case STOPPING =>
          finishStopping(name, partition, view, write)
        case PAUSING | PAUSED =>
          // Resume: the mirror goes on from the log end offset.
          write(MIRRORING)
        case state =>
          state
      }
    } else if (desired == PAUSED) {
      view.state match {
        case MIRRORING | EPOCH_FENCING =>
          val state = write(PAUSING)
          if (state == PAUSING) {
            removeFetcher()
            writeState(name, partition, view.copy(state = PAUSING, stateEpoch = view.stateEpoch + 1), PAUSED, None, None, false)
            PAUSED
          } else state
        case PAUSING =>
          removeFetcher()
          write(PAUSED)
        case state => state
      }
    } else {
      view.state match {
        case UNKNOWN | STOPPED => view.state
        case STOPPING => finishStopping(name, partition, view, write)
        case _ =>
          // The last mirror epoch and offset are taken before the leader epoch is bumped, which caches the epoch of
          // the destination in the log.
          val lastMirror = (log.latestEpoch.orElse(-1).intValue, log.logEndOffset, partition.getLeaderEpoch + 1)
          val state = write(STOPPING)
          if (state == STOPPING) {
            stoppingLastMirror(tp) = lastMirror
            finishStopping(name, partition, view.copy(state = STOPPING, stateEpoch = view.stateEpoch + 1), write)
          } else state
      }
    }
  }

  /**
   * STOPPING: removes the fetcher, bumps the leader epoch to draw a boundary between the mirrored records and the
   * records written locally, and stores the last mirror epoch and offset. The partition becomes writable once STOPPED.
   */
  private def finishStopping(name: String, partition: Partition, view: StateView,
                             write: (MirrorPartitionState, Option[(Int, Long)], Option[String], Boolean) => MirrorPartitionState): MirrorPartitionState = {
    val tp = partition.topicPartition
    fetcherManagers.get(name).foreach(_._2.removeFetcherForPartitions(Set(tp)))
    mirrored.values.foreach(_.remove(tp))
    val log = partition.localLogOrException
    val (lastMirrorEpoch, lastMirrorOffset, targetEpoch) = stoppingLastMirror.getOrElseUpdate(tp,
      (log.latestEpoch.orElse(-1).intValue, log.logEndOffset, partition.getLeaderEpoch + 1))
    if (partition.getLeaderEpoch < targetEpoch) {
      requestLeaderEpochBump(tp, targetEpoch)
      STOPPING
    } else {
      val state = write(STOPPED, Some((lastMirrorEpoch, lastMirrorOffset)), None, false)
      if (state == STOPPED) {
        stoppingLastMirror.remove(tp)
        retryAttempts.remove(tp)
        nextRetryMs.remove(tp)
        info(s"Stopped the mirroring of $tp at epoch $lastMirrorEpoch and offset $lastMirrorOffset")
      }
      state
    }
  }

  /**
   * FAILED: retries the previous state after a backoff with full jitter, unless the failure is non-retryable or the
   * retries are exhausted.
   */
  private def maybeRetry(name: String, partition: Partition, view: StateView,
                         write: (MirrorPartitionState, Option[(Int, Long)], Option[String], Boolean) => MirrorPartitionState): MirrorPartitionState = {
    val tp = partition.topicPartition
    fetcherManagers.get(name).foreach(_._2.removeFetcherForPartitions(Set(tp)))
    mirrored.values.foreach(_.remove(tp))
    val maxAttempts = brokerConfig.failedRetryMaxAttempts
    if (view.retryAttempt < 0 || view.retryAttempt >= maxAttempts)
      return FAILED
    val now = time.milliseconds
    val retryAt = nextRetryMs.getOrElseUpdate(tp, {
      val cap = math.min(brokerConfig.failedRetryMaxBackoffMs.toDouble,
        brokerConfig.failedRetryInitialBackoffMs * math.pow(2, view.retryAttempt)).toLong
      now + ThreadLocalRandom.current().nextLong(math.max(cap, 1L))
    })
    if (now < retryAt)
      return FAILED
    nextRetryMs.remove(tp)
    retryAttempts(tp) = view.retryAttempt + 1
    val target = view.previousState match {
      case UNKNOWN | FAILED | STOPPING | STOPPED => LOG_ALIGNMENT
      case state => state
    }
    info(s"Retrying mirror partition $tp in state $target after ${view.retryAttempt + 1} failures")
    write(target, None, None, false)
  }

  /**
   * @return true if the replicas of the partition, or only those in the ISR, have the log end offset of the leader
   */
  private def replicasCaughtUp(partition: Partition, inSyncOnly: Boolean): Boolean = {
    val logEndOffset = partition.localLogOrException.logEndOffset
    val isr = partition.inSyncReplicaIds
    partition.remoteReplicas
      .filter(replica => !inSyncOnly || isr.contains(replica.brokerId))
      .forall(_.stateSnapshot.logEndOffset == logEndOffset)
  }

  /**
   * Creates the __mirror_state topic through the controller the first time that it is needed.
   *
   * @return true if the topic exists
   */
  private def ensureStateTopic(): Boolean = {
    if (replicaManager.metadataCache.contains(Topic.MIRROR_STATE_TOPIC_NAME))
      return true
    if (stateTopicCreationInFlight.compareAndSet(false, true)) {
      val topic = new CreateTopicsRequestData.CreatableTopic()
        .setName(Topic.MIRROR_STATE_TOPIC_NAME)
        .setNumPartitions(brokerConfig.stateTopicNumPartitions)
        .setReplicationFactor(brokerConfig.stateTopicReplicationFactor)
      Seq("cleanup.policy" -> "compact",
        "min.insync.replicas" -> brokerConfig.stateTopicMinISR.toString,
        "segment.bytes" -> brokerConfig.stateTopicSegmentBytes.toString).foreach { case (key, value) =>
        topic.configs.add(new CreateTopicsRequestData.CreatableTopicConfig().setName(key).setValue(value))
      }
      val data = new CreateTopicsRequestData().setTimeoutMs(config.requestTimeoutMs)
      data.topics.add(topic)
      info(s"Creating the ${Topic.MIRROR_STATE_TOPIC_NAME} topic")
      controllerChannel.sendRequest(new CreateTopicsRequest.Builder(data), new ControllerRequestCompletionHandler {
        override def onComplete(response: ClientResponse): Unit = {
          stateTopicCreationInFlight.set(false)
          Option(response.responseBody).collect { case r: CreateTopicsResponse => r }.foreach { r =>
            r.data.topics.forEach { result =>
              if (result.errorCode != Errors.NONE.code && result.errorCode != Errors.TOPIC_ALREADY_EXISTS.code)
                warn(s"Failed to create the ${Topic.MIRROR_STATE_TOPIC_NAME} topic: ${Errors.forCode(result.errorCode)} ${result.errorMessage}")
            }
          }
          signal()
        }

        override def onTimeout(): Unit = {
          stateTopicCreationInFlight.set(false)
          signal()
        }
      })
    }
    false
  }

  private def coordinatorFor(name: String, partition: Partition): Option[(Int, BrokerEndPoint)] =
    partition.topicId.flatMap(topicId => coordinatorFor(name, topicId, partition.partitionId))

  private[mirror] def coordinatorFor(name: String, topicId: Uuid, partitionIndex: Int): Option[(Int, BrokerEndPoint)] = {
    for {
      numPartitions <- replicaManager.metadataCache.numPartitions(Topic.MIRROR_STATE_TOPIC_NAME).toScala.map(_.intValue)
      coordinatorPartition = new MirrorPartition(name, topicId, partitionIndex).coordinatingPartition(numPartitions)
      node <- replicaManager.metadataCache.getPartitionLeaderEndpoint(Topic.MIRROR_STATE_TOPIC_NAME, coordinatorPartition,
        config.interBrokerListenerName).toScala
    } yield (node.id, new BrokerEndPoint(node.id, node.host, node.port))
  }

  private[mirror] def sendToCoordinator(endPoint: BrokerEndPoint, request: org.apache.kafka.common.requests.AbstractRequest.Builder[_ <: org.apache.kafka.common.requests.AbstractRequest]): Option[ClientResponse] = {
    val sender = coordinatorSenders.getOrElseUpdate(endPoint.id, new BrokerBlockingSender(endPoint, config, metrics, time,
      CoordinatorClientId, s"mirror-state-client-${config.brokerId}",
      new LogContext(s"[MirrorStateClient broker=${config.brokerId} coordinator=${endPoint.id}] ")))
    try {
      Some(sender.sendRequest(request))
    } catch {
      case e: Throwable =>
        debug(s"Failed to send $request to the mirror coordinator ${endPoint.id}", e)
        coordinatorSenders.remove(endPoint.id).foreach(s => Utils.closeQuietly(() => s.close(), "mirror coordinator client"))
        None
    }
  }

  /**
   * Reads the states of the given partitions of a cluster mirror, with one request per coordinator.
   */
  private def readStates(name: String, partitions: Seq[Partition]): Map[TopicPartition, StateView] =
    readStates(name, partitions.flatMap(p => p.topicId.map(id => (p.topicPartition, id))))

  /**
   * Reads the states of the given partitions of a cluster mirror, by topic id, with one request per coordinator.
   */
  private[mirror] def readStates(name: String, partitions: Iterable[(TopicPartition, Uuid)]): Map[TopicPartition, StateView] = {
    val result = mutable.Map[TopicPartition, StateView]()
    partitions.flatMap { case (tp, topicId) => coordinatorFor(name, topicId, tp.partition).map(_ -> tp) }
      .groupMap(_._1)(_._2).foreachEntry { case ((_, endPoint), group) =>
      val data = new ReadMirrorStatesRequestData().setMirrorName(name)
      group.groupBy(_.topic).foreachEntry { (topic, topicPartitions) =>
        val topicData = new ReadMirrorStatesRequestData.TopicMetadata().setTopicName(topic)
        topicPartitions.foreach(tp => topicData.partitions.add(new ReadMirrorStatesRequestData.PartitionData().setPartitionIndex(tp.partition)))
        data.topics.add(topicData)
      }
      sendToCoordinator(endPoint, new ReadMirrorStatesRequest.Builder(data)).map(_.responseBody).foreach {
        case response: ReadMirrorStatesResponse =>
          response.data.topics.forEach(topic => topic.partitions.forEach { p =>
            if (p.errorCode == Errors.NONE.code) {
              result(new TopicPartition(topic.topicName, p.partitionIndex)) = StateView(
                MirrorPartitionState.fromValue(p.state), p.stateEpoch, p.retryAttempt,
                MirrorPartitionState.fromValue(p.previousState),
                if (p.lastMirrorEpoch >= 0) Some((p.lastMirrorEpoch, p.lastMirrorOffset)) else None)
            } else {
              debug(s"Failed to read the state of ${topic.topicName}-${p.partitionIndex}: ${Errors.forCode(p.errorCode)}")
            }
          })
        case _ =>
      }
    }
    result.toMap
  }

  /**
   * Writes a state transition of a mirror partition, with its leader epoch and the state epoch read last, so that
   * the coordinator fences the transitions decided on a stale state.
   *
   * @return true if the transition was written
   */
  private def writeState(name: String, partition: Partition, view: StateView, state: MirrorPartitionState,
                         lastMirror: Option[(Int, Long)], error: Option[String], nonRetryable: Boolean): Boolean = {
    val tp = partition.topicPartition
    coordinatorFor(name, partition).exists { case (_, endPoint) =>
      val partitionData = new WriteMirrorStatesRequestData.PartitionData()
        .setPartitionIndex(partition.partitionId)
        .setState(state.value)
        .setLeaderEpoch(partition.getLeaderEpoch)
        .setStateEpoch(view.stateEpoch)
        .setRetryAttempt(retryAttempts.getOrElse(tp, 0).toShort)
        .setNonRetryable(nonRetryable)
      lastMirror.foreach { case (epoch, offset) => partitionData.setLastMirrorEpoch(epoch).setLastMirrorOffset(offset) }
      error.foreach(partitionData.setErrorMessage)
      val topicData = new WriteMirrorStatesRequestData.TopicMetadata().setTopicName(tp.topic)
      topicData.partitions.add(partitionData)
      val data = new WriteMirrorStatesRequestData().setMirrorName(name)
      data.topics.add(topicData)
      sendToCoordinator(endPoint, new WriteMirrorStatesRequest.Builder(data)).map(_.responseBody).exists {
        case response: WriteMirrorStatesResponse =>
          val errors = response.data.topics.asScala.flatMap(_.partitions.asScala).map(p => Errors.forCode(p.errorCode)).toSet
          if (errors == Set(Errors.NONE)) {
            info(s"Mirror partition $tp: ${view.state} -> $state")
            true
          } else {
            info(s"The transition of mirror partition $tp from ${view.state} to $state was not written: ${errors.mkString(", ")}")
            false
          }
        case _ => false
      }
    }
  }

  private def reconcileMirror(name: String, manager: MirrorFetcherManager, partitions: Seq[kafka.cluster.Partition]): Unit = {
    val current = mirrored.getOrElseUpdate(name, mutable.Map())
    val desired = partitions.map(partition => partition.topicPartition -> partition).toMap

    val removed = current.keySet.diff(desired.keySet).toSet
    if (removed.nonEmpty) {
      info(s"Stopping the mirroring of partitions ${removed.mkString(", ")} from cluster mirror $name")
      manager.removeFetcherForPartitions(removed)
      current --= removed
    }

    if (desired.nonEmpty) {
      val admin = adminFor(name)
      val sourceTopics = admin.describeTopics(desired.keySet.map(_.topic).asJava).allTopicNames()
        .get(manager.mirrorConfig.requestTimeoutMs, TimeUnit.MILLISECONDS)

      val added = mutable.Map[TopicPartition, InitialFetchState]()
      desired.foreachEntry { (tp, partition) =>
        Option(sourceTopics.get(tp.topic)).flatMap(_.partitions.asScala.find(_.partition == tp.partition)) match {
          case None =>
            warn(s"Partition $tp does not exist in the source cluster of cluster mirror $name")
          case Some(sourcePartition) if sourcePartition.leader == null || sourcePartition.leader.isEmpty =>
            debug(s"Partition $tp has no leader in the source cluster of cluster mirror $name")
          case Some(sourcePartition) =>
            val leader = sourcePartition.leader
            val target = MirroredPartition(new BrokerEndPoint(leader.id, leader.host, leader.port),
              sourceTopics.get(tp.topic).topicId, partition.getLeaderEpoch)
            val previous = current.get(tp)
            // A partition which failed is moved out of MIRRORING by the state machine, which removes its fetcher.
            if (!previous.contains(target) && !manager.failedPartitions.contains(tp)) {
              if (previous.isDefined)
                manager.removeFetcherForPartitions(Set(tp))
              added(tp) = InitialFetchState(Some(target.sourceTopicId), target.sourceLeader, target.leaderEpoch,
                partition.localLogOrException.logEndOffset)
              current(tp) = target
            }
        }
      }

      if (added.nonEmpty) {
        info(s"Mirroring partitions ${added.keys.mkString(", ")} from cluster mirror $name")
        manager.addFetcherForPartitions(added)
      }
    }
    manager.shutdownIdleFetcherThreads()
  }

  /**
   * Writes a state transition of any partition of a cluster mirror, for the operations of the clients such as
   * RecoverMirrorTopics, with the leader epoch of the partition in the metadata image.
   *
   * @return the error of the write
   */
  private[mirror] def writeStateFor(name: String, tp: TopicPartition, topicId: Uuid, leaderEpoch: Int, view: StateView,
                                    state: MirrorPartitionState): Errors = {
    retryAttempts.remove(tp)
    nextRetryMs.remove(tp)
    coordinatorFor(name, topicId, tp.partition) match {
      case None => Errors.COORDINATOR_NOT_AVAILABLE
      case Some((_, endPoint)) =>
        val partitionData = new WriteMirrorStatesRequestData.PartitionData()
          .setPartitionIndex(tp.partition).setState(state.value).setLeaderEpoch(leaderEpoch)
          .setStateEpoch(view.stateEpoch).setRetryAttempt(0.toShort)
        val topicData = new WriteMirrorStatesRequestData.TopicMetadata().setTopicName(tp.topic)
        topicData.partitions.add(partitionData)
        val data = new WriteMirrorStatesRequestData().setMirrorName(name)
        data.topics.add(topicData)
        sendToCoordinator(endPoint, new WriteMirrorStatesRequest.Builder(data)).map(_.responseBody) match {
          case Some(response: WriteMirrorStatesResponse) =>
            response.data.topics.asScala.flatMap(_.partitions.asScala).map(p => Errors.forCode(p.errorCode))
              .find(_ != Errors.NONE).getOrElse(Errors.NONE)
          case _ => Errors.COORDINATOR_NOT_AVAILABLE
        }
    }
  }

  /**
   * Wakes up the manager, for instance after a client changed the states of mirror partitions.
   */
  private[mirror] def wakeup(): Unit = signal()

  private[mirror] def adminFor(name: String): Admin = {
    admins.getOrElseUpdate(name, {
      val props = new Properties()
      mirrorConfigs(name).foreachEntry((key, value) => props.put(key, value))
      props.put(AdminClientConfig.CLIENT_ID_CONFIG, s"mirror-$name-broker-${config.brokerId}-admin")
      Admin.create(props)
    })
  }
}
