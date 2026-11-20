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

import java.net.SocketTimeoutException

import kafka.server.BlockingSend
import org.apache.kafka.clients.{ApiVersions, ClientResponse, ClientUtils, ManualMetadataUpdater, MetadataRecoveryStrategy, NetworkClient, NetworkClientUtils, BootstrapConfiguration}
import org.apache.kafka.common.Node
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.metrics.internals.MetricsUtils
import org.apache.kafka.common.network.{NetworkReceive, Selectable, Selector}
import org.apache.kafka.common.requests.AbstractRequest
import org.apache.kafka.common.utils.Time
import org.apache.kafka.common.utils.internals.LogContext
import org.apache.kafka.coordinator.mirror.ClusterMirrorConfig
import org.apache.kafka.server.network.BrokerEndPoint

/**
 * Sends requests to a broker of the source cluster of a cluster mirror, with the connection settings of the mirror,
 * such as its security protocol and credentials, rather than the inter-broker settings of the destination cluster.
 */
class MirrorBlockingSender(sourceBroker: BrokerEndPoint,
                           mirrorName: String,
                           mirrorConfig: ClusterMirrorConfig,
                           metrics: Metrics,
                           time: Time,
                           fetcherId: Int,
                           clientId: String,
                           logContext: LogContext) extends BlockingSend {

  private val sourceNode = new Node(sourceBroker.id, sourceBroker.host, sourceBroker.port)
  private val socketTimeout = mirrorConfig.socketTimeoutMs

  private val networkClient = {
    val channelBuilder = ClientUtils.createChannelBuilder(mirrorConfig, time, logContext)
    val selector = new Selector(
      NetworkReceive.UNLIMITED,
      mirrorConfig.connectionsMaxIdleMs,
      metrics,
      time,
      "mirror-fetcher",
      MetricsUtils.getTags("mirror-name", mirrorName, "source-broker-id", sourceBroker.id.toString,
        "fetcher-id", fetcherId.toString),
      false,
      channelBuilder,
      logContext
    )
    new NetworkClient(
      selector,
      new ManualMetadataUpdater(),
      clientId,
      1,
      0,
      0,
      Selectable.USE_DEFAULT_BUFFER_SIZE,
      mirrorConfig.socketReceiveBufferBytes,
      mirrorConfig.requestTimeoutMs,
      mirrorConfig.connectionSetupTimeoutMs,
      mirrorConfig.connectionSetupTimeoutMaxMs,
      time,
      true,
      new ApiVersions,
      logContext,
      MetadataRecoveryStrategy.NONE,
      BootstrapConfiguration.DISABLED,
      false
    )
  }

  override def brokerEndPoint(): BrokerEndPoint = sourceBroker

  override def sendRequest(requestBuilder: AbstractRequest.Builder[_ <: AbstractRequest]): ClientResponse = {
    try {
      if (!NetworkClientUtils.awaitReady(networkClient, sourceNode, time, socketTimeout))
        throw new SocketTimeoutException(s"Failed to connect to source broker $sourceBroker within $socketTimeout ms")
      val clientRequest = networkClient.newClientRequest(sourceBroker.id.toString, requestBuilder,
        time.milliseconds(), true)
      NetworkClientUtils.sendAndReceive(networkClient, clientRequest, time)
    } catch {
      case e: Throwable =>
        networkClient.close(sourceBroker.id.toString)
        throw e
    }
  }

  override def initiateClose(): Unit = networkClient.initiateClose()

  override def close(): Unit = networkClient.close()

  override def toString: String = s"MirrorBlockingSender(mirror=$mirrorName, sourceBroker=$sourceBroker, fetcherId=$fetcherId)"
}
