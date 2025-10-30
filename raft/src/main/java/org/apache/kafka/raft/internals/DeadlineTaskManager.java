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

import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Timer;
import org.apache.kafka.queue.KafkaDeadlineEventQueue;

import java.util.Optional;

/**
 * Manage deferred tasks that should execute once before their deadline,
 * or trigger a timeout callback if missed.
 */
public class DeadlineTaskManager {
    private final KafkaDeadlineEventQueue<DeadlineTask> eventQueue;
    private final Time time;

    public DeadlineTaskManager(Time time) {
        this(time, new KafkaDeadlineEventQueue<>());
    }

    public DeadlineTaskManager(Time time, KafkaDeadlineEventQueue<DeadlineTask> eventQueue) {
        this.time = time;
        this.eventQueue = eventQueue;
    }

    /**
     * Add a new one-shot task.
     */
    public void addTask(String tag, DeadlineTask task, Timer timer) {
        eventQueue.enqueue(new KafkaDeadlineEventQueue.Event<>(timer, tag, task));
    }

    /**
     * Poll once:
     * - If deadline not reached → run action (normal path)
     * - If deadline reached or passed → run onTimeout (timeout path)
     *
     * After execution, task is automatically removed.
     */
    public void poll(long now) {
        Optional<DeadlineTask> taskOpt = eventQueue.dequeue(now, null);
        taskOpt.ifPresent(task -> {
            if (task.deadlineMs <= now) {
                task.onTimeout.run();
            } else {
                task.action.run();
            }
        });
    }

    /**
     * Explicitly trigger onTimeout for all expired tasks.
     */
    public void checkTimeout(long now) {
        eventQueue.checkTimeout(now, task -> task.onTimeout.run());
    }

    public int size() {
        return eventQueue.size();
    }

    public record DeadlineTask(long deadlineMs, Runnable action, Runnable onTimeout) { }
}
