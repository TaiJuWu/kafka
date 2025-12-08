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

package org.apache.kafka.jmh.clients;

import org.apache.kafka.clients.consumer.internals.RequestFuture;
import org.apache.kafka.clients.consumer.internals.RequestFutureListener;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Benchmark for RequestFuture performance characteristics.
 * Tests various scenarios including:
 * - Basic completion and failure
 * - Listener registration and notification
 * - Concurrent listener addition
 * - Chain and compose operations
 */
public class RequestFutureBenchmark {

    @State(Scope.Benchmark)
    public static class ListenerCountState {
        @Param({"1", "5", "10", "50"})
        public int listenerCount;
    }

    /**
     * Benchmark basic completion of a RequestFuture
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
    @Measurement(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
    @Fork(value = 1, warmups = 0)
    public void basicCompletion(Blackhole blackhole) {
        RequestFuture<String> future = new RequestFuture<>();
        future.complete("test");
        blackhole.consume(future.value());
    }

    /**
     * Benchmark basic failure of a RequestFuture
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
    @Measurement(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
    @Fork(value = 1, warmups = 0)
    public void basicFailure(Blackhole blackhole) {
        RequestFuture<String> future = new RequestFuture<>();
        RuntimeException e = new RuntimeException("test error");
        future.raise(e);
        blackhole.consume(future.exception());
    }

    /**
     * Benchmark listener registration before completion
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
    @Measurement(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
    @Fork(value = 1, warmups = 0)
    public void listenerBeforeCompletion(ListenerCountState state, Blackhole blackhole) {
        RequestFuture<String> future = new RequestFuture<>();

        for (int i = 0; i < state.listenerCount; i++) {
            future.addListener(new NoOpListener<>());
        }

        future.complete("test");
        blackhole.consume(future.value());
    }

    /**
     * Benchmark listener registration after completion
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
    @Measurement(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
    @Fork(value = 1, warmups = 0)
    public void listenerAfterCompletion(ListenerCountState state, Blackhole blackhole) {
        RequestFuture<String> future = new RequestFuture<>();
        future.complete("test");

        for (int i = 0; i < state.listenerCount; i++) {
            future.addListener(new NoOpListener<>());
        }

        blackhole.consume(future.value());
    }

    /**
     * Benchmark chaining two futures
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
    @Measurement(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
    @Fork(value = 1, warmups = 0)
    public void chainFutures(Blackhole blackhole) {
        RequestFuture<String> source = new RequestFuture<>();
        RequestFuture<String> chained = new RequestFuture<>();

        source.chain(chained);
        source.complete("test");

        blackhole.consume(chained.value());
    }

    /**
     * Benchmark composing futures with type transformation
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
    @Measurement(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
    @Fork(value = 1, warmups = 0)
    public void composeFutures(Blackhole blackhole) {
        RequestFuture<String> source = new RequestFuture<>();
        RequestFuture<Integer> composed = source.compose(new StringToLengthAdapter());

        source.complete("test");

        blackhole.consume(composed.value());
    }

    /**
     * Benchmark static factory methods
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
    @Measurement(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
    @Fork(value = 1, warmups = 0)
    public void staticFactoryMethods(Blackhole blackhole) {
        blackhole.consume(RequestFuture.voidSuccess());
        blackhole.consume(RequestFuture.coordinatorNotAvailable());
        blackhole.consume(RequestFuture.noBrokersAvailable());
        blackhole.consume(RequestFuture.failure(new RuntimeException("test")));
    }

    /**
     * Benchmark completion with listener that performs work
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
    @Measurement(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
    @Fork(value = 1, warmups = 0)
    public void completionWithWorkingListener(ListenerCountState state, Blackhole blackhole) {
        RequestFuture<String> future = new RequestFuture<>();

        for (int i = 0; i < state.listenerCount; i++) {
            future.addListener(new WorkingListener<>(blackhole));
        }

        future.complete("test");
        blackhole.consume(future.value());
    }

    /**
     * Benchmark average latency of completion
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
    @Measurement(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
    @Fork(value = 1, warmups = 0)
    public void averageCompletionLatency(Blackhole blackhole) {
        RequestFuture<String> future = new RequestFuture<>();
        future.complete("test");
        blackhole.consume(future.value());
    }

    /**
     * Benchmark mixed operations (complete, chain, compose)
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
    @Measurement(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
    @Fork(value = 1, warmups = 0)
    public void mixedOperations(Blackhole blackhole) {
        RequestFuture<String> source = new RequestFuture<>();
        RequestFuture<String> chained = new RequestFuture<>();
        RequestFuture<Integer> composed = source.compose(new StringToLengthAdapter());

        source.addListener(new NoOpListener<>());
        source.chain(chained);
        source.complete("test");

        blackhole.consume(source.value());
        blackhole.consume(chained.value());
        blackhole.consume(composed.value());
    }

    /**
     * Benchmark state checking operations
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
    @Measurement(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
    @Fork(value = 1, warmups = 0)
    public void stateChecking(Blackhole blackhole) {
        RequestFuture<String> future = new RequestFuture<>();
        blackhole.consume(future.isDone());
        blackhole.consume(future.succeeded());
        blackhole.consume(future.failed());

        future.complete("test");

        blackhole.consume(future.isDone());
        blackhole.consume(future.succeeded());
        blackhole.consume(future.failed());
    }

    // Helper classes

    private static class NoOpListener<T> implements RequestFutureListener<T> {
        @Override
        public void onSuccess(T value) {
            // No-op
        }

        @Override
        public void onFailure(RuntimeException e) {
            // No-op
        }
    }

    private static class WorkingListener<T> implements RequestFutureListener<T> {
        private final Blackhole blackhole;

        WorkingListener(Blackhole blackhole) {
            this.blackhole = blackhole;
        }

        @Override
        public void onSuccess(T value) {
            // Simulate some work
            blackhole.consume(value);
            blackhole.consume(value.hashCode());
        }

        @Override
        public void onFailure(RuntimeException e) {
            blackhole.consume(e);
            blackhole.consume(e.getMessage());
        }
    }

    private static class StringToLengthAdapter extends org.apache.kafka.clients.consumer.internals.RequestFutureAdapter<String, Integer> {
        @Override
        public void onSuccess(String value, RequestFuture<Integer> future) {
            future.complete(value.length());
        }
    }
}
