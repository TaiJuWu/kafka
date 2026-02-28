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
import org.apache.kafka.server.config.ServerTopicConfigSynonyms;

import java.util.HashMap;
import java.util.Map;

public class ConfigFeatureGate {

    @FunctionalInterface
    interface ConfigValidator {
        void validate(String value, MetadataVersion mv);
    }

    private static final Map<String, ConfigValidator> VALIDATORS = new HashMap<>();

    static {
        VALIDATORS.put("log.segment.bytes", (value, mv) -> {
            int val = Integer.parseInt(value);
            if (mv.isAtLeast(MetadataVersion.IBP_4_3_IV1) && val < 3 * 1024 * 1024) {
                throw new InvalidConfigurationException("log.segment.bytes should be at least 3 MB for IBP_4_3_IV1");
            }
        });
    }

    private static String resolveToRegisteredKey(String key) {
        if (VALIDATORS.containsKey(key)) return key;
        String brokerSynonym = ServerTopicConfigSynonyms.TOPIC_CONFIG_SYNONYMS.get(key);
        if (brokerSynonym != null && VALIDATORS.containsKey(brokerSynonym)) return brokerSynonym;
        return key;
    }

    public static void validate(MetadataVersion mv, String key, String value) {
        ConfigValidator validator = VALIDATORS.get(resolveToRegisteredKey(key));
        if (validator != null) {
            validator.validate(value, mv);
        }
    }
}
