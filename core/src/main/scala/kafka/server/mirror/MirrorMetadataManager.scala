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
import java.util.concurrent.{Semaphore, TimeUnit}

import kafka.server.{InitialFetchState, KafkaConfig, ReplicaManager}
import kafka.utils.Logging
import org.apache.kafka.clients.admin.{Admin, AdminClientConfig}
import org.apache.kafka.common.{TopicPartition, Uuid}
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.utils.{Time, Utils}
import org.apache.kafka.coordinator.mirror.{ClusterMirrorBrokerConfig, ClusterMirrorConfig}
import org.apache.kafka.image.{MetadataDelta, MetadataImage}
import org.apache.kafka.image.loader.LoaderManifest
import org.apache.kafka.image.publisher.MetadataPublisher
import org.apache.kafka.server.network.BrokerEndPoint
import org.apache.kafka.server.util.ShutdownableThread

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object MirrorMetadataManager {
  /**
   * In this phase, the cluster mirrors are defined in the broker configuration, with the prefix
   * cluster.mirror.<name>. followed by a configuration of [[ClusterMirrorConfig]], such as
   * cluster.mirror.m1.bootstrap.servers. The KIP stores them in the metadata log instead.
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

  private case class MirroredPartition(sourceLeader: BrokerEndPoint, sourceTopicId: Uuid, leaderEpoch: Int)
}

/**
 * Drives the mirroring of the partitions of which this broker is the leader. On every metadata update, and every
 * mirror.metadata.refresh.interval.ms, it finds the leader partitions whose topic is mirrored from a cluster mirror,
 * looks up the topic id and the partition leaders of the source topics, and adds or removes them from the mirror
 * fetchers. The work runs on a thread of its own, so the calls to the source clusters do not block the metadata
 * publishers.
 *
 * In this phase, a topic is mirrored if its configurations mirror.name and mirror.state=MIRRORING are set, and a
 * partition whose mirroring fails is retried only when its leader epoch changes.
 */
class MirrorMetadataManager(config: KafkaConfig,
                            replicaManager: ReplicaManager,
                            metrics: Metrics,
                            time: Time) extends MetadataPublisher with Logging {
  import MirrorMetadataManager._

  this.logIdent = s"[MirrorMetadataManager id=${config.brokerId}] "

  private val brokerConfig = {
    val props = new Properties()
    props.putAll(config.originals)
    new ClusterMirrorBrokerConfig(props)
  }
  private val mirrors = clusterMirrors(config)
  private val fetcherManagers: Map[String, MirrorFetcherManager] = mirrors.map { case (name, mirrorConfig) =>
    name -> new MirrorFetcherManager(config.brokerId, name, mirrorConfig, brokerConfig.numReplicaFetchers,
      replicaManager, metrics, time)
  }
  private val admins = mutable.Map[String, Admin]()
  private val mirrored = mutable.Map[String, mutable.Map[TopicPartition, MirroredPartition]]()

  // A permit is released on every metadata update; the thread also reconciles when none comes within the
  // refresh interval.
  private val updates = new Semaphore(0)

  private val thread = new ShutdownableThread(s"mirror-metadata-manager-${config.brokerId}", false) {
    override def doWork(): Unit = {
      if (updates.tryAcquire(brokerConfig.metadataRefreshIntervalMs, TimeUnit.MILLISECONDS))
        updates.drainPermits()
      if (isRunning)
        reconcile()
    }
  }

  def startup(): Unit = {
    if (mirrors.nonEmpty) {
      info(s"Starting with cluster mirrors ${mirrors.keys.mkString(", ")}")
      thread.start()
    }
  }

  override def name(): String = "MirrorMetadataManager"

  override def onMetadataUpdate(delta: MetadataDelta, newImage: MetadataImage, manifest: LoaderManifest): Unit = {
    if (mirrors.nonEmpty && (delta.topicsDelta() != null || delta.configsDelta() != null))
      signal()
  }

  private def signal(): Unit = updates.release()

  override def close(): Unit = {
    if (mirrors.nonEmpty) {
      thread.initiateShutdown()
      signal()
      thread.awaitShutdown()
    }
    fetcherManagers.values.foreach(_.shutdown())
    admins.values.foreach(admin => Utils.closeQuietly(admin, "mirror admin client"))
    admins.clear()
  }

  private[mirror] def reconcile(): Unit = {
    val partitionsByMirror = replicaManager.leaderPartitionsIterator.flatMap { partition =>
      partition.log.filter(_.config.isMirroring).map(log => log.config.mirrorName -> partition)
    }.toSeq.groupMap(_._1)(_._2)

    partitionsByMirror.keySet.diff(mirrors.keySet).foreach { name =>
      warn(s"Partitions ${partitionsByMirror(name).map(_.topicPartition).mkString(", ")} are mirrored from " +
        s"unknown cluster mirror $name")
    }

    fetcherManagers.foreachEntry { (name, manager) =>
      try {
        reconcileMirror(name, manager, partitionsByMirror.getOrElse(name, Seq.empty))
      } catch {
        case e: Throwable => warn(s"Failed to update the mirrored partitions of cluster mirror $name", e)
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
            val failed = manager.failedPartitions.contains(tp)
            if (failed && previous.exists(_.leaderEpoch == target.leaderEpoch)) {
              // The mirroring of the partition failed, for example on a source epoch higher than the local leader
              // epoch. It is retried when the partition gets a new leader epoch.
            } else if (!previous.contains(target) || failed) {
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

  private def adminFor(name: String): Admin = {
    admins.getOrElseUpdate(name, {
      val props = new Properties()
      mirrors(name).originals().forEach((key, value) => props.put(key, value))
      props.put(AdminClientConfig.CLIENT_ID_CONFIG, s"mirror-$name-broker-${config.brokerId}-admin")
      Admin.create(props)
    })
  }
}
