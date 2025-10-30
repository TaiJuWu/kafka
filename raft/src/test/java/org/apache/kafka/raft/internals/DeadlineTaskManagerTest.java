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

package org.apache.kafka.raft.internals;

import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.queue.KafkaDeadlineEventQueue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeadlineTaskManagerTest {

    Time mockTime = new MockTime();
    KafkaDeadlineEventQueue<DeadlineTaskManager.DeadlineTask> eventQueue = new KafkaDeadlineEventQueue<>();
    DeadlineTaskManager deadlineTaskManager = new DeadlineTaskManager(mockTime, eventQueue);

    @Test
    public void DeadlineTaskTimeout() {
        AtomicBoolean timeout = new AtomicBoolean(false);

        deadlineTaskManager.addTask("test Deadline", new DeadlineTaskManager.DeadlineTask(
                1000, () -> { }, () -> timeout.set(true)), mockTime.timer(1000));
        mockTime.sleep(1001);
        deadlineTaskManager.checkTimeout(mockTime.milliseconds());
        deadlineTaskManager.poll(mockTime.milliseconds());
        assertTrue(timeout.get());
    }

    @Test
    public void DeadlineTaskNotTimeout() {
        AtomicBoolean timeout = new AtomicBoolean(false);

        deadlineTaskManager.addTask("test Deadline", new DeadlineTaskManager.DeadlineTask(
                1000, () -> timeout.set(true), () -> { }
        ), mockTime.timer(1000));
        deadlineTaskManager.poll(mockTime.milliseconds());
        assertTrue(timeout.get());
    }
}
