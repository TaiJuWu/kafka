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

package org.apache.kafka.jmh.util;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This benchmark calculates the empirical evidence of different implementation for encoding/decoding a protobuf
 * <a href="https://protobuf.dev/programming-guides/encoding/#varints">VarInt</a> and VarLong.
 *
 * The benchmark uses JMH and calculates results for different sizes of variable length integer. We expect most of the
 * usage in Kafka code base to be 1 or 2 byte integers.
 */
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.All)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Threads(2)
public class CopyOnWriteArrayListBenchmark {
    private static final int TIMES = 100_000;

    @Param({"1000"})
    private int init_size;
    private CopyOnWriteArrayList<Integer> copyOnWriteArrayList;
    private CopyOnWriteArrayList<Integer> DeleteSeq;

    @Setup(Level.Invocation)
    public void setup() {

        List<Integer> arrayListTemplate =  IntStream.range(0, init_size).boxed()
                .collect(Collectors.toList());
        copyOnWriteArrayList = new CopyOnWriteArrayList<>(arrayListTemplate);
        DeleteSeq = new CopyOnWriteArrayList<>(arrayListTemplate);
        Collections.shuffle(DeleteSeq);
    }

    @Benchmark
    @OperationsPerInvocation(TIMES)
    public void testCopyOnWriteArrayListRemove(Blackhole blackhole) {
        for (Integer obj: DeleteSeq) {
            blackhole.consume(copyOnWriteArrayList.remove(obj));
        }
    }
}
