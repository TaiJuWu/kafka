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

import kafka.utils.Logging
import org.apache.kafka.common.internals.Topic
import org.apache.kafka.coordinator.common.runtime.{KRaftCoordinatorMetadataDelta, KRaftCoordinatorMetadataImage}
import org.apache.kafka.coordinator.mirror.MirrorCoordinatorService
import org.apache.kafka.image.{MetadataDelta, MetadataImage}
import org.apache.kafka.image.loader.LoaderManifest
import org.apache.kafka.image.publisher.MetadataPublisher
import org.apache.kafka.server.fault.FaultHandler

import java.util.OptionalInt

/**
 * Loads and unloads the shards of the cluster mirror coordinator when this broker gains or loses the leadership of
 * partitions of the __mirror_state topic, and passes the metadata updates to the coordinator.
 */
class MirrorCoordinatorPublisher(brokerId: Int,
                                 coordinator: MirrorCoordinatorService,
                                 faultHandler: FaultHandler) extends MetadataPublisher with Logging {

  override def name(): String = "MirrorCoordinatorPublisher"

  override def onMetadataUpdate(delta: MetadataDelta, newImage: MetadataImage, manifest: LoaderManifest): Unit = {
    try {
      Option(delta.topicsDelta()).foreach { topicsDelta =>
        if (topicsDelta.topicWasDeleted(Topic.MIRROR_STATE_TOPIC_NAME)) {
          topicsDelta.image.getTopic(Topic.MIRROR_STATE_TOPIC_NAME).partitions.entrySet.forEach { entry =>
            if (entry.getValue.leader == brokerId)
              coordinator.onResignation(entry.getKey, OptionalInt.empty())
          }
        }
        Option(newImage.topics().getTopic(Topic.MIRROR_STATE_TOPIC_NAME))
          .flatMap(topic => Option(topicsDelta.changedTopic(topic.id())))
          .foreach { topicDelta =>
            val changes = topicDelta.localChanges(brokerId)
            changes.deletes.forEach(tp => coordinator.onResignation(tp.partition, OptionalInt.empty()))
            changes.electedLeaders.forEach((tp, info) => coordinator.onElection(tp.partition, info.partition.leaderEpoch))
            changes.followers.forEach((tp, info) => coordinator.onResignation(tp.partition, OptionalInt.of(info.partition.leaderEpoch)))
          }
      }
      coordinator.onMetadataUpdate(new KRaftCoordinatorMetadataDelta(delta), new KRaftCoordinatorMetadataImage(newImage))
    } catch {
      case t: Throwable => faultHandler.handleFault("Error updating the cluster mirror coordinator", t)
    }
  }
}
