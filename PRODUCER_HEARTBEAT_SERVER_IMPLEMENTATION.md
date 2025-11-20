# Producer Heartbeat Server 端實作設計

## 概述

本文檔詳細說明 Producer Heartbeat 在 Kafka Broker 端的實作細節，包括：
1. TransactionCoordinator 擴展
2. HeartbeatRequestHandler 實作
3. Session Timeout Checker 實作
4. 與 `__transaction_state` topic 的整合
5. Coordinator Failover 處理

## 架構概覽

```
┌─────────────────────────────────────────────────────────────────┐
│ KafkaApis (Request Router)                                     │
│                                                                 │
│  case ApiKeys.PRODUCER_HEARTBEAT =>                            │
│    handleProducerHeartbeatRequest(request)                     │
└─────────────────────┬───────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│ TransactionCoordinator                                          │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │ handleProducerHeartbeat()                                 │ │
│  │  1. 驗證 producerId + epoch                               │ │
│  │  2. 查找 transaction metadata                             │ │
│  │  3. 更新 heartbeat state                                  │ │
│  │  4. 返回 heartbeatIntervalMs                              │ │
│  └───────────────────┬───────────────────────────────────────┘ │
│                      │                                          │
│  ┌───────────────────▼───────────────────────────────────────┐ │
│  │ TransactionStateManager                                   │ │
│  │  - 管理 __transaction_state topic                         │ │
│  │  - 查找 transactionalId -> TransactionMetadata            │ │
│  │  - 查找 producerId -> TransactionMetadata (反向索引)      │ │
│  └───────────────────┬───────────────────────────────────────┘ │
│                      │                                          │
│  ┌───────────────────▼───────────────────────────────────────┐ │
│  │ ProducerHeartbeatTracker (新增)                           │ │
│  │  Map<ProducerId, HeartbeatState>                          │ │
│  │    - lastHeartbeatMs                                      │ │
│  │    - sessionTimeoutMs                                     │ │
│  │    - transactionalId                                      │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │ SessionTimeoutChecker (新增 - Scheduler task)             │ │
│  │  - 定期掃描 heartbeat state                               │ │
│  │  - 檢查 session timeout                                   │ │
│  │  - Abort pending transactions                             │ │
│  └───────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

## 1. KafkaApis 擴展

### 1.1 添加新的 API handler

```scala
// core/src/main/scala/kafka/server/KafkaApis.scala

def handle(request: RequestChannel.Request, requestLocal: RequestLocal): Unit = {
  request.header.apiKey match {
    // ... 現有的 case statements ...

    case ApiKeys.PRODUCER_HEARTBEAT =>
      handleProducerHeartbeatRequest(request, requestLocal)

    // ... 其他 cases ...
  }
}

def handleProducerHeartbeatRequest(
    request: RequestChannel.Request,
    requestLocal: RequestLocal): Unit = {

  val producerHeartbeatRequest = request.body[ProducerHeartbeatRequest]
  val producerId = producerHeartbeatRequest.data.producerId
  val producerEpoch = producerHeartbeatRequest.data.producerEpoch

  // Authorization check
  if (!authorize(request.context, CLUSTER_ACTION, CLUSTER, CLUSTER_NAME)) {
    sendErrorResponseMaybeThrottle(request,
      Errors.CLUSTER_AUTHORIZATION_FAILED.exception)
    return
  }

  def sendResponseCallback(response: ProducerHeartbeatResponse): Unit = {
    trace(s"Sending producer heartbeat response $response for producer " +
      s"$producerId with epoch $producerEpoch")
    sendResponseMaybeThrottle(request, requestThrottleMs =>
      new ProducerHeartbeatResponse(response.data.setThrottleTimeMs(requestThrottleMs)))
  }

  // 委託給 TransactionCoordinator 處理
  transactionCoordinator.handleProducerHeartbeat(
    producerId,
    producerEpoch,
    sendResponseCallback
  )
}
```

### 1.2 Throttling 和 Metrics

```scala
// Throttle configuration
// 繼承現有的 transaction coordinator throttle quota
val producerHeartbeatQuota = quotas.fetch

// Metrics
metrics.addMetric("producer-heartbeat-request-rate", ...)
metrics.addMetric("producer-heartbeat-error-rate", ...)
```

## 2. TransactionCoordinator 擴展

### 2.1 新增 ProducerHeartbeatTracker

```scala
// core/src/main/scala/kafka/coordinator/transaction/ProducerHeartbeatTracker.scala

package kafka.coordinator.transaction

import java.util.concurrent.ConcurrentHashMap
import org.apache.kafka.common.utils.Time
import scala.jdk.CollectionConverters._

/**
 * 追蹤 transactional producers 的 heartbeat 狀態
 *
 * Thread-safe: 使用 ConcurrentHashMap
 */
class ProducerHeartbeatTracker(time: Time, config: TransactionConfig) {

  // ProducerId -> HeartbeatState
  private val heartbeats = new ConcurrentHashMap[Long, HeartbeatState]()

  case class HeartbeatState(
    var lastHeartbeatMs: Long,
    var sessionTimeoutMs: Long,
    var transactionalId: String
  )

  /**
   * 更新或創建 heartbeat 狀態
   */
  def updateHeartbeat(
      producerId: Long,
      transactionalId: String,
      sessionTimeoutMs: Long): Unit = {

    val currentTimeMs = time.milliseconds()

    heartbeats.compute(producerId, (_, existingState) => {
      if (existingState == null) {
        HeartbeatState(currentTimeMs, sessionTimeoutMs, transactionalId)
      } else {
        existingState.lastHeartbeatMs = currentTimeMs
        existingState.sessionTimeoutMs = sessionTimeoutMs
        existingState
      }
    })
  }

  /**
   * 移除 heartbeat 狀態
   */
  def removeHeartbeat(producerId: Long): Unit = {
    heartbeats.remove(producerId)
  }

  /**
   * 獲取所有超時的 producers
   *
   * @return Map of producerId -> (transactionalId, elapsedMs)
   */
  def getExpiredProducers(): Map[Long, (String, Long)] = {
    val currentTimeMs = time.milliseconds()

    heartbeats.asScala
      .filter { case (producerId, state) =>
        val elapsedMs = currentTimeMs - state.lastHeartbeatMs
        elapsedMs > state.sessionTimeoutMs
      }
      .map { case (producerId, state) =>
        val elapsedMs = currentTimeMs - state.lastHeartbeatMs
        producerId -> (state.transactionalId, elapsedMs)
      }
      .toMap
  }

  /**
   * 計算 heartbeat interval (session timeout 的 1/3)
   */
  def calculateHeartbeatInterval(sessionTimeoutMs: Long): Int = {
    (sessionTimeoutMs / 3).toInt
  }

  /**
   * 清理所有 heartbeat 狀態 (用於測試或 coordinator shutdown)
   */
  def clear(): Unit = {
    heartbeats.clear()
  }

  /**
   * 獲取當前追蹤的 producer 數量
   */
  def size: Int = heartbeats.size()
}
```

### 2.2 TransactionCoordinator 擴展

```scala
// core/src/main/scala/kafka/coordinator/transaction/TransactionCoordinator.scala

class TransactionCoordinator(
    txnConfig: TransactionConfig,
    scheduler: Scheduler,
    createProducerIdManager: () => ProducerIdManager,
    txnManager: TransactionStateManager,
    txnMarkerChannelManager: TransactionMarkerChannelManager,
    time: Time,
    logContext: LogContext) extends Logging {

  // 新增: Producer Heartbeat Tracker
  private val heartbeatTracker = new ProducerHeartbeatTracker(time, txnConfig)

  // 新增: Session Timeout Checker (定期執行)
  private val sessionTimeoutCheckerTask = scheduler.schedule(
    name = "producer-session-timeout-checker",
    fun = checkSessionTimeouts _,
    period = txnConfig.producerHeartbeatTimeoutCheckIntervalMs,
    delay = txnConfig.producerHeartbeatTimeoutCheckIntervalMs,
    unit = java.util.concurrent.TimeUnit.MILLISECONDS
  )

  /**
   * 處理 Producer Heartbeat Request
   */
  def handleProducerHeartbeat(
      producerId: Long,
      producerEpoch: Short,
      responseCallback: ProducerHeartbeatResponse => Unit): Unit = {

    if (!isActive.get()) {
      responseCallback(heartbeatError(Errors.COORDINATOR_NOT_AVAILABLE))
      return
    }

    // 從 TransactionStateManager 查找 producer 的 transaction metadata
    // 使用 producerId 反向查找 (需要新增索引)
    val txnMetadataOpt = txnManager.getTransactionMetadataByProducerId(producerId)

    txnMetadataOpt match {
      case None =>
        // Producer ID 不存在
        responseCallback(heartbeatError(Errors.INVALID_PRODUCER_ID_MAPPING))

      case Some(coordinatorEpochAndMetadata) =>
        val txnMetadata = coordinatorEpochAndMetadata.transactionMetadata

        // 驗證 producer epoch
        if (txnMetadata.producerEpoch != producerEpoch) {
          responseCallback(heartbeatError(Errors.INVALID_PRODUCER_EPOCH))
        } else {
          // 更新 heartbeat 狀態
          val sessionTimeoutMs = txnConfig.producerHeartbeatSessionTimeoutMs
          heartbeatTracker.updateHeartbeat(
            producerId,
            txnMetadata.transactionalId,
            sessionTimeoutMs
          )

          // 計算 heartbeat interval (由 server 控制)
          val heartbeatIntervalMs =
            heartbeatTracker.calculateHeartbeatInterval(sessionTimeoutMs)

          // 返回成功 response
          responseCallback(new ProducerHeartbeatResponse(
            new ProducerHeartbeatResponseData()
              .setErrorCode(Errors.NONE.code)
              .setHeartbeatIntervalMs(heartbeatIntervalMs)
          ))

          trace(s"Updated heartbeat for producer $producerId with " +
            s"transactionalId ${txnMetadata.transactionalId}")
        }
    }
  }

  /**
   * 定期檢查 session timeout
   *
   * 由 Scheduler 定期調用
   */
  private def checkSessionTimeouts(): Unit = {
    try {
      val expiredProducers = heartbeatTracker.getExpiredProducers()

      if (expiredProducers.nonEmpty) {
        info(s"Found ${expiredProducers.size} expired producers: " +
          expiredProducers.keys.mkString(", "))
      }

      expiredProducers.foreach { case (producerId, (transactionalId, elapsedMs)) =>
        info(s"Producer $producerId (transactionalId: $transactionalId) " +
          s"session timeout (last heartbeat: ${elapsedMs}ms ago), " +
          s"aborting pending transaction")

        // 獲取 transaction metadata
        txnManager.getTransactionState(transactionalId).foreach {
          case Some(coordinatorEpochAndMetadata) =>
            val txnMetadata = coordinatorEpochAndMetadata.transactionMetadata

            // 只 abort ongoing transactions
            if (txnMetadata.state == TransactionState.Ongoing) {
              abortTransactionDueToSessionTimeout(
                transactionalId,
                producerId,
                txnMetadata
              )
            }

          case None =>
            // Transaction 不存在，可能已經 completed
            warn(s"Transaction metadata not found for transactionalId $transactionalId")
        }

        // 清理 heartbeat 狀態
        heartbeatTracker.removeHeartbeat(producerId)
      }

    } catch {
      case e: Exception =>
        error("Error checking session timeouts", e)
    }
  }

  /**
   * 因 session timeout abort transaction
   */
  private def abortTransactionDueToSessionTimeout(
      transactionalId: String,
      producerId: Long,
      txnMetadata: TransactionMetadata): Unit = {

    info(s"Aborting transaction $transactionalId for producer $producerId " +
      s"due to session timeout")

    // 準備 abort 的 transition metadata
    val transitMetadata = txnMetadata.prepareAbortOrCommit(
      TransactionResult.ABORT,
      time.milliseconds()
    )

    // 寫入 __transaction_state topic
    txnManager.appendTransactionToLog(
      transactionalId,
      coordinatorEpoch = 0, // TODO: 獲取正確的 epoch
      transitMetadata,
      responseCallback = { errors =>
        if (errors != Errors.NONE) {
          error(s"Failed to abort transaction $transactionalId due to $errors")
        } else {
          // 發送 WriteTxnMarkers 到所有 partitions
          txnMarkerChannelManager.addTxnMarkersToSend(
            coordinatorEpoch = 0,
            txnResult = TransactionResult.ABORT,
            txnMetadata = txnMetadata,
            transitMetadata = transitMetadata
          )

          info(s"Successfully aborted transaction $transactionalId")
        }
      },
      retryCallback = _ => ()
    )
  }

  private def heartbeatError(error: Errors): ProducerHeartbeatResponse = {
    new ProducerHeartbeatResponse(
      new ProducerHeartbeatResponseData()
        .setErrorCode(error.code)
        .setErrorMessage(error.message)
    )
  }

  /**
   * Shutdown 時清理資源
   */
  def shutdown(): Unit = {
    // ... 現有的 shutdown 邏輯 ...

    // 取消 session timeout checker
    sessionTimeoutCheckerTask.cancel()

    // 清理 heartbeat tracker
    heartbeatTracker.clear()
  }
}
```

## 3. TransactionStateManager 擴展

### 3.1 添加 ProducerId 反向索引

```scala
// core/src/main/scala/kafka/coordinator/transaction/TransactionStateManager.scala

class TransactionStateManager(
    brokerId: Int,
    scheduler: Scheduler,
    replicaManager: ReplicaManager,
    metadataCache: MetadataCache,
    config: TransactionConfig,
    time: Time,
    metrics: Metrics) extends Logging {

  // 現有: transactionalId -> TransactionMetadata
  private val transactionMetadataCache =
    new Pool[String, TransactionStateManager.TransactionMetadataRef]()

  // 新增: producerId -> transactionalId 反向索引
  // 用於快速查找 producerId 對應的 transaction metadata
  private val producerIdIndex =
    new ConcurrentHashMap[Long, String]()

  /**
   * 新增方法: 通過 producerId 查找 transaction metadata
   */
  def getTransactionMetadataByProducerId(
      producerId: Long): Option[CoordinatorEpochAndTxnMetadata] = {

    val transactionalId = producerIdIndex.get(producerId)

    if (transactionalId == null) {
      None
    } else {
      getTransactionState(transactionalId).toOption.flatten
    }
  }

  /**
   * 修改: 在添加/更新 transaction metadata 時更新索引
   */
  private def addOrUpdateTransactionMetadata(
      transactionalId: String,
      txnMetadata: TransactionMetadata): Unit = {

    // 更新 transactionalId -> metadata mapping (現有邏輯)
    transactionMetadataCache.put(transactionalId,
      new TransactionStateManager.TransactionMetadataRef(txnMetadata))

    // 新增: 更新 producerId -> transactionalId 反向索引
    if (txnMetadata.producerId != RecordBatch.NO_PRODUCER_ID) {
      producerIdIndex.put(txnMetadata.producerId, transactionalId)
    }
  }

  /**
   * 修改: 在移除 transaction metadata 時清理索引
   */
  private def removeTransactionMetadata(transactionalId: String): Unit = {
    val txnMetadataOpt = transactionMetadataCache.remove(transactionalId)

    // 新增: 清理 producerId 索引
    txnMetadataOpt.foreach { txnMetadataRef =>
      val producerId = txnMetadataRef.metadata.producerId
      if (producerId != RecordBatch.NO_PRODUCER_ID) {
        producerIdIndex.remove(producerId)
      }
    }
  }
}
```

### 3.2 索引維護的注意事項

```scala
/**
 * 索引維護時機：
 *
 * 1. Transaction 初始化時 (InitProducerId)
 *    - 分配新的 producerId
 *    - 建立 producerId -> transactionalId 映射
 *
 * 2. Transaction 過期時
 *    - 移除 transaction metadata
 *    - 清理 producerId 索引
 *
 * 3. Producer ID rotation 時
 *    - 舊 producerId 從索引中移除
 *    - 新 producerId 加入索引
 *
 * 4. Coordinator failover 時
 *    - 從 __transaction_state log 重建索引
 *    - 掃描所有 transaction metadata
 */
```

## 4. 配置參數

### 4.1 TransactionConfig 擴展

```scala
// core/src/main/scala/kafka/coordinator/transaction/TransactionConfig.scala

case class TransactionConfig(
    // ... 現有配置 ...

    // 新增: Producer heartbeat 配置
    producerHeartbeatSessionTimeoutMs: Long = 10000,  // 10 秒
    producerHeartbeatTimeoutCheckIntervalMs: Long = 3000  // 3 秒檢查一次
)
```

### 4.2 Server 配置

```scala
// core/src/main/scala/kafka/server/KafkaConfig.scala

// 新增配置項
val ProducerHeartbeatSessionTimeoutMsProp =
  "producer.heartbeat.session.timeout.ms"
val ProducerHeartbeatSessionTimeoutMsDoc =
  "The session timeout for transactional producers. If no heartbeat is " +
  "received within this timeout, the transaction coordinator will abort " +
  "any pending transactions for this producer."

val ProducerHeartbeatTimeoutCheckIntervalMsProp =
  "producer.heartbeat.timeout.check.interval.ms"
val ProducerHeartbeatTimeoutCheckIntervalMsDoc =
  "The frequency at which the transaction coordinator checks for " +
  "session timeouts."

// 在 ConfigDef 中定義
.define(
  ProducerHeartbeatSessionTimeoutMsProp,
  LONG,
  10000,  // 預設 10 秒
  atLeast(5000),  // 最小 5 秒
  HIGH,
  ProducerHeartbeatSessionTimeoutMsDoc
)
.define(
  ProducerHeartbeatTimeoutCheckIntervalMsProp,
  LONG,
  3000,  // 預設 3 秒
  atLeast(1000),  // 最小 1 秒
  MEDIUM,
  ProducerHeartbeatTimeoutCheckIntervalMsDoc
)
```

## 5. Coordinator Failover 處理

### 5.1 Failover 流程

```scala
/**
 * Coordinator Failover 時的處理流程：
 *
 * 1. 新 coordinator 當選
 *    ↓
 * 2. 從 __transaction_state log 加載 transaction metadata
 *    ↓
 * 3. 重建 producerId -> transactionalId 反向索引
 *    ↓
 * 4. 清空 in-memory heartbeat state
 *    (producers 會在下一個 heartbeat 時重新註冊)
 *    ↓
 * 5. 開始接收 heartbeat requests
 */
```

### 5.2 實作

```scala
// core/src/main/scala/kafka/coordinator/transaction/TransactionStateManager.scala

/**
 * Coordinator 當選時調用
 */
def onBecomeLeader(partition: Int): Unit = {
  info(s"Becoming leader for transaction partition $partition")

  // 1. 從 log 加載 transaction metadata (現有邏輯)
  loadTransactionsFromLog(partition)

  // 2. 重建 producerId 反向索引 (新增)
  rebuildProducerIdIndex(partition)

  // 3. 清空 heartbeat tracker (新增)
  // 注意: 在 TransactionCoordinator 中處理
}

/**
 * 重建 producerId 反向索引
 */
private def rebuildProducerIdIndex(partition: Int): Unit = {
  val startMs = time.milliseconds()
  var count = 0

  // 掃描該 partition 的所有 transaction metadata
  transactionMetadataCache.values.foreach { txnMetadataRef =>
    val txnMetadata = txnMetadataRef.metadata

    if (txnMetadata.producerId != RecordBatch.NO_PRODUCER_ID &&
        partitionFor(txnMetadata.transactionalId) == partition) {

      producerIdIndex.put(txnMetadata.producerId, txnMetadata.transactionalId)
      count += 1
    }
  }

  val elapsedMs = time.milliseconds() - startMs
  info(s"Rebuilt producerId index for partition $partition: " +
    s"$count entries in ${elapsedMs}ms")
}
```

### 5.3 Failover 期間的處理

```
Scenario 1: Producer 發送 heartbeat 到舊 coordinator
  ↓
  舊 coordinator 已經不再是 leader
  ↓
  返回 NOT_COORDINATOR 錯誤
  ↓
  Producer 重新發現 coordinator
  ↓
  發送 heartbeat 到新 coordinator

Scenario 2: 在 failover 期間沒有收到 heartbeat
  ↓
  新 coordinator 沒有該 producer 的 heartbeat state
  ↓
  Producer 第一次發送 heartbeat 時會重新註冊
  ↓
  正常處理，不會觸發 timeout

Scenario 3: Transaction 在 failover 期間超時
  ↓
  In-memory heartbeat state 丟失
  ↓
  需要依賴 transaction.timeout.ms (現有機制)
  ↓
  接受這個限制：failover 期間可能有短暫的檢測延遲
```

## 6. 錯誤處理

### 6.1 常見錯誤場景

```scala
/**
 * 錯誤處理矩陣
 */
object ProducerHeartbeatErrors {

  // 1. Producer ID 不存在
  // 原因: Producer 還沒有調用 initTransactions()
  // 處理: 返回 INVALID_PRODUCER_ID_MAPPING
  // Client: Fatal error，停止 heartbeat

  // 2. Producer Epoch 不匹配
  // 原因: Producer 被 fenced 或 epoch bump
  // 處理: 返回 INVALID_PRODUCER_EPOCH
  // Client: 請求更新 epoch

  // 3. Coordinator 不可用
  // 原因: Broker 正在 shutdown 或 partition 正在遷移
  // 處理: 返回 COORDINATOR_NOT_AVAILABLE
  // Client: 重新發現 coordinator，快速重試

  // 4. 不是 coordinator
  // 原因: Client 發送到錯誤的 broker
  // 處理: 返回 NOT_COORDINATOR
  // Client: 重新發現 coordinator

  // 5. Authorization 失敗
  // 原因: Client 沒有 CLUSTER_ACTION 權限
  // 處理: 返回 CLUSTER_AUTHORIZATION_FAILED
  // Client: Fatal error
}
```

### 6.2 實作

```scala
def handleProducerHeartbeat(
    producerId: Long,
    producerEpoch: Short,
    responseCallback: ProducerHeartbeatResponse => Unit): Unit = {

  try {
    // 檢查 coordinator 狀態
    if (!isActive.get()) {
      responseCallback(heartbeatError(Errors.COORDINATOR_NOT_AVAILABLE))
      return
    }

    // 查找 transaction metadata
    val txnMetadataOpt = txnManager.getTransactionMetadataByProducerId(producerId)

    txnMetadataOpt match {
      case None =>
        // Producer ID 不存在
        debug(s"Producer $producerId not found in transaction metadata")
        responseCallback(heartbeatError(Errors.INVALID_PRODUCER_ID_MAPPING))

      case Some(coordinatorEpochAndMetadata) =>
        val txnMetadata = coordinatorEpochAndMetadata.transactionMetadata

        // 驗證 epoch
        if (txnMetadata.producerEpoch != producerEpoch) {
          info(s"Producer $producerId epoch mismatch: " +
            s"expected ${txnMetadata.producerEpoch}, got $producerEpoch")
          responseCallback(heartbeatError(Errors.INVALID_PRODUCER_EPOCH))

        } else {
          // 正常處理 heartbeat
          processHeartbeat(producerId, txnMetadata, responseCallback)
        }
    }

  } catch {
    case e: Exception =>
      error(s"Error handling producer heartbeat for producer $producerId", e)
      responseCallback(heartbeatError(Errors.UNKNOWN_SERVER_ERROR))
  }
}

private def processHeartbeat(
    producerId: Long,
    txnMetadata: TransactionMetadata,
    responseCallback: ProducerHeartbeatResponse => Unit): Unit = {

  // 更新 heartbeat 狀態
  val sessionTimeoutMs = txnConfig.producerHeartbeatSessionTimeoutMs
  heartbeatTracker.updateHeartbeat(
    producerId,
    txnMetadata.transactionalId,
    sessionTimeoutMs
  )

  // 計算 heartbeat interval
  val heartbeatIntervalMs =
    heartbeatTracker.calculateHeartbeatInterval(sessionTimeoutMs)

  // 返回成功 response
  responseCallback(new ProducerHeartbeatResponse(
    new ProducerHeartbeatResponseData()
      .setErrorCode(Errors.NONE.code)
      .setHeartbeatIntervalMs(heartbeatIntervalMs)
  ))

  trace(s"Updated heartbeat for producer $producerId " +
    s"(transactionalId: ${txnMetadata.transactionalId})")
}
```

## 7. Metrics 和監控

### 7.1 JMX Metrics

```scala
// core/src/main/scala/kafka/coordinator/transaction/TransactionCoordinatorMetrics.scala

class ProducerHeartbeatMetrics(metrics: Metrics) {

  // Heartbeat request metrics
  val heartbeatRequestRate = metrics.sensor("producer-heartbeat-request-rate")
  heartbeatRequestRate.add(
    metrics.metricName("producer-heartbeat-request-rate",
      "kafka.coordinator.transaction"),
    new Rate()
  )

  // Heartbeat error metrics
  val heartbeatErrorRate = metrics.sensor("producer-heartbeat-error-rate")
  heartbeatErrorRate.add(
    metrics.metricName("producer-heartbeat-error-rate",
      "kafka.coordinator.transaction"),
    new Rate()
  )

  // Active producers
  val activeProducerCount = metrics.gauge(
    "active-producer-count",
    "kafka.coordinator.transaction",
    () => heartbeatTracker.size
  )

  // Session timeout metrics
  val sessionTimeoutRate = metrics.sensor("producer-session-timeout-rate")
  sessionTimeoutRate.add(
    metrics.metricName("producer-session-timeout-rate",
      "kafka.coordinator.transaction"),
    new Rate()
  )

  // Aborted transactions due to timeout
  val timeoutAbortRate = metrics.sensor("transaction-timeout-abort-rate")
  timeoutAbortRate.add(
    metrics.metricName("transaction-timeout-abort-rate",
      "kafka.coordinator.transaction"),
    new Rate()
  )
}
```

### 7.2 Logging

```scala
// 關鍵事件 logging

// Heartbeat 成功
trace(s"Received heartbeat from producer $producerId " +
  s"(transactionalId: $transactionalId)")

// Session timeout 檢測
info(s"Producer $producerId session timeout " +
  s"(last heartbeat: ${elapsedMs}ms ago), aborting transaction")

// Transaction abort 成功
info(s"Successfully aborted transaction $transactionalId " +
  s"for producer $producerId due to session timeout")

// 錯誤情況
warn(s"Producer $producerId epoch mismatch: " +
  s"expected $expectedEpoch, got $actualEpoch")

error(s"Failed to abort transaction $transactionalId " +
  s"due to $error")
```

## 8. 測試策略

### 8.1 單元測試

```scala
// core/src/test/scala/unit/kafka/coordinator/transaction/ProducerHeartbeatTrackerTest.scala

class ProducerHeartbeatTrackerTest {

  @Test
  def testUpdateHeartbeat(): Unit = {
    // 測試 heartbeat 狀態更新
  }

  @Test
  def testGetExpiredProducers(): Unit = {
    // 測試過期 producer 檢測
  }

  @Test
  def testCalculateHeartbeatInterval(): Unit = {
    // 測試 heartbeat interval 計算
  }
}

// core/src/test/scala/unit/kafka/coordinator/transaction/TransactionCoordinatorTest.scala

class TransactionCoordinatorTest {

  @Test
  def testHandleProducerHeartbeat(): Unit = {
    // 測試正常 heartbeat 處理
  }

  @Test
  def testHandleProducerHeartbeatWithInvalidProducerId(): Unit = {
    // 測試無效 producer ID
  }

  @Test
  def testHandleProducerHeartbeatWithInvalidEpoch(): Unit = {
    // 測試無效 epoch
  }

  @Test
  def testSessionTimeoutCheck(): Unit = {
    // 測試 session timeout 檢查
  }

  @Test
  def testAbortTransactionDueToSessionTimeout(): Unit = {
    // 測試因 timeout abort transaction
  }
}
```

### 8.2 整合測試

```scala
// core/src/test/scala/integration/kafka/api/ProducerHeartbeatIntegrationTest.scala

class ProducerHeartbeatIntegrationTest extends KafkaServerTestHarness {

  @Test
  def testProducerHeartbeatNormalFlow(): Unit = {
    // 測試完整的 heartbeat 流程
    // 1. 創建 transactional producer
    // 2. 發送 heartbeat
    // 3. 驗證 response
  }

  @Test
  def testSessionTimeoutAbortsTransaction(): Unit = {
    // 測試 session timeout 導致 transaction abort
    // 1. 開始 transaction
    // 2. 停止發送 heartbeat
    // 3. 等待 session timeout
    // 4. 驗證 transaction 被 abort
  }

  @Test
  def testCoordinatorFailover(): Unit = {
    // 測試 coordinator failover
    // 1. 發送 heartbeat 到 coordinator
    // 2. 觸發 coordinator failover
    // 3. 驗證 heartbeat 可以發送到新 coordinator
  }
}
```

## 9. 性能考量

### 9.1 並發控制

```scala
/**
 * 並發處理策略：
 *
 * 1. HeartbeatTracker 使用 ConcurrentHashMap
 *    - 支持高並發讀寫
 *    - 無需額外的鎖
 *
 * 2. TransactionStateManager 的索引
 *    - producerIdIndex 使用 ConcurrentHashMap
 *    - 與現有的 transactionMetadataCache 一致
 *
 * 3. Session Timeout Checker
 *    - 在獨立的 scheduler thread 中執行
 *    - 不會阻塞 heartbeat request 處理
 */
```

### 9.2 內存使用

```scala
/**
 * 內存使用估算：
 *
 * 每個 producer 的 heartbeat state:
 * - lastHeartbeatMs: 8 bytes (Long)
 * - sessionTimeoutMs: 8 bytes (Long)
 * - transactionalId: ~50 bytes (String)
 * - HashMap overhead: ~32 bytes
 * 總計: ~100 bytes per producer
 *
 * 1000 transactional producers = 100 KB
 * 10000 transactional producers = 1 MB
 *
 * ProducerId 反向索引:
 * - producerId (Long): 8 bytes
 * - transactionalId (String): ~50 bytes
 * - HashMap overhead: ~32 bytes
 * 總計: ~90 bytes per entry
 *
 * 1000 entries = 90 KB
 * 10000 entries = 900 KB
 *
 * 總內存開銷: 對於 10000 producers ≈ 2 MB
 */
```

### 9.3 CPU 使用

```scala
/**
 * CPU 使用分析：
 *
 * 1. Heartbeat request 處理:
 *    - HashMap lookup: O(1)
 *    - Epoch 驗證: O(1)
 *    - State 更新: O(1)
 *    每個 request < 1ms
 *
 * 2. Session timeout checking:
 *    - 掃描所有 producers: O(n)
 *    - 每 3 秒執行一次
 *    - 10000 producers: ~10ms per scan
 *
 * 3. Coordinator failover:
 *    - 重建索引: O(n)
 *    - 10000 entries: ~50ms
 *    - 只在 failover 時發生，頻率很低
 */
```

## 10. 總結

### 10.1 關鍵實作要點

1. **Heartbeat Tracker**
   - 使用 ConcurrentHashMap 保證線程安全
   - In-memory 狀態，輕量級
   - Coordinator failover 時自動重建

2. **ProducerId 反向索引**
   - 快速查找 producerId -> TransactionMetadata
   - 與現有的 transaction metadata cache 同步維護
   - Coordinator failover 時從 log 重建

3. **Session Timeout Checker**
   - 獨立的 scheduler task
   - 定期掃描過期的 producers
   - 自動 abort pending transactions

4. **錯誤處理**
   - 完整的錯誤碼定義
   - Graceful degradation
   - 詳細的 logging

5. **Metrics 和監控**
   - 完整的 JMX metrics
   - 關鍵事件 logging
   - 易於故障排查

### 10.2 與現有系統的整合

- ✅ 最小化對現有代碼的修改
- ✅ 重用現有的 TransactionCoordinator 架構
- ✅ 利用現有的 `__transaction_state` topic
- ✅ 遵循現有的錯誤處理模式
- ✅ 使用現有的 Scheduler 和 Metrics 框架

### 10.3 未來優化方向

1. 批量處理 heartbeat requests
2. 動態調整 heartbeat interval
3. 更精細的 timeout 控制
4. 支持 producer group 的概念
