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
package org.apache.kafka.image;

import org.apache.kafka.common.metadata.MirrorTopicStateChangeRecord;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ClusterMirrorsDelta {
    private final ClusterMirrorsImage image;
    private final Map<String, Optional<ClusterMirrorsImage.MirrorTopic>> changes = new HashMap<>();

    public ClusterMirrorsDelta(ClusterMirrorsImage image) {
        this.image = image;
    }

    public ClusterMirrorsImage image() {
        return image;
    }

    /**
     * @return the topics whose cluster mirror or desired state changed, with empty for the removed ones
     */
    public Map<String, Optional<ClusterMirrorsImage.MirrorTopic>> changes() {
        return changes;
    }

    public void finishSnapshot() {
        for (String topic : image.topics().keySet()) {
            changes.putIfAbsent(topic, Optional.empty());
        }
    }

    public void replay(MirrorTopicStateChangeRecord record) {
        if (record.mirrorName() == null) {
            changes.put(record.topicName(), Optional.empty());
        } else {
            changes.put(record.topicName(), Optional.of(
                new ClusterMirrorsImage.MirrorTopic(record.mirrorName(), record.desiredState())));
        }
    }

    /**
     * Removes the mirror state of a deleted topic.
     */
    public void removeTopic(String topicName) {
        if (image.topics().containsKey(topicName) || changes.containsKey(topicName)) {
            changes.put(topicName, Optional.empty());
        }
    }

    public ClusterMirrorsImage apply() {
        Map<String, ClusterMirrorsImage.MirrorTopic> topics = new HashMap<>(image.topics());
        changes.forEach((topic, change) -> {
            if (change.isPresent()) {
                topics.put(topic, change.get());
            } else {
                topics.remove(topic);
            }
        });
        return new ClusterMirrorsImage(topics);
    }

    @Override
    public String toString() {
        return "ClusterMirrorsDelta(changes=" + changes + ")";
    }
}
