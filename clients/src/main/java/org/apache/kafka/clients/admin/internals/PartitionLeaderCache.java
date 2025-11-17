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

import java.util.HashMap;
import java.util.Map;

public class PartitionLeaderCache {
    private final Map<TopicPartition, Integer> partitionLeaderCache;
    private final Map<TopicIdPartition, Integer> partitionIdLeaderCache;
    private final Map<String, Uuid> topicByName;
    private final Map<Uuid, String> topicById;

    public PartitionLeaderCache() {
        this.partitionLeaderCache = new HashMap<>();
        this.partitionIdLeaderCache = new HashMap<>();
        this.topicByName = new HashMap<>();
        this.topicById = new HashMap<>();
    }

    public boolean containTopicName(TopicPartition tp) {
        return partitionLeaderCache.containsKey(tp);
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

    public int getLeaderByTopicName(TopicPartition topicPartition) {
        return partitionLeaderCache.get(topicPartition);
    }

    public int getLeaderById(TopicIdPartition tip) {
        return partitionIdLeaderCache.get(tip);
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
        if (partitionLeaderCache.containsKey(tp)) {
            throw new IllegalStateException("Leader mapping already exists for " + tp);
        }

        if (!topicByName.containsKey(tp.topic())) {
            updateTopicIdMapping(tp.topic(), Uuid.ZERO_UUID);
        }
        partitionLeaderCache.put(tp, brokerMapping);
    }

    public void putByTopicId(TopicIdPartition tip, int brokerMapping) {
        if (tip.topicId() == null || tip.topicId().equals(Uuid.ZERO_UUID)) {
            throw new IllegalStateException("Uuid can't be null or Uuid.ZERO");
        }
        if (partitionIdLeaderCache.containsKey(tip)) {
            throw new IllegalStateException("Leader mapping already exists for " + tip);
        }

        updateTopicIdMapping(tip.topic(), tip.topicId());
        partitionIdLeaderCache.put(tip, brokerMapping);
    }

    public int removeByName(TopicPartition tp) {
        return partitionLeaderCache.remove(tp);
    }

    public int removeById(TopicIdPartition tp) {
        return partitionIdLeaderCache.remove(tp);
    }

    private void updateTopicIdMapping(String topic, Uuid uuid) {
        Uuid existingUuid = topicByName.get(topic);
        if (existingUuid != null && !existingUuid.equals(uuid)) {
            if (existingUuid.equals(Uuid.ZERO_UUID) && uuid != null && !uuid.equals(Uuid.ZERO_UUID)) {
                // upgrade from unknown id to known id
                topicByName.put(topic, uuid);
            } else {
                throw new IllegalStateException("Topic " + topic + " is already mapped to " + existingUuid);
            }
        } else {
            topicByName.put(topic, uuid);
        }

        if (uuid != null && !uuid.equals(Uuid.ZERO_UUID)) {
            String existingTopic = topicById.get(uuid);
            if (existingTopic != null && !existingTopic.equals(topic)) {
                throw new IllegalStateException("Uuid " + uuid + " is already mapped to topic " + existingTopic);
            }
            topicById.put(uuid, topic);
        }
    }


    public Map<TopicPartition, Integer> getByName() {
        return partitionLeaderCache;
    }

    public Map<TopicIdPartition, Integer> getById() {
        return partitionIdLeaderCache;
    }
}
