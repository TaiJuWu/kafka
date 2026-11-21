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
package org.apache.kafka.clients.admin;

import org.apache.kafka.common.TopicPartition;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The description of a cluster mirror by {@link Admin#describeClusterMirrors} (KIP-1279).
 */
public class ClusterMirrorDescription {
    private final String mirrorName;
    private final String sourceBootstrap;
    private final String sourceClusterId;
    private final Map<String, Set<LeaderStateDescription>> leaderStates;

    public ClusterMirrorDescription(String mirrorName, String sourceBootstrap, String sourceClusterId,
                                    Map<String, Set<LeaderStateDescription>> leaderStates) {
        this.mirrorName = mirrorName;
        this.sourceBootstrap = sourceBootstrap;
        this.sourceClusterId = sourceClusterId;
        this.leaderStates = leaderStates;
    }

    public String mirrorName() {
        return mirrorName;
    }

    public String sourceBootstrap() {
        return sourceBootstrap;
    }

    public String sourceClusterId() {
        return sourceClusterId;
    }

    /**
     * @return the state of each partition, by topic
     */
    public Map<String, Set<LeaderStateDescription>> leaderStates() {
        return leaderStates;
    }

    /**
     * The state of a mirror partition: its offsets in the source and destination clusters, its mirror state, and its
     * last mirror epoch and offset.
     */
    public static class LeaderStateDescription {
        private final TopicPartition topicPartition;
        private final long sourceOffset;
        private final long destinationOffset;
        private final String state;
        private final int retryAttempt;
        private final String errorMessage;
        private final int lastMirrorEpoch;
        private final long lastMirrorOffset;

        public LeaderStateDescription(TopicPartition topicPartition, long sourceOffset, long destinationOffset, String state,
                                      int retryAttempt, String errorMessage, int lastMirrorEpoch, long lastMirrorOffset) {
            this.topicPartition = topicPartition;
            this.sourceOffset = sourceOffset;
            this.destinationOffset = destinationOffset;
            this.state = state;
            this.retryAttempt = retryAttempt;
            this.errorMessage = errorMessage;
            this.lastMirrorEpoch = lastMirrorEpoch;
            this.lastMirrorOffset = lastMirrorOffset;
        }

        public TopicPartition topicPartition() {
            return topicPartition;
        }

        public long sourceOffset() {
            return sourceOffset;
        }

        public long destinationOffset() {
            return destinationOffset;
        }

        /**
         * @return the number of records not mirrored yet, or -1 if the offsets are not known
         */
        public long lag() {
            return sourceOffset < 0 || destinationOffset < 0 ? -1 : Math.max(0, sourceOffset - destinationOffset);
        }

        public String state() {
            return state;
        }

        public int retryAttempt() {
            return retryAttempt;
        }

        public String errorMessage() {
            return errorMessage;
        }

        public int lastMirrorEpoch() {
            return lastMirrorEpoch;
        }

        public long lastMirrorOffset() {
            return lastMirrorOffset;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof LeaderStateDescription)) return false;
            LeaderStateDescription that = (LeaderStateDescription) o;
            return sourceOffset == that.sourceOffset && destinationOffset == that.destinationOffset &&
                retryAttempt == that.retryAttempt && lastMirrorEpoch == that.lastMirrorEpoch &&
                lastMirrorOffset == that.lastMirrorOffset && Objects.equals(topicPartition, that.topicPartition) &&
                Objects.equals(state, that.state) && Objects.equals(errorMessage, that.errorMessage);
        }

        @Override
        public int hashCode() {
            return Objects.hash(topicPartition, sourceOffset, destinationOffset, state, retryAttempt, errorMessage,
                lastMirrorEpoch, lastMirrorOffset);
        }

        @Override
        public String toString() {
            return "LeaderStateDescription(topicPartition=" + topicPartition + ", state=" + state +
                ", sourceOffset=" + sourceOffset + ", destinationOffset=" + destinationOffset +
                ", retryAttempt=" + retryAttempt + ", lastMirrorEpoch=" + lastMirrorEpoch +
                ", lastMirrorOffset=" + lastMirrorOffset + ")";
        }
    }

    @Override
    public String toString() {
        return "ClusterMirrorDescription(mirrorName=" + mirrorName + ", sourceBootstrap=" + sourceBootstrap +
            ", sourceClusterId=" + sourceClusterId + ", leaderStates=" + leaderStates + ")";
    }
}
