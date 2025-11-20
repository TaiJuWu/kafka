# Producer Heartbeat - Client 端詳細設計

## 架構概覽

```
KafkaProducer
    │
    ├─> ProducerHeartbeatManager (NEW)
    │       │
    │       ├─> ProducerHeartbeatSender (Thread)
    │       ├─> HeartbeatState
    │       └─> HeartbeatMetrics
    │
    └─> Sender (Existing)
```

## 核心類設計

### 1. ProducerHeartbeatManager

負責管理整個 heartbeat 生命週期的主要類。

```java
package org.apache.kafka.clients.producer.internals;

import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.Uuid;
import org.slf4j.Logger;

import java.io.Closeable;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages producer heartbeat lifecycle.
 *
 * Thread Safety: This class is thread-safe. All mutable state is protected
 * or uses atomic operations.
 */
public class ProducerHeartbeatManager implements Closeable {

    private final Logger log;
    private final Time time;
    private final HeartbeatConfig config;
    private final ProducerHeartbeatSender sender;
    private final HeartbeatState state;
    private final HeartbeatMetrics metrics;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Producer identification
    private final Uuid producerId;        // Unique instance ID
    private final String clientId;        // Client ID
    private final String version;         // Producer version
    private final String hostname;        // Hostname

    /**
     * Create a new ProducerHeartbeatManager.
     *
     * @param config Heartbeat configuration
     * @param clientId Client ID
     * @param time Time instance
     * @param logContext Log context
     * @param metrics Producer metrics
     */
    public ProducerHeartbeatManager(
            HeartbeatConfig config,
            String clientId,
            Time time,
            LogContext logContext,
            ProducerMetrics metrics) {

        this.log = logContext.logger(ProducerHeartbeatManager.class);
        this.time = time;
        this.config = config;
        this.clientId = clientId;

        // Generate unique producer ID
        this.producerId = Uuid.randomUuid();
        this.version = AppInfoParser.getVersion();
        this.hostname = getHostname();

        // Initialize components
        this.state = new HeartbeatState(time);
        this.metrics = new HeartbeatMetrics(metrics);
        this.sender = new ProducerHeartbeatSender(
            config,
            this,
            time,
            logContext
        );

        log.info("Created ProducerHeartbeatManager with ID: {}", producerId);
    }

    /**
     * Start the heartbeat thread.
     */
    public void start() {
        if (!config.enabled) {
            log.info("Producer heartbeat is disabled");
            return;
        }

        sender.start();
        state.markActive();
        log.info("Started producer heartbeat with interval {}ms",
            config.intervalMs);
    }

    /**
     * Stop the heartbeat thread gracefully.
     * Sends final STOPPING heartbeat before shutdown.
     */
    @Override
    public void close() {
        close(Duration.ofSeconds(30));
    }

    public void close(Duration timeout) {
        if (closed.compareAndSet(false, true)) {
            log.info("Closing ProducerHeartbeatManager");

            try {
                // Mark as stopping and send final heartbeat
                state.markStopping();
                sender.sendImmediateHeartbeat();

                // Wait for sender thread to finish
                sender.close(timeout);

            } catch (Exception e) {
                log.error("Error during heartbeat manager shutdown", e);
            } finally {
                metrics.close();
            }
        }
    }

    /**
     * Force send a heartbeat immediately (for testing or manual control).
     */
    public void sendHeartbeat() {
        if (!closed.get() && config.enabled) {
            sender.sendImmediateHeartbeat();
        }
    }

    /**
     * Get the producer ID.
     */
    public Uuid producerId() {
        return producerId;
    }

    /**
     * Get current heartbeat state.
     */
    public ProducerState currentState() {
        return state.currentState();
    }

    /**
     * Get last successful heartbeat timestamp.
     */
    public long lastHeartbeatTime() {
        return state.lastSuccessfulHeartbeat();
    }

    /**
     * Check if heartbeat is healthy.
     */
    public boolean isHealthy() {
        if (!config.enabled) {
            return true;
        }

        long lastHeartbeat = state.lastSuccessfulHeartbeat();
        long now = time.milliseconds();
        return (now - lastHeartbeat) < config.sessionTimeoutMs;
    }

    // Package-private for sender thread
    HeartbeatState state() {
        return state;
    }

    String clientId() {
        return clientId;
    }

    String version() {
        return version;
    }

    String hostname() {
        return hostname;
    }

    HeartbeatMetrics metrics() {
        return metrics;
    }

    private static String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
```

### 2. ProducerHeartbeatSender (Background Thread)

後台執行緒,負責實際發送 heartbeat。

```java
package org.apache.kafka.clients.producer.internals;

import org.apache.kafka.clients.ClientRequest;
import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.clients.KafkaClient;
import org.apache.kafka.clients.RequestCompletionHandler;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.requests.ProducerHeartbeatRequest;
import org.apache.kafka.common.requests.ProducerHeartbeatResponse;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;

import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background thread that sends periodic heartbeats to the broker.
 *
 * This thread is similar to the Sender thread but much simpler - it only
 * sends heartbeats and doesn't handle batching or complex retry logic.
 */
public class ProducerHeartbeatSender extends Thread implements Closeable {

    private final Logger log;
    private final Time time;
    private final HeartbeatConfig config;
    private final ProducerHeartbeatManager manager;
    private final KafkaClient client;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean forceHeartbeat = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private volatile Node coordinator;
    private long lastHeartbeatSendMs = 0;
    private int failedAttempts = 0;

    public ProducerHeartbeatSender(
            HeartbeatConfig config,
            ProducerHeartbeatManager manager,
            KafkaClient client,
            Time time,
            LogContext logContext) {

        super("producer-heartbeat-sender");
        setDaemon(true);

        this.config = config;
        this.manager = manager;
        this.client = client;
        this.time = time;
        this.log = logContext.logger(ProducerHeartbeatSender.class);
    }

    @Override
    public void run() {
        log.info("Starting producer heartbeat sender thread");

        try {
            while (running.get()) {
                try {
                    runOnce();
                } catch (Exception e) {
                    log.error("Unexpected error in heartbeat sender", e);
                }
            }
        } finally {
            log.info("Producer heartbeat sender thread exiting");
            shutdownLatch.countDown();
        }
    }

    private void runOnce() {
        long now = time.milliseconds();

        // Check if we need to send heartbeat
        boolean shouldSend = forceHeartbeat.getAndSet(false) ||
            (now - lastHeartbeatSendMs >= config.intervalMs);

        if (!shouldSend) {
            // Sleep until next heartbeat time
            long sleepMs = config.intervalMs - (now - lastHeartbeatSendMs);
            time.sleep(Math.max(1, Math.min(sleepMs, 100)));
            return;
        }

        // Ensure we have a coordinator
        if (coordinator == null) {
            coordinator = findCoordinator();
            if (coordinator == null) {
                log.warn("Cannot find coordinator, will retry");
                time.sleep(config.retryBackoffMs);
                return;
            }
        }

        // Send heartbeat
        sendHeartbeat(coordinator);
        lastHeartbeatSendMs = now;
    }

    private void sendHeartbeat(Node node) {
        log.debug("Sending heartbeat to node {}", node);

        ProducerHeartbeatRequest.Builder builder =
            new ProducerHeartbeatRequest.Builder(
                manager.producerId(),
                manager.clientId(),
                time.milliseconds(),
                manager.currentState(),
                manager.version(),
                manager.hostname(),
                Collections.emptyList()  // metadata
            );

        ClientRequest request = client.newClientRequest(
            node.idString(),
            builder,
            time.milliseconds(),
            true,  // expectResponse
            (int) config.requestTimeoutMs,
            new HeartbeatResponseHandler()
        );

        try {
            client.send(request, time.milliseconds());
            manager.metrics().recordHeartbeatSend();

        } catch (Exception e) {
            log.error("Error sending heartbeat", e);
            handleSendError(e);
        }
    }

    private class HeartbeatResponseHandler implements RequestCompletionHandler {
        @Override
        public void onComplete(ClientResponse response) {
            long now = time.milliseconds();

            if (response.disconnected()) {
                log.warn("Heartbeat request disconnected from {}",
                    response.destination());
                handleDisconnect();
                return;
            }

            if (response.versionMismatch() != null) {
                log.error("Heartbeat request version mismatch: {}",
                    response.versionMismatch());
                failedAttempts++;
                return;
            }

            ProducerHeartbeatResponse hbResponse =
                (ProducerHeartbeatResponse) response.responseBody();

            Errors error = hbResponse.error();
            if (error == Errors.NONE) {
                // Success
                manager.state().recordSuccessfulHeartbeat(now);
                manager.metrics().recordHeartbeatSuccess();
                failedAttempts = 0;

                log.trace("Heartbeat succeeded");

            } else if (error == Errors.NOT_COORDINATOR) {
                log.info("Broker {} is not the coordinator, will rediscover",
                    response.destination());
                coordinator = null;
                failedAttempts = 0;

            } else {
                log.warn("Heartbeat failed with error: {}", error);
                manager.metrics().recordHeartbeatFailure();
                failedAttempts++;
            }
        }
    }

    private Node findCoordinator() {
        // Use consistent hashing to select coordinator
        // Hash producer ID and mod by number of brokers
        List<Node> nodes = client.cluster().nodes();
        if (nodes.isEmpty()) {
            log.warn("No brokers available");
            return null;
        }

        int hash = Math.abs(manager.producerId().hashCode());
        int index = hash % nodes.size();
        Node coordinator = nodes.get(index);

        log.info("Selected coordinator: {} (hash={}, index={})",
            coordinator, hash, index);

        return coordinator;
    }

    private void handleDisconnect() {
        coordinator = null;
        failedAttempts++;
        manager.metrics().recordHeartbeatFailure();
    }

    private void handleSendError(Exception e) {
        failedAttempts++;
        manager.metrics().recordHeartbeatFailure();

        if (failedAttempts >= config.maxRetries) {
            log.error("Heartbeat failed after {} attempts, giving up for this round",
                failedAttempts);
            failedAttempts = 0;
        }
    }

    /**
     * Request immediate heartbeat send (wakes up sender thread).
     */
    public void sendImmediateHeartbeat() {
        forceHeartbeat.set(true);
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(30));
    }

    public void close(Duration timeout) {
        log.info("Shutting down heartbeat sender");
        running.set(false);

        try {
            if (!shutdownLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Heartbeat sender did not shutdown within {}ms",
                    timeout.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for heartbeat sender shutdown");
        }
    }
}
```

### 3. HeartbeatState

追蹤 heartbeat 狀態。

```java
package org.apache.kafka.clients.producer.internals;

import org.apache.kafka.common.utils.Time;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the state of producer heartbeats.
 *
 * Thread-safe: All state is atomic.
 */
public class HeartbeatState {

    private final Time time;
    private final AtomicReference<ProducerState> currentState;
    private final AtomicLong lastSuccessfulHeartbeatMs;
    private final AtomicLong lastAttemptedHeartbeatMs;
    private final AtomicLong totalHeartbeatsSent;
    private final AtomicLong totalHeartbeatsFailed;

    public HeartbeatState(Time time) {
        this.time = time;
        this.currentState = new AtomicReference<>(ProducerState.ACTIVE);
        this.lastSuccessfulHeartbeatMs = new AtomicLong(time.milliseconds());
        this.lastAttemptedHeartbeatMs = new AtomicLong(time.milliseconds());
        this.totalHeartbeatsSent = new AtomicLong(0);
        this.totalHeartbeatsFailed = new AtomicLong(0);
    }

    public void markActive() {
        currentState.set(ProducerState.ACTIVE);
    }

    public void markStopping() {
        currentState.set(ProducerState.STOPPING);
    }

    public void markError() {
        currentState.set(ProducerState.ERROR);
    }

    public ProducerState currentState() {
        return currentState.get();
    }

    public void recordSuccessfulHeartbeat(long timestamp) {
        lastSuccessfulHeartbeatMs.set(timestamp);
        lastAttemptedHeartbeatMs.set(timestamp);
        totalHeartbeatsSent.incrementAndGet();
    }

    public void recordFailedHeartbeat(long timestamp) {
        lastAttemptedHeartbeatMs.set(timestamp);
        totalHeartbeatsFailed.incrementAndGet();
    }

    public long lastSuccessfulHeartbeat() {
        return lastSuccessfulHeartbeatMs.get();
    }

    public long lastAttemptedHeartbeat() {
        return lastAttemptedHeartbeatMs.get();
    }

    public long totalSent() {
        return totalHeartbeatsSent.get();
    }

    public long totalFailed() {
        return totalHeartbeatsFailed.get();
    }

    /**
     * Check if heartbeat is overdue based on session timeout.
     */
    public boolean isOverdue(long sessionTimeoutMs) {
        long now = time.milliseconds();
        return (now - lastSuccessfulHeartbeatMs.get()) > sessionTimeoutMs;
    }
}

/**
 * Producer state for heartbeat.
 */
public enum ProducerState {
    ACTIVE((byte) 0),
    STOPPING((byte) 1),
    ERROR((byte) 2);

    private final byte id;

    ProducerState(byte id) {
        this.id = id;
    }

    public byte id() {
        return id;
    }

    public static ProducerState fromId(byte id) {
        switch (id) {
            case 0: return ACTIVE;
            case 1: return STOPPING;
            case 2: return ERROR;
            default: throw new IllegalArgumentException("Unknown state: " + id);
        }
    }
}
```

### 4. HeartbeatConfig

配置類。

```java
package org.apache.kafka.clients.producer.internals;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;

import java.util.Map;

/**
 * Configuration for producer heartbeat.
 */
public class HeartbeatConfig {

    public static final String ENABLED_CONFIG = "producer.heartbeat.enabled";
    public static final String INTERVAL_MS_CONFIG = "producer.heartbeat.interval.ms";
    public static final String SESSION_TIMEOUT_MS_CONFIG = "producer.heartbeat.session.timeout.ms";
    public static final String REQUEST_TIMEOUT_MS_CONFIG = "producer.heartbeat.request.timeout.ms";
    public static final String MAX_RETRIES_CONFIG = "producer.heartbeat.max.retries";
    public static final String RETRY_BACKOFF_MS_CONFIG = "producer.heartbeat.retry.backoff.ms";

    // Defaults
    public static final boolean DEFAULT_ENABLED = true;
    public static final long DEFAULT_INTERVAL_MS = 60_000;        // 60 seconds
    public static final long DEFAULT_SESSION_TIMEOUT_MS = 180_000; // 3 minutes
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 30_000;  // 30 seconds
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final long DEFAULT_RETRY_BACKOFF_MS = 1000;      // 1 second

    public final boolean enabled;
    public final long intervalMs;
    public final long sessionTimeoutMs;
    public final long requestTimeoutMs;
    public final int maxRetries;
    public final long retryBackoffMs;

    public HeartbeatConfig(Map<String, ?> configs) {
        AbstractConfig config = new AbstractConfig(configDef(), configs, false);

        this.enabled = config.getBoolean(ENABLED_CONFIG);
        this.intervalMs = config.getLong(INTERVAL_MS_CONFIG);
        this.sessionTimeoutMs = config.getLong(SESSION_TIMEOUT_MS_CONFIG);
        this.requestTimeoutMs = config.getLong(REQUEST_TIMEOUT_MS_CONFIG);
        this.maxRetries = config.getInt(MAX_RETRIES_CONFIG);
        this.retryBackoffMs = config.getLong(RETRY_BACKOFF_MS_CONFIG);

        validate();
    }

    private void validate() {
        if (sessionTimeoutMs < intervalMs) {
            throw new IllegalArgumentException(
                "Session timeout must be >= interval");
        }
        if (requestTimeoutMs > intervalMs) {
            throw new IllegalArgumentException(
                "Request timeout must be < interval");
        }
    }

    public static ConfigDef configDef() {
        return new ConfigDef()
            .define(ENABLED_CONFIG,
                    ConfigDef.Type.BOOLEAN,
                    DEFAULT_ENABLED,
                    ConfigDef.Importance.MEDIUM,
                    "Whether to enable producer heartbeat")
            .define(INTERVAL_MS_CONFIG,
                    ConfigDef.Type.LONG,
                    DEFAULT_INTERVAL_MS,
                    ConfigDef.Importance.MEDIUM,
                    "Heartbeat interval in milliseconds")
            .define(SESSION_TIMEOUT_MS_CONFIG,
                    ConfigDef.Type.LONG,
                    DEFAULT_SESSION_TIMEOUT_MS,
                    ConfigDef.Importance.MEDIUM,
                    "Session timeout in milliseconds")
            .define(REQUEST_TIMEOUT_MS_CONFIG,
                    ConfigDef.Type.LONG,
                    DEFAULT_REQUEST_TIMEOUT_MS,
                    ConfigDef.Importance.LOW,
                    "Request timeout in milliseconds")
            .define(MAX_RETRIES_CONFIG,
                    ConfigDef.Type.INT,
                    DEFAULT_MAX_RETRIES,
                    ConfigDef.Importance.LOW,
                    "Maximum retry attempts")
            .define(RETRY_BACKOFF_MS_CONFIG,
                    ConfigDef.Type.LONG,
                    DEFAULT_RETRY_BACKOFF_MS,
                    ConfigDef.Importance.LOW,
                    "Retry backoff in milliseconds");
    }
}
```

### 5. HeartbeatMetrics

JMX 指標。

```java
package org.apache.kafka.clients.producer.internals;

import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Avg;
import org.apache.kafka.common.metrics.stats.Count;
import org.apache.kafka.common.metrics.stats.Max;
import org.apache.kafka.common.metrics.stats.Rate;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for producer heartbeat.
 */
public class HeartbeatMetrics implements Closeable {

    private final Metrics metrics;
    private final String metricGrpName = "producer-heartbeat-metrics";

    private final Sensor heartbeatSendSensor;
    private final Sensor heartbeatSuccessSensor;
    private final Sensor heartbeatFailureSensor;

    private final AtomicLong lastHeartbeatTimestamp = new AtomicLong(0);

    public HeartbeatMetrics(ProducerMetrics producerMetrics) {
        this.metrics = producerMetrics.metrics();

        // Heartbeat send rate
        this.heartbeatSendSensor = metrics.sensor("heartbeat-send");
        heartbeatSendSensor.add(
            metrics.metricName("heartbeat-send-rate", metricGrpName,
                "Heartbeat send rate"),
            new Rate()
        );
        heartbeatSendSensor.add(
            metrics.metricName("heartbeat-send-total", metricGrpName,
                "Total heartbeats sent"),
            new Count()
        );

        // Heartbeat success rate
        this.heartbeatSuccessSensor = metrics.sensor("heartbeat-success");
        heartbeatSuccessSensor.add(
            metrics.metricName("heartbeat-success-rate", metricGrpName,
                "Heartbeat success rate"),
            new Rate()
        );

        // Heartbeat failure rate
        this.heartbeatFailureSensor = metrics.sensor("heartbeat-failure");
        heartbeatFailureSensor.add(
            metrics.metricName("heartbeat-failure-rate", metricGrpName,
                "Heartbeat failure rate"),
            new Rate()
        );
        heartbeatFailureSensor.add(
            metrics.metricName("heartbeat-failure-total", metricGrpName,
                "Total heartbeat failures"),
            new Count()
        );

        // Last heartbeat timestamp
        MetricName lastHbMetric = metrics.metricName(
            "last-heartbeat-timestamp", metricGrpName,
            "Timestamp of last successful heartbeat");
        metrics.addMetric(lastHbMetric, (config, now) -> lastHeartbeatTimestamp.get());
    }

    public void recordHeartbeatSend() {
        heartbeatSendSensor.record();
    }

    public void recordHeartbeatSuccess() {
        heartbeatSuccessSensor.record();
        lastHeartbeatTimestamp.set(System.currentTimeMillis());
    }

    public void recordHeartbeatFailure() {
        heartbeatFailureSensor.record();
    }

    @Override
    public void close() {
        // Metrics are owned by ProducerMetrics, no cleanup needed
    }
}
```

## 整合到 KafkaProducer

在 KafkaProducer 中添加:

```java
public class KafkaProducer<K, V> implements Producer<K, V> {

    // ... existing fields ...

    private final ProducerHeartbeatManager heartbeatManager;  // NEW

    public KafkaProducer(Map<String, Object> configs,
                        Serializer<K> keySerializer,
                        Serializer<V> valueSerializer,
                        ...) {
        // ... existing initialization ...

        // Initialize heartbeat manager
        HeartbeatConfig heartbeatConfig = new HeartbeatConfig(configs);
        this.heartbeatManager = new ProducerHeartbeatManager(
            heartbeatConfig,
            clientId,
            time,
            logContext,
            producerMetrics
        );

        // Start heartbeat
        heartbeatManager.start();

        // ... rest of initialization ...
    }

    @Override
    public void close(Duration timeout) {
        // ... existing close logic ...

        // Close heartbeat manager
        heartbeatManager.close(timeout);

        // ... rest of close logic ...
    }

    // NEW: Expose heartbeat info
    public String producerInstanceId() {
        return heartbeatManager.producerId().toString();
    }

    public boolean isHeartbeatHealthy() {
        return heartbeatManager.isHealthy();
    }
}
```

## 使用範例

```java
// 1. 基本使用 - 自動啟用
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9092");
props.put("producer.heartbeat.enabled", "true");        // 默認 true
props.put("producer.heartbeat.interval.ms", "60000");   // 60 seconds

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
// Heartbeat 自動開始發送

// 發送數據
producer.send(new ProducerRecord<>("topic", "key", "value"));

// Graceful shutdown - 會發送 STOPPING heartbeat
producer.close();

// 2. 禁用 heartbeat
props.put("producer.heartbeat.enabled", "false");
KafkaProducer<String, String> producer2 = new KafkaProducer<>(props);
// 不會發送 heartbeat

// 3. 檢查健康狀態
if (!producer.isHeartbeatHealthy()) {
    log.warn("Producer heartbeat is unhealthy");
}

// 4. 獲取 producer instance ID (用於監控)
String instanceId = producer.producerInstanceId();
log.info("Producer instance ID: {}", instanceId);
```

## 錯誤處理

### 1. Coordinator 不可用
```
- 症狀: NOT_COORDINATOR 錯誤
- 處理: 重新發現 coordinator
- 重試: 立即重試,使用新的 coordinator
```

### 2. 網絡斷線
```
- 症狀: DisconnectException
- 處理: 標記 coordinator 為 null,下次重新發現
- 重試: 按配置的 retry.backoff.ms 等待後重試
```

### 3. 超時
```
- 症狀: REQUEST_TIMED_OUT
- 處理: 記錄失敗,但不影響主流程
- 重試: 下個週期自動重試
```

### 4. 版本不匹配
```
- 症狀: UNSUPPORTED_VERSION
- 處理: 記錄錯誤,禁用 heartbeat
- 重試: 不重試
```

## 線程安全

- `ProducerHeartbeatManager`: Thread-safe,使用 atomic 操作
- `ProducerHeartbeatSender`: 單獨的後台線程,不與 Sender 線程交互
- `HeartbeatState`: 完全使用 atomic 變量,天然線程安全
- `HeartbeatMetrics`: 通過 Metrics 框架保證線程安全

## 性能考量

- **內存**: 每個 producer 額外約 ~1KB (主要是線程棧空間)
- **CPU**: 非常低,每分鐘一次 heartbeat,幾乎可忽略
- **網絡**: 每個 heartbeat < 500 bytes,1000 producers = ~8KB/min
- **延遲**: 不影響主發送路徑,完全異步

## 測試計劃

### 單元測試
- HeartbeatState 狀態轉換
- HeartbeatConfig 驗證邏輯
- Coordinator 選擇算法

### 整合測試
- 正常 heartbeat 流程
- Coordinator failover
- 網絡分區恢復
- Graceful shutdown

### 壓力測試
- 1000 producers 同時運行
- Broker 重啟場景
- 網絡延遲/丟包
