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
package org.apache.kafka.server.common;

import org.apache.kafka.common.errors.InvalidConfigurationException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ConfigFeatureGateTest {

    @Test
    public void testTopicSynonymResolvesToBrokerValidator() {
        // "segment.bytes" (topic config name) should resolve to "log.segment.bytes" validator
        assertThrows(InvalidConfigurationException.class, () ->
            ConfigFeatureGate.validate(MetadataVersion.IBP_4_4_IV1,
                "segment.bytes", String.valueOf(2 * 1024 * 1024)));
    }

    @Test
    public void testBrokerConfigDirectHit() {
        // "log.segment.bytes" should directly match
        assertThrows(InvalidConfigurationException.class, () ->
            ConfigFeatureGate.validate(MetadataVersion.IBP_4_4_IV1,
                "log.segment.bytes", String.valueOf(2 * 1024 * 1024)));
    }

    @Test
    public void testValidationPassesWhenMvBelowThreshold() {
        // MV below IBP_4_4_IV1 → no constraint, should pass
        assertDoesNotThrow(() ->
            ConfigFeatureGate.validate(MetadataVersion.IBP_4_0_IV0,
                "log.segment.bytes", String.valueOf(2 * 1024 * 1024)));
    }

    @Test
    public void testValidationPassesForValidValue() {
        // 4MB >= 3MB threshold → should pass
        assertDoesNotThrow(() ->
            ConfigFeatureGate.validate(MetadataVersion.IBP_4_4_IV1,
                "log.segment.bytes", String.valueOf(4 * 1024 * 1024)));
    }

    @Test
    public void testUnregisteredConfigDoesNotThrow() {
        // Unknown config name → no validator, should not throw
        assertDoesNotThrow(() ->
            ConfigFeatureGate.validate(MetadataVersion.IBP_4_4_IV1,
                "unknown.config", "anyvalue"));
    }
}
