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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a heartbeat from a Kafka producer.
 * Contains information about the producer's identity, state, and metadata.
 */
public class ProducerHeartbeat {

    public enum State {
        ACTIVE(0),
        STOPPING(1),
        ERROR(2);

        private final int value;

        State(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        public static State fromValue(int value) {
            for (State state : values()) {
                if (state.value == value) {
                    return state;
                }
            }
            throw new IllegalArgumentException("Unknown state value: " + value);
        }
    }

    private final String producerId;
    private final String clientId;
    private final long timestamp;
    private final String version;
    private final String host;
    private final Map<String, String> metadata;
    private final State state;

    public ProducerHeartbeat(
            String producerId,
            String clientId,
            long timestamp,
            String version,
            String host,
            Map<String, String> metadata,
            State state) {
        this.producerId = Objects.requireNonNull(producerId, "producerId cannot be null");
        this.clientId = Objects.requireNonNull(clientId, "clientId cannot be null");
        this.timestamp = timestamp;
        this.version = version;
        this.host = host;
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        this.state = state != null ? state : State.ACTIVE;
    }

    public String producerId() {
        return producerId;
    }

    public String clientId() {
        return clientId;
    }

    public long timestamp() {
        return timestamp;
    }

    public String version() {
        return version;
    }

    public String host() {
        return host;
    }

    public Map<String, String> metadata() {
        return new HashMap<>(metadata);
    }

    public State state() {
        return state;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProducerHeartbeat that = (ProducerHeartbeat) o;
        return timestamp == that.timestamp &&
                Objects.equals(producerId, that.producerId) &&
                Objects.equals(clientId, that.clientId) &&
                Objects.equals(version, that.version) &&
                Objects.equals(host, that.host) &&
                Objects.equals(metadata, that.metadata) &&
                state == that.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(producerId, clientId, timestamp, version, host, metadata, state);
    }

    @Override
    public String toString() {
        return "ProducerHeartbeat{" +
                "producerId='" + producerId + '\'' +
                ", clientId='" + clientId + '\'' +
                ", timestamp=" + timestamp +
                ", version='" + version + '\'' +
                ", host='" + host + '\'' +
                ", metadata=" + metadata +
                ", state=" + state +
                '}';
    }

    /**
     * Builder for creating ProducerHeartbeat instances.
     */
    public static class Builder {
        private String producerId;
        private String clientId;
        private long timestamp;
        private String version;
        private String host;
        private Map<String, String> metadata = new HashMap<>();
        private State state = State.ACTIVE;

        public Builder producerId(String producerId) {
            this.producerId = producerId;
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = new HashMap<>(metadata);
            return this;
        }

        public Builder addMetadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder state(State state) {
            this.state = state;
            return this;
        }

        public ProducerHeartbeat build() {
            return new ProducerHeartbeat(
                    producerId,
                    clientId,
                    timestamp,
                    version,
                    host,
                    metadata,
                    state
            );
        }
    }
}
