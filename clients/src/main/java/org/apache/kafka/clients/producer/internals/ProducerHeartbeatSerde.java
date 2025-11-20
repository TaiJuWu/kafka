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

import org.apache.kafka.common.protocol.ByteBufferAccessor;
import org.apache.kafka.common.protocol.ObjectSerializationCache;
import org.apache.kafka.common.protocol.Readable;
import org.apache.kafka.common.protocol.Writable;
import org.apache.kafka.common.utils.ByteUtils;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Serializer and deserializer for ProducerHeartbeat messages.
 * Uses a compact binary format for efficiency.
 */
public class ProducerHeartbeatSerde {

    private static final short CURRENT_VERSION = 0;

    /**
     * Serialize a ProducerHeartbeat to bytes.
     */
    public static byte[] serialize(ProducerHeartbeat heartbeat) {
        ObjectSerializationCache cache = new ObjectSerializationCache();
        int size = size(heartbeat, CURRENT_VERSION, cache);
        ByteBuffer buffer = ByteBuffer.allocate(size);
        ByteBufferAccessor accessor = new ByteBufferAccessor(buffer);
        write(accessor, heartbeat, CURRENT_VERSION, cache);
        return buffer.array();
    }

    /**
     * Deserialize a ProducerHeartbeat from bytes.
     */
    public static ProducerHeartbeat deserialize(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        ByteBufferAccessor accessor = new ByteBufferAccessor(buffer);
        return read(accessor, CURRENT_VERSION);
    }

    private static int size(ProducerHeartbeat heartbeat, short version, ObjectSerializationCache cache) {
        int size = 0;
        size += 2; // version
        size += ByteUtils.sizeOfUnsignedVarint(cache.getSerializedValue(heartbeat.producerId()).length + 1);
        size += cache.getSerializedValue(heartbeat.producerId()).length;
        size += ByteUtils.sizeOfUnsignedVarint(cache.getSerializedValue(heartbeat.clientId()).length + 1);
        size += cache.getSerializedValue(heartbeat.clientId()).length;
        size += 8; // timestamp (long)
        size += ByteUtils.sizeOfUnsignedVarint(cache.getSerializedValue(heartbeat.version()).length + 1);
        size += cache.getSerializedValue(heartbeat.version()).length;
        size += ByteUtils.sizeOfUnsignedVarint(cache.getSerializedValue(heartbeat.host()).length + 1);
        size += cache.getSerializedValue(heartbeat.host()).length;
        size += 1; // state (byte)

        // metadata map
        size += ByteUtils.sizeOfUnsignedVarint(heartbeat.metadata().size() + 1);
        for (Map.Entry<String, String> entry : heartbeat.metadata().entrySet()) {
            size += ByteUtils.sizeOfUnsignedVarint(cache.getSerializedValue(entry.getKey()).length + 1);
            size += cache.getSerializedValue(entry.getKey()).length;
            size += ByteUtils.sizeOfUnsignedVarint(cache.getSerializedValue(entry.getValue()).length + 1);
            size += cache.getSerializedValue(entry.getValue()).length;
        }

        return size;
    }

    private static void write(Writable writable, ProducerHeartbeat heartbeat, short version, ObjectSerializationCache cache) {
        writable.writeShort(version);
        writeString(writable, heartbeat.producerId(), cache);
        writeString(writable, heartbeat.clientId(), cache);
        writable.writeLong(heartbeat.timestamp());
        writeString(writable, heartbeat.version(), cache);
        writeString(writable, heartbeat.host(), cache);
        writable.writeByte((byte) heartbeat.state().value());

        // Write metadata
        writable.writeUnsignedVarint(heartbeat.metadata().size() + 1);
        for (Map.Entry<String, String> entry : heartbeat.metadata().entrySet()) {
            writeString(writable, entry.getKey(), cache);
            writeString(writable, entry.getValue(), cache);
        }
    }

    private static ProducerHeartbeat read(Readable readable, short version) {
        short readVersion = readable.readShort();
        if (readVersion != version) {
            throw new IllegalArgumentException("Unsupported version: " + readVersion);
        }

        String producerId = readString(readable);
        String clientId = readString(readable);
        long timestamp = readable.readLong();
        String producerVersion = readString(readable);
        String host = readString(readable);
        ProducerHeartbeat.State state = ProducerHeartbeat.State.fromValue(readable.readByte());

        // Read metadata
        int metadataSize = readable.readUnsignedVarint() - 1;
        Map<String, String> metadata = new HashMap<>(metadataSize);
        for (int i = 0; i < metadataSize; i++) {
            String key = readString(readable);
            String value = readString(readable);
            metadata.put(key, value);
        }

        return new ProducerHeartbeat.Builder()
                .producerId(producerId)
                .clientId(clientId)
                .timestamp(timestamp)
                .version(producerVersion)
                .host(host)
                .metadata(metadata)
                .state(state)
                .build();
    }

    private static void writeString(Writable writable, String value, ObjectSerializationCache cache) {
        byte[] bytes = cache.getSerializedValue(value);
        writable.writeUnsignedVarint(bytes.length + 1);
        writable.writeByteArray(bytes);
    }

    private static String readString(Readable readable) {
        int length = readable.readUnsignedVarint() - 1;
        return readable.readString(length);
    }
}
