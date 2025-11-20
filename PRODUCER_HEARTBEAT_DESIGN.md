# Producer Heartbeat 設計文件

## 目標

為 **Transactional Producers** 提供獨立的 liveness check 機制，解決 `transaction.timeout.ms` 身兼二職的問題。

## 問題背景

根據 [KAFKA-19853](https://issues.apache.org/jira/browse/KAFKA-19853)，目前 transactional producers 存在以下問題：

```
當 transactional producer crash 時：
1. Transaction 保持 open 狀態
2. Read-committed consumers 被 blocked (head-of-line blocking)
3. 只能等待 transaction.timeout.ms 才能 abort

問題：transaction.timeout.ms 身兼二職
├─ 需要短 timeout (快速偵測 producer failure，避免 consumer blocking)
└─ 需要長 timeout (允許長時間 transaction 處理)
矛盾！無法同時滿足！
```

**解決方案：** 引入 `session.timeout.ms` + heartbeat 機制，將 liveness check 與 transaction timeout 解耦。

## 設計範圍

### 僅支持 Transactional Producers

**理由：**
- Non-transactional producers 不需要 liveness check（沒有 blocking 問題）
- Idempotent-only producers 沒有跨 partition 的 transaction state
- 專注解決實際問題，避免不必要的開銷

**啟用條件：**
```java
transactional.id != null  // 自動啟用 heartbeat
```

## 架構設計

```
┌─────────────────────────────────┐
│     KafkaProducer               │
│  (transactional.id != null)     │
│                                 │
│  ┌───────────────────────────┐ │
│  │ TransactionManager        │ │
│  │  - producerId + epoch     │ │
│  │  - transactionCoordinator │ │
│  └──────────┬────────────────┘ │
│             │                   │
│  ┌──────────▼────────────────┐ │      ProducerHeartbeat
│  │ ProducerHeartbeatManager  │─┼──────RPC Request────────┐
│  │  - session.timeout.ms     │ │                          │
│  │  - HeartbeatRequestState  │ │                          │
│  └───────────────────────────┘ │                          │
└─────────────────────────────────┘                          │
                                                             │
┌────────────────────────────────────────────────────────────┼──────┐
│ Transaction Coordinator                                    ▼      │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ ProducerHeartbeatHandler                                     │ │
│  │  - 接收 heartbeat RPC                                         │ │
│  │  - 返回 heartbeatIntervalMs (server 控制)                     │ │
│  └──────────────────────────┬───────────────────────────────────┘ │
│                             │                                      │
│  ┌──────────────────────────▼───────────────────────────────────┐ │
│  │ __transaction_state topic                                    │ │
│  │  - TransactionMetadata (transactionalId -> producerId)       │ │
│  │  - Transaction state (Ongoing, PrepareCommit, etc.)          │ │
│  └──────────────────────────┬───────────────────────────────────┘ │
│                             │                                      │
│  ┌──────────────────────────▼───────────────────────────────────┐ │
│  │ In-Memory Heartbeat State                                    │ │
│  │  Map<ProducerId, ProducerHeartbeatState>                     │ │
│  │    - lastHeartbeatMs                                         │ │
│  │    - sessionTimeoutMs                                        │ │
│  │    - transactionalId                                         │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ Session Timeout Checker (定期執行)                           │ │
│  │  - 檢查 lastHeartbeatMs + sessionTimeoutMs < now             │ │
│  │  - 如果超時 → abort pending transaction                      │ │
│  └──────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────┘
```

### 關鍵設計決策

1. **使用 Transaction Coordinator**
   - Transactional producer 已經有 transaction coordinator
   - 不需要額外的 coordinator 選擇邏輯
   - 與 `__transaction_state` topic 自然整合

2. **使用 Broker 分配的 Producer ID**
   - Transactional producer 一定有 broker 分配的 Producer ID
   - 不需要額外的 UUID
   - 可以直接關聯到 transaction metadata

3. **Server 控制 Heartbeat Interval**
   - 參考 AsyncConsumer (新 Consumer Group Protocol)
   - Server 通過 response 返回 `heartbeatIntervalMs`
   - Client 不需要配置 `heartbeat.interval.ms`

4. **重用 `__transaction_state` Topic**
   - Transaction metadata 已經存儲在此 topic
   - Heartbeat 只是增強現有的 liveness check
   - Coordinator failover 時可以從 log 重建狀態

## RPC 協議

### ProducerHeartbeatRequest (API Key: 93)

```json
{
  "apiKey": 93,
  "type": "request",
  "listeners": ["broker"],
  "name": "ProducerHeartbeatRequest",
  "validVersions": "0",
  "flexibleVersions": "0+",
  "fields": [
    {
      "name": "ProducerId",
      "type": "int64",
      "versions": "0+",
      "entityType": "producerId",
      "about": "The producer ID assigned by the broker."
    },
    {
      "name": "ProducerEpoch",
      "type": "int64",
      "versions": "0+",
      "default": "-1",
      "about": "The producer epoch."
    }
  ]
}
```

**說明：**
- 使用 broker 分配的 Producer ID（transactional producer 一定有）
- 不需要額外的 instance ID（與 transaction state 綁定）
- 簡單、高效

### ProducerHeartbeatResponse (API Key: 93)

```json
{
  "apiKey": 93,
  "type": "response",
  "name": "ProducerHeartbeatResponse",
  "validVersions": "0",
  "flexibleVersions": "0+",
  "fields": [
    {
      "name": "ThrottleTimeMs",
      "type": "int32",
      "versions": "0+",
      "about": "The duration in milliseconds for which the request was throttled."
    },
    {
      "name": "ErrorCode",
      "type": "int16",
      "versions": "0+",
      "about": "The error code, or 0 if there was no error."
    },
    {
      "name": "ErrorMessage",
      "type": "string",
      "versions": "0+",
      "nullableVersions": "0+",
      "about": "The error message, or null if there was no error."
    },
    {
      "name": "HeartbeatIntervalMs",
      "type": "int32",
      "versions": "0+",
      "about": "The heartbeat interval in milliseconds (controlled by server)."
    }
  ]
}
```

**關鍵：** Server 通過 `HeartbeatIntervalMs` 控制 heartbeat 頻率，類似 AsyncConsumer 設計。

### 錯誤碼

| 錯誤碼 | 說明 | Client 處理 |
|--------|------|-------------|
| `NONE (0)` | 成功 | 更新 heartbeatIntervalMs |
| `NOT_COORDINATOR (16)` | 請求發送到錯誤的 coordinator | 重新查找 coordinator，快速重試 |
| `COORDINATOR_NOT_AVAILABLE (10)` | Coordinator 不可用 | 重新查找 coordinator，快速重試 |
| `INVALID_PRODUCER_ID_MAPPING (90)` | Producer ID 不存在 | Fatal error，停止 heartbeat |
| `PRODUCER_FENCED (91)` | Producer 被 fence | Fatal error，停止 heartbeat |
| `INVALID_PRODUCER_EPOCH (47)` | Epoch 錯誤 | 請求更新 epoch |
| `COORDINATOR_LOAD_IN_PROGRESS (14)` | Coordinator 加載中 | 指數退避重試 |

## Client 端設計

### ProducerHeartbeatManager

```java
/**
 * Producer Heartbeat Manager
 * 僅在 transactional producer 啟用
 */
public class ProducerHeartbeatManager implements Closeable {
    private final TransactionManager transactionManager;
    private final HeartbeatRequestState heartbeatState;
    private final long sessionTimeoutMs;
    private final NetworkClient networkClient;

    public ProducerHeartbeatManager(
            ProducerConfig config,
            TransactionManager transactionManager,
            NetworkClient networkClient,
            Time time,
            LogContext logContext) {

        // 只有 transactional producer 才啟用
        if (transactionManager == null || !transactionManager.isTransactional()) {
            throw new IllegalArgumentException(
                "Producer heartbeat is only supported for transactional producers");
        }

        this.transactionManager = transactionManager;
        this.sessionTimeoutMs = config.getLong("producer.heartbeat.session.timeout.ms");

        // HeartbeatRequestState 管理 heartbeat timing
        // heartbeatIntervalMs 由 server response 動態更新
        long retryBackoffMs = config.getLong(ProducerConfig.RETRY_BACKOFF_MS_CONFIG);
        long retryBackoffMaxMs = config.getLong(ProducerConfig.RETRY_BACKOFF_MAX_MS_CONFIG);

        this.heartbeatState = new HeartbeatRequestState(
            logContext,
            time,
            0,  // 初始 interval，之後由 server 更新
            retryBackoffMs,
            retryBackoffMaxMs,
            0.2  // jitter
        );

        this.networkClient = networkClient;
    }

    /**
     * 檢查是否需要發送 heartbeat
     */
    public boolean shouldSendHeartbeat(long currentTimeMs) {
        // 等待 Producer ID 分配完成
        if (!transactionManager.hasProducerId()) {
            return false;
        }

        // 等待 transaction coordinator 就緒
        if (!transactionManager.hasProducerIdAndEpoch()) {
            return false;
        }

        return heartbeatState.canSendRequest(currentTimeMs);
    }

    /**
     * 發送 heartbeat request
     */
    public void sendHeartbeat(long currentTimeMs) {
        ProducerIdAndEpoch idAndEpoch = transactionManager.producerIdAndEpoch();

        ProducerHeartbeatRequest.Builder builder =
            new ProducerHeartbeatRequest.Builder(
                new ProducerHeartbeatRequestData()
                    .setProducerId(idAndEpoch.producerId)
                    .setProducerEpoch(idAndEpoch.epoch)
            );

        Node coordinator = transactionManager.coordinator();

        ClientRequest request = networkClient.newClientRequest(
            coordinator.idString(),
            builder,
            currentTimeMs,
            true,
            (int) sessionTimeoutMs,
            new HeartbeatResponseHandler()
        );

        networkClient.send(request, currentTimeMs);
        heartbeatState.onSendAttempt(currentTimeMs);
        heartbeatState.resetTimer();
    }

    /**
     * 處理 heartbeat response
     */
    private class HeartbeatResponseHandler implements RequestCompletionHandler {
        @Override
        public void onComplete(ClientResponse response) {
            long currentTimeMs = response.receivedTimeMs();

            if (response.wasDisconnected()) {
                heartbeatState.onFailedAttempt(currentTimeMs);
                return;
            }

            ProducerHeartbeatResponse heartbeatResponse =
                (ProducerHeartbeatResponse) response.responseBody();

            Errors error = Errors.forCode(heartbeatResponse.errorCode());

            if (error == Errors.NONE) {
                // 成功：更新 heartbeat interval (由 server 控制)
                heartbeatState.updateHeartbeatIntervalMs(
                    heartbeatResponse.heartbeatIntervalMs()
                );
                heartbeatState.onSuccessfulAttempt(currentTimeMs);
            } else {
                handleError(error, heartbeatResponse.errorMessage(), currentTimeMs);
            }
        }
    }

    private void handleError(Errors error, String errorMessage, long currentTimeMs) {
        heartbeatState.onFailedAttempt(currentTimeMs);

        switch (error) {
            case NOT_COORDINATOR:
            case COORDINATOR_NOT_AVAILABLE:
                // Coordinator 變更，需要重新發現
                transactionManager.lookupCoordinator();
                heartbeatState.reset();  // 快速重試
                break;

            case PRODUCER_FENCED:
                // Producer 被 fence，停止 heartbeat
                log.error("Producer fenced: {}", errorMessage);
                transactionManager.handleFatalError(new ProducerFencedException(errorMessage));
                break;

            case INVALID_PRODUCER_EPOCH:
                // Epoch 錯誤
                log.error("Invalid producer epoch: {}", errorMessage);
                transactionManager.requestEpochUpdate();
                break;

            default:
                log.warn("Heartbeat failed with error: {} - {}", error, errorMessage);
                break;
        }
    }
}
```

### 整合到 KafkaProducer

```java
public class KafkaProducer<K, V> implements Producer<K, V> {
    private final TransactionManager transactionManager;
    private final ProducerHeartbeatManager heartbeatManager;  // 新增

    public KafkaProducer(Map<String, Object> configs, ...) {
        // ... 現有初始化代碼 ...

        // 只為 transactional producer 創建 heartbeat manager
        if (transactionManager != null && transactionManager.isTransactional()) {
            this.heartbeatManager = new ProducerHeartbeatManager(
                config,
                transactionManager,
                networkClient,
                time,
                logContext
            );
        } else {
            this.heartbeatManager = null;
        }
    }

    private void runOnce() {
        // ... 現有邏輯 ...

        // 發送 heartbeat (如果需要)
        if (heartbeatManager != null) {
            long currentTimeMs = time.milliseconds();
            if (heartbeatManager.shouldSendHeartbeat(currentTimeMs)) {
                heartbeatManager.sendHeartbeat(currentTimeMs);
            }
        }
    }
}
```

## Server 端設計

### TransactionCoordinator 擴展

```java
/**
 * Transaction Coordinator 處理 heartbeat
 */
public class TransactionCoordinator {
    // 現有的 transaction metadata (存儲在 __transaction_state topic)
    private final TransactionStateManager txnStateManager;

    // 追蹤 heartbeat 時間 (in-memory)
    private final ConcurrentHashMap<Long, ProducerHeartbeatState> producerHeartbeats;

    // Session timeout checker
    private final Timer sessionTimeoutChecker;

    static class ProducerHeartbeatState {
        long lastHeartbeatMs;
        long sessionTimeoutMs;
        String transactionalId;  // 關聯到 transaction metadata
    }

    /**
     * 處理 ProducerHeartbeatRequest
     */
    public ProducerHeartbeatResponse handleHeartbeat(
            ProducerHeartbeatRequest request,
            RequestContext context) {

        long producerId = request.data().producerId();
        long producerEpoch = request.data().producerEpoch();
        long currentTimeMs = time.milliseconds();

        // 從 __transaction_state 查找 transaction metadata
        Optional<TransactionMetadata> txnMetadata =
            txnStateManager.getTransactionByProducerId(producerId);

        if (txnMetadata.isEmpty()) {
            return new ProducerHeartbeatResponse(
                new ProducerHeartbeatResponseData()
                    .setErrorCode(Errors.INVALID_PRODUCER_ID_MAPPING.code())
                    .setErrorMessage("Unknown producer ID")
            );
        }

        TransactionMetadata metadata = txnMetadata.get();

        // 驗證 epoch
        if (metadata.producerEpoch() != producerEpoch) {
            return new ProducerHeartbeatResponse(
                new ProducerHeartbeatResponseData()
                    .setErrorCode(Errors.INVALID_PRODUCER_EPOCH.code())
                    .setErrorMessage("Producer epoch mismatch")
            );
        }

        // 更新 heartbeat 時間
        ProducerHeartbeatState heartbeatState =
            producerHeartbeats.computeIfAbsent(producerId, id ->
                new ProducerHeartbeatState());

        heartbeatState.lastHeartbeatMs = currentTimeMs;
        heartbeatState.transactionalId = metadata.transactionalId();
        heartbeatState.sessionTimeoutMs = getSessionTimeoutMs();

        // 返回 server 控制的 heartbeat interval
        int heartbeatIntervalMs = calculateHeartbeatInterval(
            heartbeatState.sessionTimeoutMs
        );

        return new ProducerHeartbeatResponse(
            new ProducerHeartbeatResponseData()
                .setErrorCode(Errors.NONE.code())
                .setHeartbeatIntervalMs(heartbeatIntervalMs)
        );
    }

    /**
     * 計算 heartbeat interval (server 控制)
     * 類似 consumer group，設為 session timeout 的 1/3
     */
    private int calculateHeartbeatInterval(long sessionTimeoutMs) {
        return (int) (sessionTimeoutMs / 3);
    }

    /**
     * 定期檢查 session timeout
     */
    public void checkSessionTimeouts() {
        long currentTimeMs = time.milliseconds();

        Iterator<Map.Entry<Long, ProducerHeartbeatState>> iterator =
            producerHeartbeats.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Long, ProducerHeartbeatState> entry = iterator.next();
            long producerId = entry.getKey();
            ProducerHeartbeatState state = entry.getValue();

            long elapsedMs = currentTimeMs - state.lastHeartbeatMs;

            if (elapsedMs > state.sessionTimeoutMs) {
                log.info("Producer {} session timeout (last heartbeat: {}ms ago), " +
                        "aborting pending transaction for transactionalId: {}",
                        producerId, elapsedMs, state.transactionalId);

                // 從 __transaction_state 獲取 transaction metadata
                Optional<TransactionMetadata> txnMetadata =
                    txnStateManager.getTransactionByProducerId(producerId);

                if (txnMetadata.isPresent()) {
                    // Abort pending transaction (寫入 __transaction_state)
                    abortTransactionDueToSessionTimeout(
                        txnMetadata.get(),
                        currentTimeMs
                    );
                }

                // 清理 heartbeat 狀態
                iterator.remove();
            }
        }
    }

    /**
     * 因 session timeout abort transaction
     */
    private void abortTransactionDueToSessionTimeout(
            TransactionMetadata txnMetadata,
            long currentTimeMs) {

        if (txnMetadata.state() != TransactionState.Ongoing) {
            // 不是 ongoing transaction，不需要 abort
            return;
        }

        log.info("Aborting transaction {} for producer {} due to session timeout",
                txnMetadata.transactionalId(), txnMetadata.producerId());

        // 更新 transaction state 到 PrepareAbort
        txnStateManager.appendTransactionToLog(
            txnMetadata.transactionalId(),
            metadata -> {
                metadata.prepareAbortOrCommit(TransactionResult.ABORT, currentTimeMs);
                return metadata;
            },
            error -> {
                if (error != Errors.NONE) {
                    log.error("Failed to abort transaction {} due to {}",
                            txnMetadata.transactionalId(), error);
                }
            }
        );

        // 發送 WriteTxnMarkers 到所有 partitions
        sendTxnMarkersToPartitions(txnMetadata, TransactionResult.ABORT);
    }
}
```

## 配置參數

### Client 端配置

```properties
# 必須設置 transactional.id 才會啟用 heartbeat
transactional.id=my-tx-id

# Session timeout (快速失敗偵測)
# 預設: 10 秒
producer.heartbeat.session.timeout.ms=10000

# Transaction timeout (允許長時間處理)
# 預設: 60 秒（可以設更長，如 5 分鐘）
transaction.timeout.ms=300000

# 注意: 不需要配置 heartbeat.interval.ms
# Server 會通過 response 動態控制 heartbeat 頻率
```

### Server 端配置

```properties
# Transaction coordinator 的 session timeout 範圍
producer.heartbeat.session.timeout.ms.min=5000    # 最小 5 秒
producer.heartbeat.session.timeout.ms.max=300000  # 最大 5 分鐘

# Session timeout checker 間隔
producer.heartbeat.timeout.check.interval.ms=3000  # 每 3 秒檢查一次
```

## 與 Consumer 模型對比

| 項目 | Classic Consumer | AsyncConsumer | Transactional Producer (本設計) |
|------|------------------|---------------|--------------------------------|
| **參數配置** | `session.timeout.ms`<br>`heartbeat.interval.ms` | Server 控制 interval | `session.timeout.ms`<br>Server 控制 interval |
| **Coordinator** | Group Coordinator | Group Coordinator | Transaction Coordinator |
| **狀態存儲** | `__consumer_offsets` | `__consumer_offsets` | `__transaction_state` |
| **Liveness check** | Heartbeat | Heartbeat | Heartbeat |
| **Timeout 解耦** | `session.timeout.ms`<br>`max.poll.interval.ms` | `max.poll.interval.ms` | `session.timeout.ms`<br>`transaction.timeout.ms` |

## 實作步驟

### Phase 1: RPC Protocol & Server 端基礎

1. ✅ 定義 `ProducerHeartbeatRequest` 和 `ProducerHeartbeatResponse`
2. ⬜ 實作 `TransactionCoordinator.handleHeartbeat()`
3. ⬜ 實作 session timeout checker
4. ⬜ 整合到 `__transaction_state` topic

### Phase 2: Client 端實作

1. ⬜ 實作 `ProducerHeartbeatManager`
2. ⬜ 整合到 `KafkaProducer`
3. ⬜ 實作錯誤處理和重試邏輯

### Phase 3: 測試與文檔

1. ⬜ 單元測試
2. ⬜ 整合測試
3. ⬜ 效能測試
4. ⬜ KIP 文檔

## 性能分析

### 中等規模 (100-1000 transactional producers)

**Heartbeat 頻率：**
```
假設：
- session.timeout.ms = 10s
- heartbeat.interval.ms = 3.3s (server 自動設為 session timeout / 3)
- 1000 transactional producers

每秒 heartbeat 數：
= 1000 producers / 3.3s
≈ 300 heartbeat/sec

分散到多個 transaction coordinators：
假設 3 個 brokers，hash-based 分散
= 300 / 3 ≈ 100 heartbeat/sec per broker
```

**資源消耗：**
- **CPU**: 極低 (100 RPC/sec per broker)
- **Memory**: 每個 producer ≈ 100 bytes (in-memory state)
  - 1000 producers ≈ 100 KB
- **Network**: 每個 heartbeat ≈ 100 bytes
  - 100 heartbeat/sec ≈ 10 KB/sec per broker

**結論：** 對於 1000 個 transactional producers，性能開銷可以忽略不計。

## 優點總結

1. **✅ 解決核心問題**
   - 快速偵測 transactional producer failure (10s)
   - 允許長時間 transaction 處理 (5min+)
   - 解除 read-committed consumers 的 blocking

2. **✅ 重用現有機制**
   - 使用 Transaction Coordinator
   - 整合 `__transaction_state` topic
   - 參考 AsyncConsumer 設計

3. **✅ 簡化配置**
   - 只需一個參數 `session.timeout.ms`
   - Server 動態控制 heartbeat 頻率

4. **✅ 高效實作**
   - In-memory heartbeat state
   - 低 CPU/網絡開銷
   - Coordinator failover 自動恢復

## 未來擴展

### 可能的優化

1. **動態調整 heartbeat interval**
   - Server 根據系統負載動態調整
   - 高負載時增加 interval，減少開銷

2. **批量處理 heartbeat**
   - 單個 coordinator 可能管理多個 producers
   - 批量檢查 session timeout

3. **Metrics 和監控**
   - Heartbeat 成功率
   - Session timeout 次數
   - Transaction abort 原因統計

## 總結

**核心設計原則：**
1. ✅ **專注 Transactional Producers** - 解決實際問題
2. ✅ **重用現有機制** - 使用 `__transaction_state` topic
3. ✅ **Server 控制** - Heartbeat interval 由 server 動態管理
4. ✅ **簡化配置** - 只需一個參數 `session.timeout.ms`
5. ✅ **與 Kafka 演進方向一致** - 參考 AsyncConsumer 設計

**解決的核心問題：**
- ⚡ 快速偵測 transactional producer failure (session.timeout.ms = 10s)
- ⏱️ 允許長時間 transaction 處理 (transaction.timeout.ms = 5min)
- 🔓 解除 read-committed consumers 的 blocking
- 🎯 兩個 timeout 解耦，各司其職！
