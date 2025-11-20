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
package kafka.coordinator.producer

import kafka.utils.Logging
import org.apache.kafka.common.utils.Time

import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledExecutorService, TimeUnit}
import scala.jdk.CollectionConverters._

/**
 * Information about a producer's liveness state
 */
case class ProducerState(
  producerId: String,
  clientId: String,
  timestamp: Long,
  version: String,
  host: String,
  state: Byte,
  metadata: Map[String, String]
)

/**
 * Tracks producer liveness based on heartbeat timestamps.
 * Runs a background thread to periodically clean up expired producers.
 */
class ProducerLivenessTracker(
  time: Time,
  sessionTimeoutMs: Long = 180000, // 3 minutes
  cleanupIntervalMs: Long = 300000  // 5 minutes
) extends Logging {

  // In-memory storage: producerId -> ProducerState
  private val producers = new ConcurrentHashMap[String, ProducerState]()

  // Scheduler for cleanup task
  private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

  // Start cleanup task
  scheduler.scheduleAtFixedRate(
    () => cleanupExpiredProducers(),
    cleanupIntervalMs,
    cleanupIntervalMs,
    TimeUnit.MILLISECONDS
  )

  /**
   * Record a heartbeat from a producer
   */
  def recordHeartbeat(
    producerId: String,
    clientId: String,
    timestamp: Long,
    version: String,
    host: String,
    state: Byte,
    metadata: Map[String, String]
  ): Unit = {
    val producerState = ProducerState(
      producerId = producerId,
      clientId = clientId,
      timestamp = timestamp,
      version = version,
      host = host,
      state = state,
      metadata = metadata
    )

    val previousState = producers.put(producerId, producerState)

    if (previousState == null) {
      info(s"New producer registered: $producerId (client: $clientId, host: $host)")
    } else {
      debug(s"Heartbeat received from producer: $producerId")
    }

    // Handle STOPPING state
    if (state == 1) { // STOPPING = 1
      info(s"Producer $producerId is stopping")
    }
  }

  /**
   * Check if a producer is alive
   */
  def isProducerAlive(producerId: String): Boolean = {
    val state = producers.get(producerId)
    if (state == null) {
      false
    } else {
      val age = time.milliseconds() - state.timestamp
      age < sessionTimeoutMs
    }
  }

  /**
   * Get producer state
   */
  def getProducerState(producerId: String): Option[ProducerState] = {
    Option(producers.get(producerId))
  }

  /**
   * Get all active producers
   */
  def getAllActiveProducers: Map[String, ProducerState] = {
    val now = time.milliseconds()
    producers.asScala
      .filter { case (_, state) =>
        (now - state.timestamp) < sessionTimeoutMs
      }
      .toMap
  }

  /**
   * Get count of active producers
   */
  def activeProducerCount: Int = {
    getAllActiveProducers.size
  }

  /**
   * Cleanup expired producers
   */
  private def cleanupExpiredProducers(): Unit = {
    try {
      val now = time.milliseconds()
      val expiredProducers = producers.asScala.filter { case (_, state) =>
        (now - state.timestamp) >= sessionTimeoutMs
      }

      expiredProducers.foreach { case (producerId, state) =>
        producers.remove(producerId)
        info(s"Producer $producerId expired (last heartbeat ${(now - state.timestamp) / 1000}s ago)")
      }

      if (expiredProducers.nonEmpty) {
        info(s"Cleaned up ${expiredProducers.size} expired producers. Active count: ${producers.size()}")
      }
    } catch {
      case e: Exception =>
        error("Error during producer cleanup", e)
    }
  }

  /**
   * Shutdown the tracker
   */
  def shutdown(): Unit = {
    info("Shutting down ProducerLivenessTracker")
    scheduler.shutdown()
    try {
      if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
        scheduler.shutdownNow()
      }
    } catch {
      case _: InterruptedException =>
        scheduler.shutdownNow()
    }
    producers.clear()
  }
}
