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
package org.apache.kafka.queue;

import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Timer;
import org.apache.kafka.server.util.KafkaScheduler;

import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Consumer;

public class KafkaDeadlineEventQueue<T> implements AutoCloseable {
    private final Queue<Event<T>> eventQueue;
    private final Time time;
    private final Consumer<T> timeOutOperation;
    private final KafkaScheduler sched;

    public KafkaDeadlineEventQueue(Time time) {
        this(time, null);
    }

    public KafkaDeadlineEventQueue(Time time, Consumer<T> timeOutOperation) {
        this.eventQueue = new PriorityBlockingQueue<>();
        this.time = time;
        this.timeOutOperation = timeOutOperation;
        this.sched = new KafkaScheduler(1, true, "kafka-deadline-event-cleaner-");
        this.sched.startup();
        this.sched.schedule("clean timeout task", () -> {
            while (!eventQueue.isEmpty()) {
                if (eventQueue.peek().timer.remainingMs() >= 0) {
                    Event<T> event = eventQueue.poll();
                    if (timeOutOperation != null) {
                        timeOutOperation.accept(event.get());
                    }
                } else {
                    break;
                }
            }
        }, 100, 1000);
    }

    public Queue<Event<T>> eventQueue() {
        return eventQueue;
    }

    public void enqueue(Event<T> element) {
        eventQueue.add(Objects.requireNonNull(element));
    }

    public Optional<Event<T>> dequeue() {
        Event<T> event;
        while (true) {
            event = eventQueue.poll();
            System.err.println("pop event: " + event);
            if (event == null) {
                return Optional.empty();
            }
            if (event.timer.deadlineMs() <= time.milliseconds()) {
                if (timeOutOperation != null) {
                    timeOutOperation.accept(event.get());
                }
                continue;
            }
            break;
        }
        return Optional.of(event);
    }

    @Override
    public void close() throws Exception {
        sched.shutdown();
    }


    public static class Event<T> implements Comparable<Event<T>> {
        private final Timer timer;
        private final String tag;
        private final T playload;


        public Event(Timer timer, String tag, T payload) {
            this.timer = timer;
            this.tag = tag;
            this.playload = Objects.requireNonNull(payload);
        }

        public T get() {
            return playload;
        }

        public String getTag() {
            return tag;
        }

        @Override
        public int compareTo(Event<T> that) {
            return Long.compare(this.timer.deadlineMs(), that.timer.deadlineMs());
        }

        @Override
        public String toString() {
            return "Event={" +
                    "timer=" + timer +
                    ", tag=" + tag +
                    ", playload=" + playload +
                    "}";
        }

    }
}
