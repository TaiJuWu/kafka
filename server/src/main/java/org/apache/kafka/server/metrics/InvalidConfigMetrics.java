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

package org.apache.kafka.server.metrics;

import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.Gauge;
import org.apache.kafka.common.metrics.Metrics;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks invalid dynamic broker configuration counts and names.
 * This class provides metrics for monitoring invalid configurations that are
 * rejected during dynamic broker configuration updates.
 */
public final class InvalidConfigMetrics implements AutoCloseable {
    private static final String METRIC_GROUP_NAME = "dynamic-broker-config";
    private static final String INVALID_BROKER_CONFIG_COUNT = "invalid-broker-config-count";
    private static final String INVALID_DEFAULT_CONFIG_COUNT = "invalid-default-config-count";
    private static final String TOTAL_INVALID_CONFIG_COUNT = "total-invalid-config-count";
    private static final String INVALID_BROKER_CONFIG_NAMES = "invalid-broker-config-names";
    private static final String INVALID_DEFAULT_CONFIG_NAMES = "invalid-default-config-names";

    private final Metrics metrics;
    private final AtomicInteger invalidBrokerConfigCount = new AtomicInteger(0);
    private final AtomicInteger invalidDefaultConfigCount = new AtomicInteger(0);
    private final AtomicReference<String> invalidBrokerConfigNames = new AtomicReference<>("");
    private final AtomicReference<String> invalidDefaultConfigNames = new AtomicReference<>("");

    public InvalidConfigMetrics(Metrics metrics) {
        this.metrics = metrics;
        registerMetrics();
    }

    private void registerMetrics() {
        // Register invalid broker config count metric
        metrics.addMetric(
            metricName(INVALID_BROKER_CONFIG_COUNT),
            (Gauge<Integer>) (config, now) -> invalidBrokerConfigCount.get()
        );

        // Register invalid default config count metric
        metrics.addMetric(
            metricName(INVALID_DEFAULT_CONFIG_COUNT),
            (Gauge<Integer>) (config, now) -> invalidDefaultConfigCount.get()
        );

        // Register total invalid config count metric
        metrics.addMetric(
            metricName(TOTAL_INVALID_CONFIG_COUNT),
            (Gauge<Integer>) (config, now) -> invalidBrokerConfigCount.get() + invalidDefaultConfigCount.get()
        );

        // Register invalid broker config names metric
        metrics.addMetric(
            metricName(INVALID_BROKER_CONFIG_NAMES),
            (Gauge<String>) (config, now) -> invalidBrokerConfigNames.get()
        );

        // Register invalid default config names metric
        metrics.addMetric(
            metricName(INVALID_DEFAULT_CONFIG_NAMES),
            (Gauge<String>) (config, now) -> invalidDefaultConfigNames.get()
        );
    }

    private MetricName metricName(String name) {
        return metrics.metricName(name, METRIC_GROUP_NAME, Collections.emptyMap());
    }

    /**
     * Update the invalid broker config count and names.
     *
     * @param count the new count of invalid broker configurations
     * @param configNames comma-separated list of invalid config names
     */
    public void setInvalidBrokerConfigCount(int count, String configNames) {
        invalidBrokerConfigCount.set(count);
        invalidBrokerConfigNames.set(configNames != null ? configNames : "");
    }

    /**
     * Update the invalid broker config count (backward compatibility).
     *
     * @param count the new count of invalid broker configurations
     */
    public void setInvalidBrokerConfigCount(int count) {
        setInvalidBrokerConfigCount(count, "");
    }

    /**
     * Update the invalid default config count and names.
     *
     * @param count the new count of invalid default configurations
     * @param configNames comma-separated list of invalid config names
     */
    public void setInvalidDefaultConfigCount(int count, String configNames) {
        invalidDefaultConfigCount.set(count);
        invalidDefaultConfigNames.set(configNames != null ? configNames : "");
    }

    /**
     * Update the invalid default config count (backward compatibility).
     *
     * @param count the new count of invalid default configurations
     */
    public void setInvalidDefaultConfigCount(int count) {
        setInvalidDefaultConfigCount(count, "");
    }

    /**
     * Get the current invalid broker config count.
     *
     * @return the count of invalid broker configurations
     */
    public int getInvalidBrokerConfigCount() {
        return invalidBrokerConfigCount.get();
    }

    /**
     * Get the current invalid default config count.
     *
     * @return the count of invalid default configurations
     */
    public int getInvalidDefaultConfigCount() {
        return invalidDefaultConfigCount.get();
    }

    /**
     * Get the total invalid config count.
     *
     * @return the total count of all invalid configurations
     */
    public int getTotalInvalidConfigCount() {
        return invalidBrokerConfigCount.get() + invalidDefaultConfigCount.get();
    }

    @Override
    public void close() {
        metrics.removeMetric(metricName(INVALID_BROKER_CONFIG_COUNT));
        metrics.removeMetric(metricName(INVALID_DEFAULT_CONFIG_COUNT));
        metrics.removeMetric(metricName(TOTAL_INVALID_CONFIG_COUNT));
        metrics.removeMetric(metricName(INVALID_BROKER_CONFIG_NAMES));
        metrics.removeMetric(metricName(INVALID_DEFAULT_CONFIG_NAMES));
    }
}
