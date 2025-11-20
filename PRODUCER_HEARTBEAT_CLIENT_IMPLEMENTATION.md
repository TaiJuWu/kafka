# Producer Heartbeat - Client 端詳細實作

## 概覽

Client 端需要在 transactional producer 啟動後，在背景持續發送 heartbeat 到 Transaction Coordinator，以確保 session liveness。

## 核心設計原則

1. **與 TransactionManager 整合** - 複用現有的 transaction coordinator 連線
2. **在 Sender thread 中執行** - 不創建額外 thread，利用現有的 I/O thread
3. **Server 控制頻率** - Heartbeat 頻率由 server 在 response 中指定
4. **只支援 Transactional Producers** - 非 transactional producer 不啟用

## 架構圖

```
┌─────────────────────────────────────────────────────────────┐
│                     KafkaProducer                           │
│                                                             │
│  ┌──────────────┐         ┌─────────────────────┐         │
│  │ User Thread  │────────>│ TransactionManager  │         │
│  │              │         │                     │         │
│  │ send()       │         │ - producerId        │         │
│  │ beginTx()    │         │ - producerEpoch     │         │
│  │ commitTx()   │         │ - coordinator       │         │
│  └──────────────┘         └─────────┬───────────┘         │
│                                      │                     │
│                                      │ inject              │
│                                      v                     │
│  ┌────────────────────────────────────────────────┐       │
│  │           Sender (I/O Thread)                  │       │
│  │                                                 │       │
│  │  ┌──────────────┐    ┌──────────────────────┐ │       │
│  │  │ runOnce()    │───>│ ProducerHeartbeat    │ │       │
│  │  │              │    │ RequestManager       │ │       │
│  │  │ - send data  │    │                      │ │       │
│  │  │ - handle txn │    │ - heartbeatState     │ │       │
│  │  │ - heartbeat  │    │ - lastHeartbeatMs    │ │       │
│  │  └──────────────┘    │ - intervalMs         │ │       │
│  │                      └──────────────────────┘ │       │
│  └────────────────────────────────────────────────┘       │
│                                      │                     │
│                                      │ send request        │
│                                      v                     │
│                      ┌─────────────────────────┐          │
│                      │   NetworkClient         │          │
│                      │                         │          │
│                      │  PRODUCER_HEARTBEAT ───>│          │
│                      └─────────────────────────┘          │
└─────────────────────────────────────────────────────────────┘
                                      │
                                      │ to Transaction Coordinator
                                      v
                        ┌──────────────────────────┐
                        │ Transaction Coordinator  │
                        └──────────────────────────┘
```

## 1. 新增類別: ProducerHeartbeatRequestManager

### 1.1 類別定義

```java
package org.apache.kafka.clients.producer.internals;

import org.apache.kafka.clients.ClientRequest;
import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.clients.RequestCompletionHandler;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.message.ProducerHeartbeatRequestData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.ProducerHeartbeatRequest;
import org.apache.kafka.common.requests.ProducerHeartbeatResponse;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Timer;
import org.slf4j.Logger;

/**
 * Manages the heartbeat requests for transactional producers to maintain session liveness
 * with the Transaction Coordinator.
 *
 * This class is NOT thread-safe and should only be accessed from the Sender thread.
 */
public class ProducerHeartbeatRequestManager {

    private final Logger log;
    private final Time time;
    private final TransactionManager transactionManager;

    /**
     * The heartbeat timer tracks the time since the last heartbeat was sent
     */
    private final Timer heartbeatTimer;

    /**
     * The heartbeat interval which is acquired/updated through the heartbeat response
     * Initially set to session timeout / 3, updated by server response
     */
    private volatile long heartbeatIntervalMs;

    /**
     * The session timeout configured by the client
     */
    private final long sessionTimeoutMs;

    /**
     * Tracks if heartbeat is enabled (only for transactional producers)
     */
    private final boolean enabled;

    /**
     * Tracks if a heartbeat request is currently in-flight
     */
    private boolean requestInFlight;

    /**
     * The last time a heartbeat was sent successfully
     */
    private long lastHeartbeatSendMs;

    /**
     * The last time a heartbeat response was received successfully
     */
    private long lastHeartbeatResponseMs;

    /**
     * Error from the last heartbeat, if any
     */
    private RuntimeException lastHeartbeatError;

    public ProducerHeartbeatRequestManager(
            LogContext logContext,
            Time time,
            long sessionTimeoutMs,
            TransactionManager transactionManager) {

        this.log = logContext.logger(ProducerHeartbeatRequestManager.class);
        this.time = time;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.transactionManager = transactionManager;

        // Only enable for transactional producers
        this.enabled = transactionManager != null &&
                       transactionManager.isTransactional();

        if (!enabled) {
            log.info("Producer heartbeat disabled: not a transactional producer");
            this.heartbeatIntervalMs = Long.MAX_VALUE;
            this.heartbeatTimer = time.timer(Long.MAX_VALUE);
            return;
        }

        // Initial heartbeat interval = session timeout / 3 (similar to consumer)
        this.heartbeatIntervalMs = sessionTimeoutMs / 3;
        this.heartbeatTimer = time.timer(heartbeatIntervalMs);
        this.requestInFlight = false;
        this.lastHeartbeatSendMs = time.milliseconds();
        this.lastHeartbeatResponseMs = time.milliseconds();

        log.info("Producer heartbeat enabled with session timeout {}ms, initial interval {}ms",
                sessionTimeoutMs, heartbeatIntervalMs);
    }

    /**
     * Check if heartbeat is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if we should send a heartbeat now
     *
     * Conditions:
     * 1. Heartbeat is enabled
     * 2. Producer has been initialized (has producerId)
     * 3. Transaction coordinator is known
     * 4. Heartbeat timer has expired
     * 5. No heartbeat request is currently in-flight
     */
    public boolean shouldSendHeartbeat(long currentTimeMs) {
        if (!enabled) {
            return false;
        }

        // Need producer ID first
        if (!transactionManager.hasProducerId()) {
            return false;
        }

        // Need transaction coordinator
        if (transactionManager.coordinator() == null) {
            return false;
        }

        // Don't send if request already in-flight
        if (requestInFlight) {
            return false;
        }

        // Check if timer expired
        heartbeatTimer.update(currentTimeMs);
        return heartbeatTimer.isExpired();
    }

    /**
     * Build the heartbeat request
     */
    public ClientRequest.Builder buildHeartbeatRequest() {
        if (!enabled) {
            throw new IllegalStateException("Cannot build heartbeat request when disabled");
        }

        ProducerIdAndEpoch producerIdAndEpoch = transactionManager.producerIdAndEpoch();

        ProducerHeartbeatRequestData data = new ProducerHeartbeatRequestData()
            .setProducerId(producerIdAndEpoch.producerId)
            .setProducerEpoch(producerIdAndEpoch.epoch);

        ProducerHeartbeatRequest request = new ProducerHeartbeatRequest.Builder(data).build();

        return new ClientRequest.Builder()
            .apiKey(request.apiKey())
            .destination(transactionManager.coordinator().idString())
            .request(request);
    }

    /**
     * Send a heartbeat request
     *
     * Called by Sender thread when shouldSendHeartbeat() returns true
     */
    public void sendHeartbeat(long currentTimeMs) {
        if (!shouldSendHeartbeat(currentTimeMs)) {
            return;
        }

        this.requestInFlight = true;
        this.lastHeartbeatSendMs = currentTimeMs;

        log.trace("Sending producer heartbeat at {}ms (interval={}ms)",
                currentTimeMs, heartbeatIntervalMs);
    }

    /**
     * Handle the heartbeat response
     */
    public void handleHeartbeatResponse(
            ClientResponse response,
            long currentTimeMs) {

        this.requestInFlight = false;

        if (response.wasDisconnected()) {
            log.debug("Heartbeat failed due to coordinator disconnect, will retry");
            this.lastHeartbeatError = new DisconnectException(
                "Disconnected from transaction coordinator");
            // Reset timer to retry immediately
            heartbeatTimer.reset(0);
            return;
        }

        if (response.versionMismatch() != null) {
            log.warn("Coordinator does not support PRODUCER_HEARTBEAT, disabling heartbeat");
            // Disable heartbeat permanently if not supported
            heartbeatTimer.reset(Long.MAX_VALUE);
            return;
        }

        ProducerHeartbeatResponse heartbeatResponse =
            (ProducerHeartbeatResponse) response.responseBody();

        Errors error = heartbeatResponse.error();

        if (error == Errors.NONE) {
            // Success!
            this.lastHeartbeatResponseMs = currentTimeMs;
            this.lastHeartbeatError = null;

            // Update heartbeat interval from server
            int serverIntervalMs = heartbeatResponse.data().heartbeatIntervalMs();
            if (serverIntervalMs > 0 && serverIntervalMs != this.heartbeatIntervalMs) {
                log.info("Updating heartbeat interval from {}ms to {}ms (from server)",
                        this.heartbeatIntervalMs, serverIntervalMs);
                this.heartbeatIntervalMs = serverIntervalMs;
            }

            // Reset timer for next heartbeat
            heartbeatTimer.reset(heartbeatIntervalMs);

            log.trace("Heartbeat succeeded, next heartbeat in {}ms", heartbeatIntervalMs);

        } else if (error == Errors.INVALID_PRODUCER_EPOCH ||
                   error == Errors.PRODUCER_FENCED) {
            // Fatal errors - producer is fenced
            log.error("Producer fenced by heartbeat response: {}", error);
            this.lastHeartbeatError = error.exception();
            // Transition transaction manager to fatal error state
            transactionManager.transitionToFatalError(
                new ProducerFencedException("Producer fenced: " + error.message()));
            // Stop sending heartbeats
            heartbeatTimer.reset(Long.MAX_VALUE);

        } else if (error == Errors.UNKNOWN_PRODUCER_ID) {
            // Producer ID not recognized, need to re-initialize
            log.warn("Producer ID not recognized by coordinator, will reinitialize");
            this.lastHeartbeatError = error.exception();
            // Let transaction manager handle re-initialization
            transactionManager.requestEpochBumpAndHandleInflight();
            // Retry heartbeat after re-init
            heartbeatTimer.reset(0);

        } else if (error == Errors.COORDINATOR_NOT_AVAILABLE ||
                   error == Errors.COORDINATOR_LOAD_IN_PROGRESS ||
                   error == Errors.NOT_COORDINATOR) {
            // Retriable coordinator errors
            log.debug("Heartbeat failed with retriable error {}, will retry", error);
            this.lastHeartbeatError = error.exception();
            // Mark coordinator as unknown and retry
            transactionManager.markCoordinatorUnknown(error.message());
            heartbeatTimer.reset(0);

        } else {
            // Unexpected error
            log.warn("Unexpected error from heartbeat: {}", error);
            this.lastHeartbeatError = error.exception();
            // Retry with backoff
            heartbeatTimer.reset(Math.min(heartbeatIntervalMs, 5000));
        }
    }

    /**
     * Calculate time to next heartbeat in milliseconds
     */
    public long timeToNextHeartbeatMs(long currentTimeMs) {
        if (!enabled) {
            return Long.MAX_VALUE;
        }

        heartbeatTimer.update(currentTimeMs);

        if (requestInFlight) {
            // Wait for in-flight request to complete
            return Long.MAX_VALUE;
        }

        return heartbeatTimer.remainingMs();
    }

    /**
     * Reset the heartbeat state (e.g., when producer is reset)
     */
    public void reset() {
        if (!enabled) {
            return;
        }

        log.debug("Resetting heartbeat state");
        this.requestInFlight = false;
        this.lastHeartbeatError = null;
        this.heartbeatTimer.reset(heartbeatIntervalMs);
    }

    /**
     * Check if session might have timed out on the server side
     * This is a heuristic check - if we haven't received a successful response
     * in more than session timeout, the server may have expired our session.
     */
    public boolean isSessionPossiblyExpired(long currentTimeMs) {
        if (!enabled) {
            return false;
        }

        long timeSinceLastResponse = currentTimeMs - lastHeartbeatResponseMs;
        return timeSinceLastResponse > sessionTimeoutMs;
    }

    /**
     * Get the last heartbeat error, if any
     */
    public RuntimeException lastError() {
        return lastHeartbeatError;
    }

    /**
     * For testing/metrics
     */
    public long lastHeartbeatSendMs() {
        return lastHeartbeatSendMs;
    }

    public long lastHeartbeatResponseMs() {
        return lastHeartbeatResponseMs;
    }

    public long heartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }
}
```

## 2. Sender 整合

### 2.1 Sender 修改

在 `Sender` 類別中整合 heartbeat 邏輯：

```java
public class Sender implements Runnable {
    // ... 現有欄位 ...

    // 新增
    private final ProducerHeartbeatRequestManager heartbeatManager;

    public Sender(LogContext logContext,
                  KafkaClient client,
                  ProducerMetadata metadata,
                  RecordAccumulator accumulator,
                  // ... 其他參數 ...
                  TransactionManager transactionManager,
                  long producerHeartbeatSessionTimeoutMs) {  // 新增參數

        // ... 現有初始化 ...

        // 初始化 heartbeat manager
        this.heartbeatManager = new ProducerHeartbeatRequestManager(
            logContext,
            time,
            producerHeartbeatSessionTimeoutMs,
            transactionManager
        );
    }

    /**
     * The main run loop for the sender thread
     */
    @Override
    public void run() {
        log.debug("Starting Kafka producer I/O thread.");

        // main loop, runs until close is called
        while (running) {
            try {
                runOnce();
            } catch (Exception e) {
                log.error("Uncaught error in producer I/O thread: ", e);
            }
        }

        // ... cleanup code ...
    }

    /**
     * Run a single iteration of sending
     */
    void runOnce() {
        // ... 現有 transaction 處理邏輯 ...

        long currentTimeMs = time.milliseconds();

        // 1. Handle transaction requests (existing)
        long pollTimeout = sendProducerData(currentTimeMs);

        // 2. Handle producer heartbeat (new)
        pollTimeout = Math.min(pollTimeout, maybeSendProducerHeartbeat(currentTimeMs));

        // 3. Do the I/O
        client.poll(pollTimeout, currentTimeMs);
    }

    /**
     * Send producer heartbeat if needed
     *
     * @return the time to next heartbeat in milliseconds
     */
    private long maybeSendProducerHeartbeat(long currentTimeMs) {
        if (!heartbeatManager.isEnabled()) {
            return Long.MAX_VALUE;
        }

        // Check if we should send heartbeat
        if (heartbeatManager.shouldSendHeartbeat(currentTimeMs)) {
            log.trace("Sending producer heartbeat");

            ClientRequest.Builder requestBuilder = heartbeatManager.buildHeartbeatRequest();

            ClientRequest request = client.newClientRequest(
                requestBuilder.destination(),
                requestBuilder,
                currentTimeMs,
                true, // expectResponse
                (int) heartbeatManager.heartbeatIntervalMs(), // requestTimeoutMs
                new ProducerHeartbeatCompletionHandler(currentTimeMs)
            );

            client.send(request, currentTimeMs);
            heartbeatManager.sendHeartbeat(currentTimeMs);
        }

        // Return time to next heartbeat for poll timeout calculation
        return heartbeatManager.timeToNextHeartbeatMs(currentTimeMs);
    }

    /**
     * Completion handler for producer heartbeat requests
     */
    private class ProducerHeartbeatCompletionHandler implements RequestCompletionHandler {
        private final long requestStartTimeMs;

        public ProducerHeartbeatCompletionHandler(long requestStartTimeMs) {
            this.requestStartTimeMs = requestStartTimeMs;
        }

        @Override
        public void onComplete(ClientResponse response) {
            long currentTimeMs = time.milliseconds();
            long latencyMs = currentTimeMs - requestStartTimeMs;

            // Record metrics
            sensors.recordHeartbeatLatency(latencyMs);

            // Let heartbeat manager handle the response
            heartbeatManager.handleHeartbeatResponse(response, currentTimeMs);

            // Check for session expiration warning
            if (heartbeatManager.isSessionPossiblyExpired(currentTimeMs)) {
                log.warn("Producer session may have expired on the server side " +
                        "(no successful heartbeat for {}ms, session timeout={}ms)",
                        currentTimeMs - heartbeatManager.lastHeartbeatResponseMs(),
                        heartbeatManager.heartbeatIntervalMs() * 3);
            }
        }
    }
}
```

## 3. KafkaProducer 修改

### 3.1 新增配置參數

```java
public class ProducerConfig extends AbstractConfig {
    // ... 現有配置 ...

    /**
     * Producer heartbeat session timeout (only for transactional producers)
     */
    public static final String PRODUCER_HEARTBEAT_SESSION_TIMEOUT_MS_CONFIG =
        "producer.heartbeat.session.timeout.ms";
    public static final String PRODUCER_HEARTBEAT_SESSION_TIMEOUT_MS_DOC =
        "The timeout used to detect producer failures when using Kafka's transactional features. " +
        "The producer sends periodic heartbeats to indicate its liveness to the transaction " +
        "coordinator. If no heartbeats are received by the broker before the expiration of " +
        "this session timeout, then the coordinator will abort any pending transaction and " +
        "remove the producer from the transaction. " +
        "This config is only used for transactional producers (when transactional.id is set). " +
        "The default value is 10 seconds, which is much shorter than transaction.timeout.ms, " +
        "allowing for fast failure detection while still supporting long-running transactions.";
    public static final long PRODUCER_HEARTBEAT_SESSION_TIMEOUT_MS_DEFAULT = 10000L; // 10 seconds

    static {
        CONFIG = new ConfigDef()
            // ... 現有配置 ...
            .define(PRODUCER_HEARTBEAT_SESSION_TIMEOUT_MS_CONFIG,
                    Type.LONG,
                    PRODUCER_HEARTBEAT_SESSION_TIMEOUT_MS_DEFAULT,
                    atLeast(1000L),
                    Importance.MEDIUM,
                    PRODUCER_HEARTBEAT_SESSION_TIMEOUT_MS_DOC)
            // ... 其他配置 ...
    }
}
```

### 3.2 KafkaProducer 初始化

```java
public class KafkaProducer<K, V> implements Producer<K, V> {

    // ... 現有欄位 ...

    private KafkaProducer(ProducerConfig config,
                          Serializer<K> keySerializer,
                          Serializer<V> valueSerializer,
                          // ... 其他參數 ...
                          ) {

        // ... 現有初始化 ...

        // Get heartbeat session timeout
        long producerHeartbeatSessionTimeoutMs = config.getLong(
            ProducerConfig.PRODUCER_HEARTBEAT_SESSION_TIMEOUT_MS_CONFIG);

        // Validate: session timeout should be less than transaction timeout
        if (transactionManager != null && transactionManager.isTransactional()) {
            if (producerHeartbeatSessionTimeoutMs >= transactionManager.transactionTimeoutMs()) {
                throw new ConfigException(
                    ProducerConfig.PRODUCER_HEARTBEAT_SESSION_TIMEOUT_MS_CONFIG +
                    " (" + producerHeartbeatSessionTimeoutMs + "ms) must be less than " +
                    ProducerConfig.TRANSACTION_TIMEOUT_CONFIG +
                    " (" + transactionManager.transactionTimeoutMs() + "ms)");
            }
        }

        // Create sender with heartbeat support
        this.sender = newSender(
            logContext,
            kafkaClient,
            metadata,
            accumulator,
            // ... 其他參數 ...
            transactionManager,
            producerHeartbeatSessionTimeoutMs  // 新增參數
        );

        // ... 其他初始化 ...
    }
}
```

## 4. 配置說明

### 4.1 Client 端配置

只需要一個配置參數：

```properties
# Transactional producer 配置
transactional.id=my-transactional-id

# Session timeout for heartbeat (預設 10 秒)
producer.heartbeat.session.timeout.ms=10000

# Transaction timeout (預設 60 秒,允許長時間處理)
transaction.timeout.ms=60000
```

### 4.2 配置驗證

```
約束條件:
producer.heartbeat.session.timeout.ms < transaction.timeout.ms

建議值:
session.timeout.ms = 10 秒  (快速失敗偵測)
transaction.timeout.ms = 60 秒或更長 (允許長 transaction)

範例:
✅ session=10s, transaction=60s    (合理)
✅ session=10s, transaction=300s   (長 transaction)
❌ session=60s, transaction=30s    (無效:session > transaction)
```

## 5. 錯誤處理

### 5.1 錯誤類型與處理

| 錯誤 | 類型 | 處理方式 | 影響 |
|------|------|---------|------|
| `NONE` | 成功 | 更新 interval,重置 timer | 繼續正常運作 |
| `INVALID_PRODUCER_EPOCH` | Fatal | 轉換為 `ProducerFencedException` | Producer fenced,停止所有操作 |
| `PRODUCER_FENCED` | Fatal | 轉換為 `ProducerFencedException` | Producer fenced,停止所有操作 |
| `UNKNOWN_PRODUCER_ID` | Retriable | 觸發 epoch bump | 重新初始化 producer ID |
| `COORDINATOR_NOT_AVAILABLE` | Retriable | 標記 coordinator 未知 | 重新查找 coordinator |
| `COORDINATOR_LOAD_IN_PROGRESS` | Retriable | 標記 coordinator 未知 | 等待後重試 |
| `NOT_COORDINATOR` | Retriable | 標記 coordinator 未知 | 重新查找 coordinator |
| `UNSUPPORTED_VERSION` | Fatal | 停用 heartbeat | 不再發送 heartbeat |
| Disconnect | Retriable | 立即重試 | 等待重連 |

### 5.2 錯誤處理流程

```
┌─────────────────────┐
│ Send Heartbeat      │
└──────────┬──────────┘
           │
           v
     ┌─────────────┐
     │  Response?  │
     └─────┬───────┘
           │
    ┌──────┴──────┐
    │             │
    v             v
SUCCESS       ERROR
    │             │
    v             └─────┬──────────────┬────────────┬─────────────┐
Reset Timer            │              │            │             │
Next = interval    FATAL         RETRIABLE    UNSUPPORTED   DISCONNECT
                       │              │            │             │
                       v              v            v             v
                  Transition     Mark Coord    Disable       Retry
                  to FATAL       Unknown       Heartbeat     Immediately
                       │              │            │             │
                       v              v            v             v
                  Stop All      Retry After    No More      Wait for
                  Operations    Re-discover    Heartbeats   Reconnect
```

## 6. 狀態管理

### 6.1 Heartbeat 狀態機

```
┌──────────────┐
│ UNINITIALIZED│ (producer 啟動,heartbeat manager 建立)
└──────┬───────┘
       │ transactionManager.initTransactions()
       v
┌──────────────┐
│   WAITING    │ (等待 producer ID 和 coordinator)
└──────┬───────┘
       │ hasProducerId() && coordinator != null
       v
┌──────────────┐
│    READY     │ (可以發送 heartbeat)
└──────┬───────┘
       │ timer expired && !requestInFlight
       v
┌──────────────┐
│  IN_FLIGHT   │ (heartbeat 請求已發送)
└──────┬───────┘
       │
  ┌────┴────┐
  │         │
  v         v
SUCCESS   ERROR
  │         │
  v         └───┬────────┬────────┐
READY           │        │        │
(reset timer) FATAL  RETRIABLE  UNSUPPORTED
                │        │        │
                v        v        v
            FENCED   WAITING   DISABLED
```

### 6.2 與 Transaction 狀態互動

```
Transaction State         Heartbeat Action
─────────────────────────────────────────────
UNINITIALIZED            Don't send (no producer ID)
INITIALIZING             Don't send (waiting for ID)
READY                    Send heartbeat
IN_TRANSACTION           Send heartbeat
PREPARED_TRANSACTION     Send heartbeat
COMMITTING_TRANSACTION   Send heartbeat
ABORTING_TRANSACTION     Send heartbeat
ABORTABLE_ERROR          Don't send (transaction error)
FATAL_ERROR              Don't send (producer fenced)
```

## 7. 效能考量

### 7.1 資源使用

```
記憶體:
- ProducerHeartbeatRequestManager: ~200 bytes
- Timer + state: ~100 bytes
- 總計: ~300 bytes per producer

網絡:
- Request size: ~32 bytes (producerId + epoch)
- Response size: ~20 bytes (error + interval)
- 預設頻率: 每 3.3 秒 (sessionTimeout=10s, interval=10s/3)
- Bandwidth: ~16 bytes/sec per producer (可忽略)

CPU:
- shouldSendHeartbeat(): < 0.01ms (timer check)
- handleHeartbeatResponse(): < 0.1ms
- 影響: 可忽略
```

### 7.2 與現有流程整合

```java
void runOnce() {
    long currentTimeMs = time.milliseconds();

    // 1. Handle transaction manager requests (existing)
    //    - InitProducerId
    //    - AddPartitionsToTxn
    //    - EndTxn
    //    Priority: HIGH
    long pollTimeout = maybeSendTransactionRequest(currentTimeMs);

    // 2. Handle producer data (existing)
    //    - ProduceRequest for user records
    //    Priority: HIGH
    pollTimeout = Math.min(pollTimeout, sendProducerData(currentTimeMs));

    // 3. Handle producer heartbeat (new)
    //    - ProducerHeartbeat
    //    Priority: MEDIUM (不阻塞 data/txn requests)
    pollTimeout = Math.min(pollTimeout, maybeSendProducerHeartbeat(currentTimeMs));

    // 4. Do I/O
    client.poll(pollTimeout, currentTimeMs);
}
```

**優先級設計:**
- Transaction requests (InitProducerId, EndTxn) 優先於 heartbeat
- Data requests (Produce) 優先於 heartbeat
- Heartbeat 在沒有其他緊急請求時才發送
- Heartbeat 不會阻塞用戶的 send() 或 commit()

## 8. Metrics

### 8.1 新增 Metrics

在 `SenderMetrics` 中新增：

```java
public class SenderMetrics {
    // ... 現有 metrics ...

    // Producer heartbeat metrics
    public final Sensor producerHeartbeatLatency;
    public final Sensor producerHeartbeatRate;
    public final Sensor producerHeartbeatErrorRate;

    public SenderMetrics(Metrics metrics, String metricGrpPrefix, Map<String, String> tags) {
        // ... 現有初始化 ...

        // Heartbeat latency
        this.producerHeartbeatLatency = metrics.sensor("producer-heartbeat-latency");
        this.producerHeartbeatLatency.add(
            metrics.metricName("producer-heartbeat-latency-avg",
                metricGrpName,
                "The average time taken for a producer heartbeat request",
                tags),
            new Avg()
        );
        this.producerHeartbeatLatency.add(
            metrics.metricName("producer-heartbeat-latency-max",
                metricGrpName,
                "The max time taken for a producer heartbeat request",
                tags),
            new Max()
        );

        // Heartbeat rate
        this.producerHeartbeatRate = metrics.sensor("producer-heartbeat-rate");
        this.producerHeartbeatRate.add(
            metrics.metricName("producer-heartbeat-rate",
                metricGrpName,
                "The number of producer heartbeats per second",
                tags),
            new Rate()
        );

        // Heartbeat error rate
        this.producerHeartbeatErrorRate = metrics.sensor("producer-heartbeat-error-rate");
        this.producerHeartbeatErrorRate.add(
            metrics.metricName("producer-heartbeat-error-rate",
                metricGrpName,
                "The number of producer heartbeat errors per second",
                tags),
            new Rate()
        );
    }

    public void recordHeartbeatLatency(long latencyMs) {
        this.producerHeartbeatLatency.record(latencyMs);
        this.producerHeartbeatRate.record();
    }

    public void recordHeartbeatError() {
        this.producerHeartbeatErrorRate.record();
    }
}
```

### 8.2 Metrics 列表

| Metric Name | Type | Description |
|-------------|------|-------------|
| `producer-heartbeat-latency-avg` | Gauge | 平均 heartbeat 延遲 (ms) |
| `producer-heartbeat-latency-max` | Gauge | 最大 heartbeat 延遲 (ms) |
| `producer-heartbeat-rate` | Rate | Heartbeat 請求頻率 (requests/sec) |
| `producer-heartbeat-error-rate` | Rate | Heartbeat 錯誤頻率 (errors/sec) |
| `producer-heartbeat-last-send-ms` | Gauge | 上次發送 heartbeat 時間 |
| `producer-heartbeat-last-response-ms` | Gauge | 上次收到 response 時間 |

## 9. 日誌

### 9.1 日誌級別

```java
// INFO: 重要狀態變更
log.info("Producer heartbeat enabled with session timeout {}ms", sessionTimeoutMs);
log.info("Updating heartbeat interval from {}ms to {}ms", oldInterval, newInterval);
log.info("Producer heartbeat disabled due to unsupported version");

// WARN: 可能的問題
log.warn("Producer session may have expired on server side");
log.warn("Heartbeat failed with unexpected error: {}", error);

// DEBUG: 詳細流程
log.debug("Heartbeat failed due to coordinator disconnect, will retry");
log.debug("Sending producer heartbeat to {}", coordinator);

// TRACE: 非常詳細
log.trace("Heartbeat succeeded, next heartbeat in {}ms", intervalMs);
log.trace("Checking if should send heartbeat: timer={}ms, inFlight={}",
         remainingMs, requestInFlight);
```

## 10. 測試策略

### 10.1 單元測試

```java
/**
 * Test ProducerHeartbeatRequestManager behavior
 */
public class ProducerHeartbeatRequestManagerTest {

    @Test
    public void testHeartbeatDisabledForNonTransactionalProducer() {
        // Given: non-transactional producer
        TransactionManager txnManager = null;

        // When: create heartbeat manager
        ProducerHeartbeatRequestManager manager =
            new ProducerHeartbeatRequestManager(logContext, time, 10000, txnManager);

        // Then: heartbeat is disabled
        assertFalse(manager.isEnabled());
        assertFalse(manager.shouldSendHeartbeat(time.milliseconds()));
    }

    @Test
    public void testHeartbeatEnabledForTransactionalProducer() {
        // Given: transactional producer with producer ID
        TransactionManager txnManager = createTransactionalManager();
        initProducerId(txnManager);

        // When: create heartbeat manager
        ProducerHeartbeatRequestManager manager =
            new ProducerHeartbeatRequestManager(logContext, time, 10000, txnManager);

        // Then: heartbeat is enabled
        assertTrue(manager.isEnabled());
    }

    @Test
    public void testShouldSendHeartbeatWhenTimerExpired() {
        // Given: heartbeat manager with initial interval 3333ms
        ProducerHeartbeatRequestManager manager = createManager();

        // When: time advances past interval
        time.sleep(3400);

        // Then: should send heartbeat
        assertTrue(manager.shouldSendHeartbeat(time.milliseconds()));
    }

    @Test
    public void testHandleSuccessfulHeartbeatResponse() {
        // Given: heartbeat request sent
        ProducerHeartbeatRequestManager manager = createManager();
        manager.sendHeartbeat(time.milliseconds());

        // When: receive successful response with new interval
        ProducerHeartbeatResponse response = createSuccessResponse(5000);
        manager.handleHeartbeatResponse(response, time.milliseconds());

        // Then: interval updated and timer reset
        assertEquals(5000, manager.heartbeatIntervalMs());
        assertFalse(manager.shouldSendHeartbeat(time.milliseconds()));
    }

    @Test
    public void testHandleProducerFencedError() {
        // Given: heartbeat manager
        ProducerHeartbeatRequestManager manager = createManager();

        // When: receive PRODUCER_FENCED error
        ProducerHeartbeatResponse response =
            createErrorResponse(Errors.PRODUCER_FENCED);
        manager.handleHeartbeatResponse(response, time.milliseconds());

        // Then: transaction manager transitioned to fatal error
        assertTrue(txnManager.hasFatalError());
        assertTrue(manager.lastError() instanceof ProducerFencedException);
    }

    @Test
    public void testHandleCoordinatorNotAvailable() {
        // Given: heartbeat manager
        ProducerHeartbeatRequestManager manager = createManager();

        // When: receive COORDINATOR_NOT_AVAILABLE error
        ProducerHeartbeatResponse response =
            createErrorResponse(Errors.COORDINATOR_NOT_AVAILABLE);
        manager.handleHeartbeatResponse(response, time.milliseconds());

        // Then: coordinator marked unknown and immediate retry
        assertNull(txnManager.coordinator());
        assertTrue(manager.shouldSendHeartbeat(time.milliseconds()));
    }
}
```

### 10.2 整合測試

```java
/**
 * Integration test for producer heartbeat with real cluster
 */
public class ProducerHeartbeatIntegrationTest {

    @Test
    public void testHeartbeatKeepsTransactionAlive() throws Exception {
        // Given: transactional producer with short session timeout
        Properties props = new Properties();
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "test-txn-id");
        props.put(ProducerConfig.PRODUCER_HEARTBEAT_SESSION_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.TRANSACTION_TIMEOUT_MS_CONFIG, 60000);

        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        producer.initTransactions();

        // When: start long transaction
        producer.beginTransaction();
        producer.send(new ProducerRecord<>("test-topic", "key", "value"));

        // Then: transaction stays alive for longer than session timeout
        Thread.sleep(10000); // 10 seconds > 5 second session timeout

        // Should still be able to commit
        producer.commitTransaction();
    }

    @Test
    public void testProducerFencedWhenHeartbeatStops() throws Exception {
        // Given: transactional producer
        KafkaProducer<String, String> producer1 = createTransactionalProducer("txn-1");
        producer1.initTransactions();
        producer1.beginTransaction();

        // When: pause producer1 (simulate network partition)
        pauseHeartbeat(producer1);
        Thread.sleep(15000); // Wait for session timeout

        // And: another producer with same transactional.id initializes
        KafkaProducer<String, String> producer2 = createTransactionalProducer("txn-1");
        producer2.initTransactions(); // This will fence producer1

        // Then: producer1 cannot commit
        assertThrows(ProducerFencedException.class, () -> {
            producer1.commitTransaction();
        });
    }

    @Test
    public void testHeartbeatIntervalUpdatedByServer() throws Exception {
        // Given: producer with initial interval
        KafkaProducer<String, String> producer = createTransactionalProducer();
        producer.initTransactions();

        long initialInterval = getHeartbeatInterval(producer);

        // When: server updates interval in response
        // (simulated by reconfiguring broker)

        // Then: producer adopts new interval
        Thread.sleep(5000);
        long newInterval = getHeartbeatInterval(producer);

        // Interval may change based on server configuration
    }
}
```

## 11. 升級路徑

### 11.1 向後兼容性

```
Broker 不支援 PRODUCER_HEARTBEAT:
├─> Client 偵測到 UNSUPPORTED_VERSION
├─> 自動禁用 heartbeat
├─> Producer 正常運作 (使用 transaction.timeout.ms)
└─> 日誌記錄: "Coordinator does not support PRODUCER_HEARTBEAT"

Broker 支援 PRODUCER_HEARTBEAT:
├─> Client 發送 heartbeat
├─> Server 處理 heartbeat
└─> 使用 session.timeout.ms (快速失敗偵測)
```

### 11.2 滾動升級

```
階段 1: 升級 Broker
├─> 部署新的 broker 版本 (支援 PRODUCER_HEARTBEAT)
├─> 舊 client 仍使用 transaction.timeout.ms
└─> 無影響

階段 2: 升級 Client
├─> 部署新的 client 版本
├─> 自動啟用 heartbeat (for transactional producers)
├─> 新配置: producer.heartbeat.session.timeout.ms
└─> 獲得快速失敗偵測的好處
```

## 12. 與 Consumer Heartbeat 對比

| 特性 | Consumer Heartbeat | Producer Heartbeat |
|------|-------------------|-------------------|
| **適用範圍** | 所有 consumer groups | 僅 transactional producers |
| **發送位置** | Consumer thread | Sender (I/O) thread |
| **頻率控制** | Client 計算 (interval/3) | Server 指定 |
| **目標** | Group Coordinator | Transaction Coordinator |
| **Session timeout** | session.timeout.ms | producer.heartbeat.session.timeout.ms |
| **配置複雜度** | 2 個參數 (session + interval) | 1 個參數 (session) |
| **失敗處理** | Rebalance | Abort transaction |
| **Thread model** | Dedicated heartbeat thread (舊) / Background (新) | Sender thread |

## 13. 總結

### 13.1 設計要點

1. **最小侵入性**: 整合到現有的 Sender thread,不創建新 thread
2. **Server 控制**: Heartbeat interval 由 server 決定,簡化配置
3. **向後兼容**: 自動偵測 broker 版本,gracefully degrade
4. **錯誤處理**: 完整處理 fatal/retriable 錯誤
5. **效能優化**: 低開銷,不影響 data/transaction requests

### 13.2 配置建議

```properties
# 必須配置 (啟用 transactional producer)
transactional.id=my-app-instance-1

# 推薦配置
producer.heartbeat.session.timeout.ms=10000  # 10 秒快速失敗偵測
transaction.timeout.ms=300000                # 5 分鐘允許長 transaction

# 對比舊設計 (沒有 heartbeat)
# transaction.timeout.ms=10000  # 必須很短,導致長 transaction 失敗
```

### 13.3 實作檢查清單

- [ ] 實作 `ProducerHeartbeatRequestManager`
- [ ] 修改 `Sender` 整合 heartbeat 邏輯
- [ ] 新增配置 `producer.heartbeat.session.timeout.ms`
- [ ] 實作錯誤處理邏輯
- [ ] 新增 metrics 和監控
- [ ] 實作單元測試
- [ ] 實作整合測試
- [ ] 更新文檔和範例
- [ ] 效能測試和 benchmark
- [ ] KIP 文檔和社群討論
