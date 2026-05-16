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

package kafka.server

import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.message.DeleteRecordsRequestData
import org.apache.kafka.common.message.DeleteRecordsRequestData.{DeleteRecordsPartition, DeleteRecordsTopic}
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.requests.{DeleteRecordsRequest, DeleteRecordsResponse}
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test

import java.util.Collections
import java.util.concurrent.TimeUnit
import scala.collection.Seq

/**
 * Compares end-to-end DeleteRecords latency between acks=-1 (wait for all alive replicas)
 * and acks=1 (leader-only) on a 3-broker cluster.
 *
 * The two paths are exercised on two separate topics to keep their state independent.
 * Each iteration trims to a strictly larger offset so every call does real work.
 *
 * The test prints a summary table and asserts that the mean latency of acks=1 is not
 * higher than acks=-1. It does not assert per-iteration latency to avoid flakiness.
 */
class DeleteRecordsAcksBenchmarkTest extends BaseRequestTest {
  private val Iterations = 100
  private val WarmupIterations = 10
  private val MessagesProducedPerTopic = Iterations + WarmupIterations + 50
  private val TimeoutMs = 30000

  @Test
  def benchmarkLeaderOnlyVsAllReplicas(): Unit = {
    val topicAllAcks = "delete-records-acks-all"
    val topicLeaderAcks = "delete-records-acks-leader"

    val partitionToLeaderAll = createTopic(topicAllAcks, numPartitions = 1, replicationFactor = 3)
    val partitionToLeaderLeader = createTopic(topicLeaderAcks, numPartitions = 1, replicationFactor = 3)

    val tpAll = new TopicPartition(topicAllAcks, 0)
    val tpLeader = new TopicPartition(topicLeaderAcks, 0)
    val leaderAll = partitionToLeaderAll(tpAll.partition)
    val leaderLeader = partitionToLeaderLeader(tpLeader.partition)

    produceData(Seq(tpAll, tpLeader), MessagesProducedPerTopic)

    // Warm up to amortize JIT, connection, and metadata caching.
    var nextOffsetAll = 1
    var nextOffsetLeader = 1
    for (_ <- 0 until WarmupIterations) {
      runOneTrim(tpAll, nextOffsetAll, acks = -1, leaderAll); nextOffsetAll += 1
      runOneTrim(tpLeader, nextOffsetLeader, acks = 1, leaderLeader); nextOffsetLeader += 1
    }

    // Measure.
    val acksAllDurationsNanos = (0 until Iterations).map { _ =>
      val nanos = timed(runOneTrim(tpAll, nextOffsetAll, acks = -1, leaderAll))
      nextOffsetAll += 1
      nanos
    }

    val acksLeaderDurationsNanos = (0 until Iterations).map { _ =>
      val nanos = timed(runOneTrim(tpLeader, nextOffsetLeader, acks = 1, leaderLeader))
      nextOffsetLeader += 1
      nanos
    }

    println()
    println(s"DeleteRecords latency comparison ($Iterations iterations, 3-broker cluster, replication=3)")
    println("=" * 100)
    printStats("acks=-1 (wait for all alive replicas)", acksAllDurationsNanos)
    printStats("acks=1  (leader-only)                ", acksLeaderDurationsNanos)
    println("=" * 100)

    val meanAll = mean(acksAllDurationsNanos)
    val meanLeader = mean(acksLeaderDurationsNanos)
    val speedupPct = 100.0 * (meanAll - meanLeader) / meanAll
    println(f"Speedup: acks=1 mean is $speedupPct%.1f%% faster than acks=-1")
    println()

    assertTrue(meanLeader <= meanAll,
      s"Expected acks=1 mean ($meanLeader ns) to be <= acks=-1 mean ($meanAll ns)")
  }

  private def timed(block: => Unit): Long = {
    val start = System.nanoTime()
    block
    System.nanoTime() - start
  }

  private def runOneTrim(tp: TopicPartition, offset: Long, acks: Short, leaderId: Int): Unit = {
    val data = new DeleteRecordsRequestData()
      .setTopics(Collections.singletonList(new DeleteRecordsTopic()
        .setName(tp.topic)
        .setPartitions(Collections.singletonList(new DeleteRecordsPartition()
          .setOffset(offset)
          .setPartitionIndex(tp.partition)))))
      .setTimeoutMs(TimeoutMs)
      .setAcks(acks)
    val request = new DeleteRecordsRequest.Builder(data).build()
    val response = connectAndReceive[DeleteRecordsResponse](request, destination = brokerSocketServer(leaderId))
    val partitionResult = response.data.topics.find(tp.topic).partitions.find(tp.partition)
    assertEquals(Errors.NONE.code(), partitionResult.errorCode(),
      s"Unexpected error: ${Errors.forCode(partitionResult.errorCode).name()}")
  }

  private def printStats(name: String, durationsNanos: Seq[Long]): Unit = {
    val sorted = durationsNanos.sorted
    val n = sorted.length
    val toMs = (ns: Long) => ns / 1_000_000.0
    val p50 = toMs(sorted(n / 2))
    val p95 = toMs(sorted(math.min(n - 1, (n * 0.95).toInt)))
    val p99 = toMs(sorted(math.min(n - 1, (n * 0.99).toInt)))
    val meanMs = mean(durationsNanos) / 1_000_000.0
    val minMs = toMs(sorted.head)
    val maxMs = toMs(sorted.last)
    println(f"$name | mean=$meanMs%6.2fms p50=$p50%6.2fms p95=$p95%6.2fms p99=$p99%6.2fms min=$minMs%6.2fms max=$maxMs%6.2fms")
  }

  private def mean(durations: Seq[Long]): Double = durations.sum / durations.length.toDouble

  private def produceData(topicPartitions: Iterable[TopicPartition], numMessagesPerPartition: Int): Seq[RecordMetadata] = {
    val producer = createProducer(keySerializer = new StringSerializer, valueSerializer = new StringSerializer)
    try {
      val records = for {
        tp <- topicPartitions.toSeq
        messageIndex <- 0 until numMessagesPerPartition
      } yield new ProducerRecord(tp.topic, tp.partition, s"key-$messageIndex", s"value-$messageIndex")

      val futures = records.map(producer.send)
      producer.flush()
      val metadata = futures.map(_.get(30, TimeUnit.SECONDS))
      metadata.foreach(m => assertTrue(m.offset >= 0, s"Invalid offset $m"))
      metadata
    } finally {
      producer.close()
    }
  }
}
