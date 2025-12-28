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

package org.apache.kafka.server.config;

import java.util.Locale;

/**
 * Policy to apply when dynamic configuration validation fails.
 */
public enum DynamicConfigFailurePolicy {
    /**
     * Log a warning and ignore invalid configurations.
     * This is lenient behavior that allows the broker to continue running even with invalid dynamic configs.
     */
    WARN("warn"),

    /**
     * Halt the broker when invalid configurations are detected during startup (first metadata publish).
     * After startup, reject configuration updates that fail validation.
     * This is strict behavior that ensures configuration correctness.
     */
    FAIL("fail");

    private final String name;

    DynamicConfigFailurePolicy(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Parse the policy from a string value.
     *
     * @param value the string value (case-insensitive)
     * @return the corresponding DynamicConfigFailurePolicy
     * @throws IllegalArgumentException if the value is not a valid policy
     */
    public static DynamicConfigFailurePolicy fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Dynamic config failure policy cannot be null");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (DynamicConfigFailurePolicy policy : values()) {
            if (policy.name.equals(normalized)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("Invalid dynamic config failure policy: " + value +
                ". Valid values are: warn, fail");
    }
}
