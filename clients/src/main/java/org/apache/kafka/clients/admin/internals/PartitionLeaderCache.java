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
package org.apache.kafka.clients.admin.internals;

import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.utils.LogContext;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

public class PartitionLeaderCache {
    private final Logger log;

    private final Map<TopicPartition, Integer> topicPartitionLeaderCache;
    private final Map<TopicIdPartition, Integer> topicIdPartitionLeaderCache;
    private final Map<String, Uuid> topicByName;
    private final Map<Uuid, String> topicById;


    public PartitionLeaderCache() {
        this(new LogContext());
    }

    public PartitionLeaderCache(LogContext logContext) {
        this.log = logContext.logger(PartitionLeaderCache.class);
        this.topicPartitionLeaderCache = new HashMap<>();
        this.topicIdPartitionLeaderCache = new HashMap<>();
        this.topicByName = new HashMap<>();
        this.topicById = new HashMap<>();
    }

    public boolean containTopicName(TopicPartition tp) {
        return topicPartitionLeaderCache.containsKey(tp);
    }

    public boolean containTopicId(Uuid topicId) {
        if (topicId == null || topicId.equals(Uuid.ZERO_UUID)) {
            return false;
        }
        return topicById.containsKey(topicId);
    }

    public Uuid getTopicIdByName(String topic) {
        return topicByName.get(topic);
    }

    public String getTopicNameById(Uuid uuid) {
        if (uuid == null || uuid.equals(Uuid.ZERO_UUID)) {
            throw new IllegalStateException("Uuid can't be null or Uuid.ZERO");
        }
        return topicById.get(uuid);
    }

    public Integer getLeaderByTopicName(TopicPartition topicPartition) {
        return topicPartitionLeaderCache.get(topicPartition);
    }

    public Integer getLeaderById(TopicIdPartition tip) {
        return topicIdPartitionLeaderCache.get(tip);
    }

    public void putAllByTopicName(Map<TopicPartition, Integer> brokerMapping) {
        for (Map.Entry<TopicPartition, Integer> entry : brokerMapping.entrySet()) {
            putByTopicName(entry.getKey(), entry.getValue());
        }
    }

    public void putAllByTopicId(Map<TopicIdPartition, Integer> brokerIdMapping) {
        for (Map.Entry<TopicIdPartition, Integer> entry : brokerIdMapping.entrySet()) {
            putByTopicId(entry.getKey(), entry.getValue());
        }
    }

    public void putByTopicName(TopicPartition tp, int brokerMapping) {
        Integer existingMapping = topicPartitionLeaderCache.get(tp);
        topicPartitionLeaderCache.put(tp, brokerMapping);
        if (existingMapping == null) {
            log.trace("Cached leader {} for {}", brokerMapping, tp);
        } else {
            log.trace("Refreshed leader {} for {} with the same broker id", brokerMapping, tp);
        }
    }

    public void putByTopicId(TopicIdPartition tip, int brokerMapping) {
        Integer existingMapping = topicIdPartitionLeaderCache.get(tip);
        updateTopicIdMapping(tip.topic(), tip.topicId());
        topicIdPartitionLeaderCache.put(tip, brokerMapping);
        if (existingMapping == null) {
            log.trace("Cached leader {} for {}", brokerMapping, tip);
        } else {
            log.trace("Refreshed leader {} for {} with the same broker id", brokerMapping, tip);
        }
    }

    public void removeByName(TopicPartition tp) {
        Integer removed = topicPartitionLeaderCache.remove(tp);
        if (removed != null) {
            log.trace("Removed cached leader {} for {}", removed, tp);
        }
    }

    public void removeById(TopicIdPartition tp) {
        Integer removed = topicIdPartitionLeaderCache.remove(tp);
        if (removed != null) {
            log.trace("Removed cached leader {} for {}", removed, tp);
        }
    }

    public void recordTopicId(String topic, Uuid topicId) {
        if (topic == null || topicId == null || topicId.equals(Uuid.ZERO_UUID)) {
            return;
        }
        log.trace("Recording topic id mapping {} -> {}", topic, topicId);
        updateTopicIdMapping(topic, topicId);
    }

    private void updateTopicIdMapping(String topic, Uuid uuid) {
        Uuid existingUuid = topicByName.get(topic);
        if (existingUuid != null && !existingUuid.equals(uuid)) {
            throw new IllegalStateException("Topic " + topic + " is already mapped to " + existingUuid);
        }
        topicByName.put(topic, uuid);

        if (uuid != null && !uuid.equals(Uuid.ZERO_UUID)) {
            String existingTopic = topicById.get(uuid);
            if (existingTopic != null && !existingTopic.equals(topic)) {
                throw new IllegalStateException("Uuid " + uuid + " is already mapped to topic " + existingTopic);
            }
            topicById.put(uuid, topic);
            log.trace("Updated topic id mapping {} -> {}", topic, uuid);
        }
    }


    public Map<TopicPartition, Integer> getByName() {
        return topicPartitionLeaderCache;
    }

    public Map<TopicIdPartition, Integer> getById() {
        return topicIdPartitionLeaderCache;
    }
}
