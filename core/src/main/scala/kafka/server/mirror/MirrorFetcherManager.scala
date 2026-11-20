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

import kafka.server.{AbstractFetcherManager, ReplicaManager}
import org.apache.kafka.clients.FetchSessionHandler
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.utils.Time
import org.apache.kafka.common.utils.internals.LogContext
import org.apache.kafka.coordinator.mirror.ClusterMirrorConfig
import org.apache.kafka.server.network.BrokerEndPoint

/**
 * Manages the fetcher threads of one cluster mirror on this broker. The threads are keyed by the source broker and
 * the fetcher id, so the partitions of a mirror are spread over several threads, and the mirrors do not share
 * connections, as they may use different credentials.
 */
class MirrorFetcherManager(brokerId: Int,
                           val mirrorName: String,
                           val mirrorConfig: ClusterMirrorConfig,
                           numFetchers: Int,
                           replicaManager: ReplicaManager,
                           epochBumper: MirrorLeaderEpochBumper,
                           metrics: Metrics,
                           time: Time)
  extends AbstractFetcherManager[MirrorFetcherThread](
    name = s"MirrorFetcherManager on broker $brokerId for mirror $mirrorName",
    clientId = s"mirror-$mirrorName",
    numFetchers = numFetchers) {

  override def createFetcherThread(fetcherId: Int, sourceBroker: BrokerEndPoint): MirrorFetcherThread = {
    val threadName = s"MirrorFetcherThread-$mirrorName-$fetcherId-${sourceBroker.id}"
    // The client id tells the source cluster which destination broker, fetcher and mirror the fetches come from,
    // so client quotas can be applied to them.
    val clientId = s"mirror-$mirrorName-broker-$brokerId-fetcher-$fetcherId"
    val logContext = new LogContext(s"[MirrorFetcher mirror=$mirrorName replicaId=$brokerId leaderId=${sourceBroker.id}, fetcherId=$fetcherId] ")
    val sender = new MirrorBlockingSender(sourceBroker, mirrorName, mirrorConfig, metrics, time, fetcherId, clientId, logContext)
    val leader = new MirrorLeaderEndPoint(logContext.logPrefix, sender, new FetchSessionHandler(logContext, sourceBroker.id),
      mirrorConfig, replicaManager)
    new MirrorFetcherThread(threadName, leader, mirrorConfig.fetchBackoffMs.toInt, failedPartitions, replicaManager,
      epochBumper, logContext.logPrefix)
  }

  def shutdown(): Unit = {
    info("shutting down")
    closeAllFetchers()
    info("shutdown completed")
  }
}
