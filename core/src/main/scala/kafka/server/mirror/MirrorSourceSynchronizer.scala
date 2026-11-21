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
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

import kafka.server.{KafkaConfig, ReplicaManager}
import kafka.utils.Logging
import org.apache.kafka.clients.admin.{Admin, AdminClientConfig, AlterConfigOp, ConfigEntry, ListGroupsOptions, ListShareGroupOffsetsSpec, ListTopicsOptions, NewPartitions, OffsetSpec}
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.acl.AclBindingFilter
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.internals.Topic
import org.apache.kafka.common.utils.{Time, Utils}
import org.apache.kafka.coordinator.mirror.ClusterMirrorConfig
import org.apache.kafka.server.mirror.MirrorPartitionState

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/**
 * Synchronizes the metadata of the source cluster of a cluster mirror to the destination cluster (KIP-1279): topic
 * discovery and exclusion, partition counts, topic configurations, consumer and share group offsets, and ACLs. For
 * each cluster mirror, it runs on one broker only: the leader of the __mirror_state partition that the mirror name
 * hashes to, every mirror.metadata.refresh.interval.ms.
 *
 * It runs on the thread of the MirrorMetadataManager, so the operations which the brokers handle on that thread, such
 * as StartMirrorTopics, are sent without waiting for their results.
 */
class MirrorSourceSynchronizer(manager: MirrorMetadataManager,
                               replicaManager: ReplicaManager,
                               config: KafkaConfig,
                               time: Time) extends Logging {
  this.logIdent = s"[MirrorSourceSynchronizer id=${config.brokerId}] "

  private val lastSyncMs = mutable.Map[String, Long]()
  private var destinationAdmin: Option[Admin] = None
  private def timeoutMs: Int = config.requestTimeoutMs

  def close(): Unit = {
    destinationAdmin.foreach(admin => Utils.closeQuietly(admin, "mirror destination admin client"))
    destinationAdmin = None
  }

  /**
   * @return true if this broker synchronizes the given cluster mirror
   */
  private[mirror] def isSyncCoordinator(name: String): Boolean = {
    replicaManager.metadataCache.numPartitions(Topic.MIRROR_STATE_TOPIC_NAME).toScala.exists { numPartitions =>
      val partition = Utils.abs(name.hashCode) % numPartitions
      replicaManager.metadataCache.getPartitionLeaderEndpoint(Topic.MIRROR_STATE_TOPIC_NAME, partition,
        config.interBrokerListenerName).toScala.exists(_.id == config.brokerId)
    }
  }

  def maybeSync(name: String, mirrorConfig: ClusterMirrorConfig, intervalMs: Long): Unit = {
    if (!isSyncCoordinator(name))
      return
    val now = time.milliseconds
    if (now - lastSyncMs.getOrElse(name, 0L) < intervalMs)
      return
    lastSyncMs(name) = now
    try {
      sync(name, mirrorConfig)
    } catch {
      case e: Throwable => warn(s"Failed to synchronize the metadata of cluster mirror $name", e)
    }
  }

  private def destination(): Admin = destinationAdmin.getOrElse {
    val node = replicaManager.metadataCache.getAliveBrokerNode(config.brokerId, config.interBrokerListenerName).toScala
      .getOrElse(throw new IllegalStateException("This broker is not registered yet"))
    val props = new Properties()
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, s"${node.host}:${node.port}")
    props.put(AdminClientConfig.SECURITY_PROTOCOL_CONFIG, config.interBrokerSecurityProtocol.name)
    props.put(AdminClientConfig.CLIENT_ID_CONFIG, s"mirror-sync-broker-${config.brokerId}")
    val admin = Admin.create(props)
    destinationAdmin = Some(admin)
    admin
  }

  private def matches(pattern: com.google.re2j.Pattern, value: String): Boolean =
    pattern != null && pattern.matcher(value).matches()

  private def sync(name: String, mirrorConfig: ClusterMirrorConfig): Unit = {
    val source = manager.adminFor(name)
    val expectedClusterId = mirrorConfig.originals.get(ClusterMirrorConfig.SOURCE_CLUSTER_ID_CONFIG)
    val clusterId = source.describeCluster().clusterId.get(timeoutMs, TimeUnit.MILLISECONDS)
    if (expectedClusterId != null && expectedClusterId.toString != clusterId) {
      error(s"The source cluster of cluster mirror $name has id $clusterId instead of $expectedClusterId, so its " +
        "metadata is not synchronized")
      return
    }

    val image = manager.image
    val allMirrorTopics = image.clusterMirrors.topics.asScala
    val mirrorTopics = allMirrorTopics.collect {
      case (topic, mirrorTopic) if mirrorTopic.mirrorName == name => topic -> MirrorPartitionState.fromValue(mirrorTopic.desiredState)
    }.toMap
    val sourceTopics = source.listTopics(new ListTopicsOptions().timeoutMs(timeoutMs)).names
      .get(timeoutMs, TimeUnit.MILLISECONDS).asScala.toSet

    syncTopicDiscovery(name, mirrorConfig, mirrorTopics, allMirrorTopics.keySet.toSet, sourceTopics)
    val syncedTopics = mirrorTopics.keySet.intersect(sourceTopics).filter(t => image.topics.getTopic(t) != null)
    if (syncedTopics.nonEmpty) {
      syncPartitions(source, syncedTopics)
      syncTopicConfigs(source, mirrorConfig, syncedTopics)
      val offsets = destinationOffsets(syncedTopics)
      syncConsumerGroupOffsets(source, mirrorConfig, syncedTopics, offsets)
      syncShareGroupOffsets(source, mirrorConfig, syncedTopics, offsets)
    }
    syncAcls(source, mirrorConfig)
  }

  /**
   * Starts the mirroring of the new source topics which match topics.include and not topics.exclude, once the mirror
   * has topics, and stops the mirroring of the topics which now match topics.exclude.
   */
  private def syncTopicDiscovery(name: String, mirrorConfig: ClusterMirrorConfig, mirrorTopics: Map[String, MirrorPartitionState],
                                 topicsInAnyMirror: Set[String], sourceTopics: Set[String]): Unit = {
    val include = mirrorConfig.topicsIncludePattern
    val exclude = mirrorConfig.topicsExcludePattern
    if (mirrorTopics.nonEmpty) {
      val discovered = sourceTopics.filter(t => matches(include, t) && !matches(exclude, t) && !topicsInAnyMirror.contains(t))
      if (discovered.nonEmpty) {
        info(s"Discovered source topics ${discovered.mkString(", ")} for cluster mirror $name")
        destination().startMirrorTopics(name, discovered.toSeq.map(Pattern.quote).asJava).all.whenComplete { (_, e) =>
          if (e != null) warn(s"Failed to start the mirroring of the discovered topics ${discovered.mkString(", ")}", e)
        }
      }
    }
    val excluded = mirrorTopics.collect { case (topic, state) if state == MirrorPartitionState.MIRRORING && matches(exclude, topic) => topic }
    if (excluded.nonEmpty) {
      info(s"Stopping the mirroring of the excluded topics ${excluded.mkString(", ")} of cluster mirror $name")
      destination().stopMirrorTopics(name, excluded.toSeq.map(Pattern.quote).asJava).all.whenComplete { (_, e) =>
        if (e != null) warn(s"Failed to stop the mirroring of the excluded topics ${excluded.mkString(", ")}", e)
      }
    }
  }

  /**
   * Adds the partitions which the source topics gained.
   */
  private def syncPartitions(source: Admin, topics: Set[String]): Unit = {
    val sourceCounts = source.describeTopics(topics.asJava).allTopicNames.get(timeoutMs, TimeUnit.MILLISECONDS).asScala
      .map { case (topic, description) => topic -> description.partitions.size }
    val increases = sourceCounts.collect {
      case (topic, count) if count > manager.image.topics.getTopic(topic).partitions.size => topic -> NewPartitions.increaseTo(count)
    }
    if (increases.nonEmpty) {
      info(s"Adding partitions to mirror topics ${increases.keys.mkString(", ")}")
      destination().createPartitions(increases.asJava).all.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
  }

  /**
   * Applies the dynamic configurations of the source topics to the mirror topics, except those which match
   * topic.properties.exclude.
   */
  private def syncTopicConfigs(source: Admin, mirrorConfig: ClusterMirrorConfig, topics: Set[String]): Unit = {
    val exclude = mirrorConfig.topicPropertiesExcludePattern
    def dynamicConfigs(admin: Admin): Map[String, Map[String, String]] = {
      val resources = topics.map(t => new ConfigResource(ConfigResource.Type.TOPIC, t))
      admin.describeConfigs(resources.asJava).all.get(timeoutMs, TimeUnit.MILLISECONDS).asScala.map { case (resource, configs) =>
        resource.name -> configs.entries.asScala
          .filter(entry => entry.source == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG && !matches(exclude, entry.name))
          .map(entry => entry.name -> entry.value).toMap
      }.toMap
    }
    val desired = dynamicConfigs(source)
    val current = dynamicConfigs(destination())
    val ops = topics.flatMap { topic =>
      val wanted = desired.getOrElse(topic, Map.empty)
      val existing = current.getOrElse(topic, Map.empty)
      val changes = wanted.collect { case (k, v) if !existing.get(k).contains(v) =>
        new AlterConfigOp(new ConfigEntry(k, v), AlterConfigOp.OpType.SET) } ++
        existing.keySet.diff(wanted.keySet).map(k => new AlterConfigOp(new ConfigEntry(k, null), AlterConfigOp.OpType.DELETE))
      if (changes.isEmpty) None else Some(new ConfigResource(ConfigResource.Type.TOPIC, topic) -> changes.toSeq.asJavaCollection)
    }.toMap
    if (ops.nonEmpty) {
      info(s"Synchronizing the configurations of mirror topics ${ops.keys.map(_.name).mkString(", ")}")
      destination().incrementalAlterConfigs(ops.asJava).all.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
  }

  /**
   * @return the log start and end offsets of the mirror partitions, resolved once per cycle for all the groups
   */
  private def destinationOffsets(topics: Set[String]): Map[TopicPartition, (Long, Long)] = {
    val partitions = topics.toSeq.flatMap { topic =>
      manager.image.topics.getTopic(topic).partitions.keySet.asScala.map(p => new TopicPartition(topic, p.intValue))
    }
    val admin = destination()
    val earliest = admin.listOffsets(partitions.map(_ -> OffsetSpec.earliest()).toMap.asJava).all.get(timeoutMs, TimeUnit.MILLISECONDS)
    val latest = admin.listOffsets(partitions.map(_ -> OffsetSpec.latest()).toMap.asJava).all.get(timeoutMs, TimeUnit.MILLISECONDS)
    partitions.map(tp => tp -> (earliest.get(tp).offset, latest.get(tp).offset)).toMap
  }

  /**
   * Clamps a source offset to the destination partition: max(destination log start, min(destination LEO, source)).
   */
  private def clamp(offsets: Map[TopicPartition, (Long, Long)], tp: TopicPartition, offset: Long): Option[Long] =
    offsets.get(tp).map { case (start, end) => math.max(start, math.min(end, offset)) }

  private def groupMatches(mirrorConfig: ClusterMirrorConfig, group: String): Boolean =
    matches(mirrorConfig.groupsIncludePattern, group) && !matches(mirrorConfig.groupsExcludePattern, group)

  /**
   * Commits the offsets of the source consumer groups for the mirror partitions. The commits of the groups with active
   * members are rejected by the group coordinator, as the consumers own their offsets once they run on the destination.
   */
  private def syncConsumerGroupOffsets(source: Admin, mirrorConfig: ClusterMirrorConfig, topics: Set[String],
                                       offsets: Map[TopicPartition, (Long, Long)]): Unit = {
    val groups = source.listGroups(ListGroupsOptions.forConsumerGroups()).all.get(timeoutMs, TimeUnit.MILLISECONDS).asScala
      .map(_.groupId).filter(groupMatches(mirrorConfig, _))
    groups.foreach { group =>
      try {
        val committed = source.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata(group).get(timeoutMs, TimeUnit.MILLISECONDS).asScala
        val clamped = committed.collect { case (tp, offset) if offset != null && topics.contains(tp.topic) =>
          clamp(offsets, tp, offset.offset).map(o => tp -> new OffsetAndMetadata(o, offset.metadata))
        }.flatten.toMap
        if (clamped.nonEmpty)
          destination().alterConsumerGroupOffsets(group, clamped.asJava).all.get(timeoutMs, TimeUnit.MILLISECONDS)
      } catch {
        case e: Throwable => debug(s"Did not synchronize the offsets of consumer group $group: ${e.getMessage}")
      }
    }
  }

  /**
   * Applies the share-partition start offsets of the source share groups to the mirror partitions, which also
   * initializes the share groups on the destination.
   */
  private def syncShareGroupOffsets(source: Admin, mirrorConfig: ClusterMirrorConfig, topics: Set[String],
                                    offsets: Map[TopicPartition, (Long, Long)]): Unit = {
    val groups = try {
      source.listGroups(ListGroupsOptions.forShareGroups()).all.get(timeoutMs, TimeUnit.MILLISECONDS).asScala
        .map(_.groupId).filter(groupMatches(mirrorConfig, _))
    } catch {
      case e: Throwable =>
        debug(s"Did not list the share groups of the source cluster: ${e.getMessage}")
        Seq.empty
    }
    groups.foreach { group =>
      try {
        val startOffsets = source.listShareGroupOffsets(Map(group -> new ListShareGroupOffsetsSpec()).asJava)
          .partitionsToOffsetInfo(group).get(timeoutMs, TimeUnit.MILLISECONDS).asScala
        val clamped = startOffsets.collect { case (tp, info) if info != null && info.startOffset >= 0 && topics.contains(tp.topic) =>
          clamp(offsets, tp, info.startOffset).map(o => tp -> java.lang.Long.valueOf(o))
        }.flatten.toMap
        if (clamped.nonEmpty)
          destination().alterShareGroupOffsets(group, clamped.asJava).all.get(timeoutMs, TimeUnit.MILLISECONDS)
      } catch {
        case e: Throwable => debug(s"Did not synchronize the offsets of share group $group: ${e.getMessage}")
      }
    }
  }

  /**
   * Creates the ACLs of the source cluster which match acls.include and not acls.exclude, and do not exist on the
   * destination cluster.
   */
  private def syncAcls(source: Admin, mirrorConfig: ClusterMirrorConfig): Unit = {
    val sourceAcls = try {
      source.describeAcls(AclBindingFilter.ANY).values.get(timeoutMs, TimeUnit.MILLISECONDS).asScala
    } catch {
      case e: Throwable =>
        debug(s"Did not describe the ACLs of the source cluster: ${e.getMessage}")
        return
    }
    val wanted = sourceAcls.filter(binding => mirrorConfig.aclIncludeRules.asScala.exists(_.matches(binding)) &&
      !mirrorConfig.aclExcludeRules.asScala.exists(_.matches(binding))).toSet
    if (wanted.isEmpty)
      return
    val existing = destination().describeAcls(AclBindingFilter.ANY).values.get(timeoutMs, TimeUnit.MILLISECONDS).asScala.toSet
    val missing = wanted.diff(existing)
    if (missing.nonEmpty) {
      info(s"Creating ${missing.size} ACLs from the source cluster")
      destination().createAcls(missing.asJavaCollection).all.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
  }
}
