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
import org.apache.kafka.image.writer.ImageWriter;
import org.apache.kafka.image.writer.ImageWriterOptions;

import java.util.Collections;
import java.util.Map;

/**
 * The cluster mirror state of the topics (KIP-1279): the cluster mirror that each mirror topic belongs to, and the
 * desired state of its partitions. The cluster mirrors themselves are CLUSTER_MIRROR config resources.
 */
public record ClusterMirrorsImage(Map<String, ClusterMirrorsImage.MirrorTopic> topics) {
    public static final ClusterMirrorsImage EMPTY = new ClusterMirrorsImage(Map.of());

    /**
     * The cluster mirror of a topic and the desired state of its partitions.
     */
    public record MirrorTopic(String mirrorName, byte desiredState) { }

    public ClusterMirrorsImage(Map<String, MirrorTopic> topics) {
        this.topics = Collections.unmodifiableMap(topics);
    }

    public boolean isEmpty() {
        return topics.isEmpty();
    }

    public void write(ImageWriter writer, ImageWriterOptions options) {
        if (topics.isEmpty()) {
            return;
        }
        if (!options.metadataVersion().isClusterMirroringSupported()) {
            options.handleLoss("ClusterMirrorsImage(" + String.join(", ", topics.keySet()) + ")");
            return;
        }
        topics.forEach((topicName, topic) -> writer.write(0, new MirrorTopicStateChangeRecord()
            .setTopicName(topicName)
            .setMirrorName(topic.mirrorName())
            .setDesiredState(topic.desiredState())));
    }
}
