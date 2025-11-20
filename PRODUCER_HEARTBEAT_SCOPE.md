# Producer Heartbeat - 適用範圍討論

## 問題: 哪些 Producer 需要 Heartbeat?

基於社群討論 (KAFKA-19853),核心問題是 **transactional producers** 缺乏獨立的 liveness check。

## 分析

### 1. Non-transactional Producers

```
情況: 普通的 producer (無 transaction)

是否需要 heartbeat?
❌ 不需要

原因:
- 沒有 "producer group" 概念
- Producer crash 不會 blocking 其他組件
- 不需要主動偵測失敗
- 沒有需要 abort 的資源
```

### 2. Idempotent-only Producers

```
情況: enable.idempotence=true, transactional.id=null

是否需要 heartbeat?
❌ 可能不需要

原因:
- 雖然有 Producer ID 和 sequence numbers
- 但沒有跨 partition 的 transaction state
- Producer crash 後,新的 instance 會得到新的 Producer ID
- 不會有遺留的 pending transaction
```

### 3. Transactional Producers ⭐

```
情況: transactional.id != null

是否需要 heartbeat?
✅ 需要!

原因:
- 有 pending transaction state 在 broker
- Producer crash 後,transaction 仍然 open
- Read-committed consumers 被 blocked
- 目前只能等 transaction.timeout.ms (可能很長)

問題:
┌─────────────────────────────────────────────────┐
│ transaction.timeout.ms 身兼二職:                │
│                                                 │
│ 1. 避免 consumer head-of-line blocking (要短)  │
│ 2. 允許長時間 transaction 處理 (要長)          │
│                                                 │
│ 矛盾! 無法同時滿足!                             │
└─────────────────────────────────────────────────┘

解決方案:
引入 session.timeout.ms + heartbeat
- session.timeout.ms: 快速失敗偵測 (10 秒)
- transaction.timeout.ms: 允許長 TX (5 分鐘)
- 解耦!
```

## Consumer 模型對比

```
Consumer Group 已經有這個設計:

session.timeout.ms (預設 45 秒)
  └─> Liveness check via heartbeat
  └─> 快速偵測 consumer crash

max.poll.interval.ms (預設 5 分鐘)
  └─> 允許長時間處理 poll() 的 records
  └─> 與 liveness check 解耦

這正是 transactional producer 需要的!
```

## 設計決策

### 方案 A: 僅支持 Transactional Producers (推薦)

```java
public class ProducerHeartbeatManager {
    public ProducerHeartbeatManager(
            HeartbeatConfig config,
            TransactionManager transactionManager,
            ...) {

        // 只有 transactional producer 才需要 heartbeat
        this.enabled = config.enabled &&
                       transactionManager != null &&
                       transactionManager.isTransactional();

        if (!enabled) {
            log.info("Producer heartbeat disabled: not a transactional producer");
            return;
        }

        // ... 初始化 heartbeat
    }
}
```

**配置:**
```properties
# 自動判斷,不需要額外配置
transactional.id=my-tx-id
producer.heartbeat.session.timeout.ms=10000      # 快速失敗偵測
transaction.timeout.ms=300000                    # 允許長 TX (5 分鐘)

# 如果沒有 transactional.id,heartbeat 自動禁用
```

**優點:**
- ✅ 解決實際問題 (transactional producers 的 liveness check)
- ✅ 不增加 non-transactional producers 的開銷
- ✅ 實作簡單,範圍明確
- ✅ 與 consumer 模型一致

**缺點:**
- ⚠️ 無法監控 non-transactional producers (但這不是問題,因為它們不需要)

### 方案 B: 支持所有 Producers (你原本的設計)

```java
public class ProducerHeartbeatManager {
    public ProducerHeartbeatManager(
            HeartbeatConfig config,
            TransactionManager transactionManager,
            ...) {

        // 所有 producer 都可以啟用 heartbeat
        this.enabled = config.enabled;

        if (!enabled) {
            log.info("Producer heartbeat disabled by configuration");
            return;
        }

        // ... 初始化 heartbeat
    }
}
```

**配置:**
```properties
# 需要明確啟用
producer.heartbeat.enabled=true
producer.heartbeat.interval.ms=60000

# 對 non-transactional producer 也生效
```

**優點:**
- ✅ 可以監控所有 producers 的存活狀態
- ✅ 統一的監控方案

**缺點:**
- ⚠️ 增加額外開銷 (即使對不需要的 producers)
- ⚠️ 沒有解決核心問題的針對性
- ⚠️ 可能給用戶造成困惑 (為什麼需要這個?)

## 建議

### 採用 **方案 A: 僅 Transactional Producers**

理由:
1. **解決實際問題**: 這是社群提出的真正需求
2. **最小化影響**: 不影響 99% 的普通 producers
3. **語義清晰**: 與 consumer group 的 session.timeout.ms 對應
4. **效能優化**: 減少不必要的網絡請求

### 實作要點

```java
// 1. 只在 transactional producer 啟用
if (transactionManager != null && transactionManager.isTransactional()) {
    heartbeatManager = new ProducerHeartbeatManager(...);
    heartbeatManager.start();
}

// 2. 使用 TransactionManager 的 Producer ID
// 因為 transactional producer 一定有 Producer ID
Uuid instanceId = Uuid.randomUuid();  // 穩定標識
long producerId = transactionManager.producerIdAndEpoch().producerId;

// 3. 發送到 Transaction Coordinator (不是隨機 broker)
// Transactional producer 已經有 transaction coordinator
Node coordinator = transactionManager.coordinator();

// 4. 新增配置 (僅對 transactional producer 生效)
producer.heartbeat.session.timeout.ms=10000  // 快速失敗偵測
```

### Server 端實作

```java
// TransactionCoordinator 處理 heartbeat
class TransactionCoordinator {

    // 追蹤 transactional producers
    private Map<String, TransactionMetadata> transactions;

    // 追蹤 heartbeat
    private Map<Uuid, Long> producerHeartbeats;

    void handleProducerHeartbeat(ProducerHeartbeatRequest request) {
        Uuid instanceId = request.instanceId();
        String transactionalId = findTransactionalId(request.producerId());

        // 更新 heartbeat 時間
        producerHeartbeats.put(instanceId, time.milliseconds());

        // 重置 session timeout timer
        TransactionMetadata txnMetadata = transactions.get(transactionalId);
        if (txnMetadata != null) {
            txnMetadata.resetSessionTimeout();
        }
    }

    // 定期檢查 session timeout
    void checkSessionTimeouts() {
        long now = time.milliseconds();

        for (Map.Entry<Uuid, Long> entry : producerHeartbeats.entrySet()) {
            long lastHeartbeat = entry.getValue();

            if (now - lastHeartbeat > sessionTimeoutMs) {
                // Session timeout! Abort pending transaction
                abortTransactionDueToSessionTimeout(entry.getKey());
            }
        }
    }
}
```

## 與原設計的差異

| 項目 | 原設計 (通用) | 新建議 (僅 TX) |
|------|--------------|---------------|
| **適用範圍** | 所有 producers | 僅 transactional producers |
| **啟用條件** | `producer.heartbeat.enabled=true` | `transactional.id != null` (自動) |
| **Coordinator** | Hash-based 選擇 | Transaction Coordinator |
| **Producer ID** | UUID (自己生成) | Broker 分配的 Producer ID |
| **核心問題** | 監控 producer 存活 | 快速偵測 TX producer 失敗 |
| **配置參數** | `heartbeat.interval.ms` | `session.timeout.ms` |

## 結論

基於社群的實際需求和問題分析,建議:

1. **專注於 Transactional Producers**
   - 這是真正需要 liveness check 的場景
   - 解決 transaction.timeout.ms 身兼二職的問題

2. **參考 Consumer Group 設計**
   - `session.timeout.ms`: 快速失敗偵測
   - `transaction.timeout.ms`: 允許長時間處理
   - 兩者解耦

3. **使用 Transaction Coordinator**
   - Transactional producer 已經有 coordinator
   - 不需要額外的 coordinator 選擇邏輯
   - 簡化實作

4. **未來擴展**
   - 如果未來有需求,可以擴展到所有 producers
   - 但現在應該專注於解決核心問題
