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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PartitionLeaderCacheTest {

    @Test
    public void testTopicIdRecordedFromPartitionId() {
        PartitionLeaderCache cache = new PartitionLeaderCache();
        TopicPartition tp = new TopicPartition("foo", 0);

        cache.putByTopicName(tp, 1);
        assertNull(cache.getTopicIdByName("foo"));
        assertFalse(cache.containTopicId(Uuid.ZERO_UUID));

        Uuid topicId = Uuid.randomUuid();
        TopicIdPartition tip = new TopicIdPartition(topicId, tp);
        cache.putByTopicId(tip, 2);

        assertEquals(topicId, cache.getTopicIdByName("foo"));
        assertEquals("foo", cache.getTopicNameById(topicId));
        assertTrue(cache.containTopicId(topicId));
    }

    @Test
    public void testRemoveByNameDoesNotClearTopicMapping() {
        PartitionLeaderCache cache = new PartitionLeaderCache();
        TopicPartition tp = new TopicPartition("foo", 0);
        Uuid topicId = Uuid.randomUuid();
        TopicIdPartition tip = new TopicIdPartition(topicId, tp);

        cache.putByTopicId(tip, 3);
        cache.removeByName(tp);

        assertEquals(topicId, cache.getTopicIdByName("foo"));
        assertEquals("foo", cache.getTopicNameById(topicId));
        assertTrue(cache.containTopicId(topicId));
        assertEquals(3, cache.getLeaderById(tip));
    }

    @Test
    public void testRemoveByIdKeepsTopicMapping() {
        PartitionLeaderCache cache = new PartitionLeaderCache();
        TopicPartition tp = new TopicPartition("foo", 0);
        Uuid topicId = Uuid.randomUuid();
        TopicIdPartition tip = new TopicIdPartition(topicId, tp);

        cache.putByTopicId(tip, 4);
        cache.removeById(tip);

        assertEquals(topicId, cache.getTopicIdByName("foo"));
        assertEquals("foo", cache.getTopicNameById(topicId));
        assertTrue(cache.containTopicId(topicId));
    }

    @Test
    public void testConflictingTopicIdMappingThrows() {
        PartitionLeaderCache cache = new PartitionLeaderCache();
        TopicPartition foo = new TopicPartition("foo", 0);
        Uuid topicId = Uuid.randomUuid();
        TopicIdPartition first = new TopicIdPartition(topicId, foo);
        cache.putByTopicId(first, 5);

        TopicPartition bar = new TopicPartition("bar", 0);
        TopicIdPartition conflict = new TopicIdPartition(topicId, bar);
        assertThrows(IllegalStateException.class, () -> cache.putByTopicId(conflict, 6));
    }
}
