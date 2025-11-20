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
package org.apache.kafka.clients.producer.internals;

import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.message.ProducerHeartbeatRequestData;
import org.apache.kafka.common.requests.ProducerHeartbeatRequest;
import org.apache.kafka.common.requests.ProducerHeartbeatResponse;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;

import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Background thread that sends periodic heartbeats for a Kafka producer using RPC.
 *
 * This thread:
 * - Generates a unique producer ID on startup
 * - Sends heartbeat RPC requests at configured intervals
 * - Handles heartbeat failures with retries
 * - Sends a final STOPPING heartbeat on graceful shutdown
 * - Uses hash-based routing to select coordinator broker
 */
public class ProducerHeartbeatThread extends Thread {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_BACKOFF_MS = 1000;

    private final Logger log;
    private final Time time;
    private final String producerId;
    private final String clientId;
    private final long heartbeatIntervalMs;
    private final long sessionTimeoutMs;
    private final int maxRetries;
    private final long retryBackoffMs;
    private final String version;
    private final String host;
    private final Map<String, String> metadata;

    // Function to get available brokers
    private final Function<Void, List<Node>> brokerSupplier;

    // Function to send RPC request to a specific broker
    // Takes (Node, ProducerHeartbeatRequest) and returns ClientResponse
    private final Function<ProducerHeartbeatRequest.Builder, ClientResponse> requestSender;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong lastHeartbeatTime = new AtomicLong(0);
    private final AtomicLong heartbeatsSent = new AtomicLong(0);
    private final AtomicLong heartbeatsFailed = new AtomicLong(0);
    private final AtomicReference<ProducerHeartbeat.State> currentState =
            new AtomicReference<>(ProducerHeartbeat.State.ACTIVE);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public ProducerHeartbeatThread(
            String clientId,
            long heartbeatIntervalMs,
            long sessionTimeoutMs,
            BiFunction<String, byte[], CompletableFuture<Void>> sendCallback,
            LogContext logContext,
            Time time) {
        this(clientId, heartbeatIntervalMs, sessionTimeoutMs, DEFAULT_MAX_RETRIES,
             DEFAULT_RETRY_BACKOFF_MS, sendCallback, logContext, time);
    }

    public ProducerHeartbeatThread(
            String clientId,
            long heartbeatIntervalMs,
            long sessionTimeoutMs,
            int maxRetries,
            long retryBackoffMs,
            BiFunction<String, byte[], CompletableFuture<Void>> sendCallback,
            LogContext logContext,
            Time time) {
        super("producer-heartbeat-thread-" + clientId);
        setDaemon(true);

        this.log = logContext.logger(ProducerHeartbeatThread.class);
        this.time = time;
        this.producerId = UUID.randomUUID().toString();
        this.clientId = clientId;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.maxRetries = maxRetries;
        this.retryBackoffMs = retryBackoffMs;
        this.sendCallback = sendCallback;
        this.version = getProducerVersion();
        this.host = getHostName();
        this.metadata = new HashMap<>();
        this.metadata.put("startTime", String.valueOf(time.milliseconds()));

        log.info("Initialized ProducerHeartbeatThread with producerId={}, heartbeatInterval={}ms",
                 producerId, heartbeatIntervalMs);
    }

    @Override
    public void run() {
        try {
            log.info("ProducerHeartbeatThread started");

            // Send initial heartbeat
            sendHeartbeat(ProducerHeartbeat.State.ACTIVE);

            while (running.get()) {
                try {
                    // Sleep for the heartbeat interval
                    Thread.sleep(heartbeatIntervalMs);

                    // Send heartbeat if still running
                    if (running.get()) {
                        sendHeartbeat(currentState.get());
                    }
                } catch (InterruptedException e) {
                    if (!running.get()) {
                        log.debug("ProducerHeartbeatThread interrupted during shutdown");
                        break;
                    }
                    throw new InterruptException("Interrupted while sending heartbeat", e);
                }
            }

            // Send final STOPPING heartbeat
            log.info("Sending final STOPPING heartbeat");
            sendHeartbeat(ProducerHeartbeat.State.STOPPING);

        } catch (Exception e) {
            log.error("ProducerHeartbeatThread encountered fatal error", e);
            currentState.set(ProducerHeartbeat.State.ERROR);
            try {
                sendHeartbeat(ProducerHeartbeat.State.ERROR);
            } catch (Exception ignored) {
                // Ignore errors when sending error heartbeat
            }
        } finally {
            log.info("ProducerHeartbeatThread stopped. Sent {} heartbeats, {} failed",
                     heartbeatsSent.get(), heartbeatsFailed.get());
            shutdownLatch.countDown();
        }
    }

    /**
     * Send a heartbeat with the specified state.
     */
    private void sendHeartbeat(ProducerHeartbeat.State state) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetries) {
            try {
                ProducerHeartbeat heartbeat = new ProducerHeartbeat.Builder()
                        .producerId(producerId)
                        .clientId(clientId)
                        .timestamp(time.milliseconds())
                        .version(version)
                        .host(host)
                        .metadata(metadata)
                        .state(state)
                        .build();

                byte[] data = ProducerHeartbeatSerde.serialize(heartbeat);

                // Send using the callback (non-blocking)
                CompletableFuture<Void> future = sendCallback.apply(HEARTBEAT_TOPIC, data);

                // Wait for send to complete
                future.get(sessionTimeoutMs, TimeUnit.MILLISECONDS);

                // Success
                lastHeartbeatTime.set(time.milliseconds());
                heartbeatsSent.incrementAndGet();
                log.debug("Heartbeat sent successfully: {}", heartbeat);
                return;

            } catch (Exception e) {
                lastException = e;
                attempt++;
                log.warn("Failed to send heartbeat (attempt {}/{}): {}",
                         attempt, maxRetries, e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryBackoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new InterruptException("Interrupted during retry backoff", ie);
                    }
                }
            }
        }

        // All retries failed
        heartbeatsFailed.incrementAndGet();
        log.error("Failed to send heartbeat after {} attempts", maxRetries, lastException);

        // Don't throw exception - just log and continue
        // This allows for high fault tolerance as specified in requirements
    }

    /**
     * Initiate graceful shutdown of the heartbeat thread.
     * Sends a STOPPING heartbeat and waits for the thread to exit.
     */
    public void shutdown(long timeoutMs) {
        log.info("Shutting down ProducerHeartbeatThread");
        running.set(false);
        currentState.set(ProducerHeartbeat.State.STOPPING);
        this.interrupt();

        try {
            if (!shutdownLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("ProducerHeartbeatThread did not shut down within {}ms", timeoutMs);
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for ProducerHeartbeatThread shutdown");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Manually send a heartbeat (usually not needed).
     */
    public void sendHeartbeatNow() {
        sendHeartbeat(currentState.get());
    }

    /**
     * Update the producer state.
     */
    public void setState(ProducerHeartbeat.State state) {
        this.currentState.set(state);
    }

    /**
     * Get the producer ID.
     */
    public String producerId() {
        return producerId;
    }

    /**
     * Get the last heartbeat timestamp.
     */
    public long lastHeartbeatTime() {
        return lastHeartbeatTime.get();
    }

    /**
     * Get the number of heartbeats sent.
     */
    public long heartbeatsSent() {
        return heartbeatsSent.get();
    }

    /**
     * Get the number of heartbeat failures.
     */
    public long heartbeatsFailed() {
        return heartbeatsFailed.get();
    }

    /**
     * Get the current state.
     */
    public ProducerHeartbeat.State currentState() {
        return currentState.get();
    }

    private static String getProducerVersion() {
        // Get Kafka version from package info
        Package pkg = ProducerHeartbeatThread.class.getPackage();
        return pkg != null && pkg.getImplementationVersion() != null
                ? pkg.getImplementationVersion()
                : "unknown";
    }

    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
