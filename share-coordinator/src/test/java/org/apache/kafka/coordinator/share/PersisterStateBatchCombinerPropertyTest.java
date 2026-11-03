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

package org.apache.kafka.coordinator.share;

import org.apache.kafka.server.share.persister.PersisterStateBatch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Randomized checks of {@link PersisterStateBatchCombiner} against a per-offset reference:
 * every offset at or above the start offset keeps the batch with the highest delivery count,
 * then the highest delivery state. Small offset ranges make overlaps frequent.
 */
public class PersisterStateBatchCombinerPropertyTest {

    private static final int TRIALS = 5_000;
    private static final int MAX_OFFSET = 40;

    @Test
    public void matchesPerOffsetMaximum() {
        for (int seed = 0; seed < TRIALS; seed++) {
            Random rng = new Random(seed);
            List<PersisterStateBatch> soFar = randomBatches(rng);
            List<PersisterStateBatch> newBatches = randomBatches(rng);
            long startOffset = randomStartOffset(rng);

            List<PersisterStateBatch> result = combine(soFar, newBatches, startOffset);

            List<PersisterStateBatch> all = new ArrayList<>(soFar);
            all.addAll(newBatches);
            assertEquals(reference(all, startOffset), expand(result), "seed " + seed);
            assertSortedDisjointCoalesced(result, seed);
        }
    }

    @Test
    public void resultDoesNotDependOnInputOrder() {
        for (int seed = 0; seed < TRIALS; seed++) {
            Random rng = new Random(seed);
            List<PersisterStateBatch> all = randomBatches(rng);
            all.addAll(randomBatches(rng));
            long startOffset = randomStartOffset(rng);
            List<PersisterStateBatch> expected = combine(all, List.of(), startOffset);

            List<PersisterStateBatch> shuffled = new ArrayList<>(all);
            Collections.shuffle(shuffled, rng);
            int split = rng.nextInt(shuffled.size() + 1);
            List<PersisterStateBatch> result = combine(shuffled.subList(0, split),
                shuffled.subList(split, shuffled.size()), startOffset);

            assertEquals(expected, result, "seed " + seed);
        }
    }

    @Test
    public void reapplyingTheSameBatchesChangesNothing() {
        for (int seed = 0; seed < TRIALS; seed++) {
            Random rng = new Random(seed);
            List<PersisterStateBatch> newBatches = randomBatches(rng);
            long startOffset = randomStartOffset(rng);
            List<PersisterStateBatch> once = combine(randomBatches(rng), newBatches, startOffset);

            assertEquals(once, combine(once, newBatches, startOffset), "seed " + seed);
        }
    }

    @Test
    public void mergingStepByStepEqualsMergingAtOnce() {
        for (int seed = 0; seed < TRIALS; seed++) {
            Random rng = new Random(seed);
            List<PersisterStateBatch> a = randomBatches(rng);
            List<PersisterStateBatch> b = randomBatches(rng);
            List<PersisterStateBatch> c = randomBatches(rng);
            long startOffset = randomStartOffset(rng);

            List<PersisterStateBatch> bc = new ArrayList<>(b);
            bc.addAll(c);
            assertEquals(combine(a, bc, startOffset),
                combine(combine(a, b, startOffset), c, startOffset), "seed " + seed);
        }
    }

    private static List<PersisterStateBatch> combine(List<PersisterStateBatch> soFar,
                                                     List<PersisterStateBatch> newBatches,
                                                     long startOffset) {
        return new PersisterStateBatchCombiner(soFar, newBatches, startOffset).combineStateBatches();
    }

    private static List<PersisterStateBatch> randomBatches(Random rng) {
        int n = rng.nextInt(6);
        List<PersisterStateBatch> batches = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long first = rng.nextInt(MAX_OFFSET);
            long last = first + rng.nextInt(8);
            // AVAILABLE, ACQUIRED, ACKNOWLEDGED, ARCHIVING, ARCHIVED
            byte state = (byte) rng.nextInt(5);
            short count = (short) (rng.nextInt(3) + 1);
            batches.add(new PersisterStateBatch(first, last, state, count));
        }
        return batches;
    }

    private static long randomStartOffset(Random rng) {
        return rng.nextInt(4) == 0 ? -1 : rng.nextInt(MAX_OFFSET);
    }

    private static TreeMap<Long, PersisterStateBatch> reference(List<PersisterStateBatch> batches, long startOffset) {
        TreeMap<Long, PersisterStateBatch> winners = new TreeMap<>();
        for (PersisterStateBatch b : batches) {
            for (long offset = b.firstOffset(); offset <= b.lastOffset(); offset++) {
                if (startOffset != -1 && offset < startOffset) {
                    continue;
                }
                PersisterStateBatch current = winners.get(offset);
                if (current == null || higher(b, current)) {
                    winners.put(offset, b);
                }
            }
        }
        TreeMap<Long, PersisterStateBatch> perOffset = new TreeMap<>();
        winners.forEach((offset, b) -> perOffset.put(offset,
            new PersisterStateBatch(offset, offset, b.deliveryState(), b.deliveryCount())));
        return perOffset;
    }

    private static boolean higher(PersisterStateBatch a, PersisterStateBatch b) {
        if (a.deliveryCount() != b.deliveryCount()) {
            return a.deliveryCount() > b.deliveryCount();
        }
        return a.deliveryState() > b.deliveryState();
    }

    private static TreeMap<Long, PersisterStateBatch> expand(List<PersisterStateBatch> batches) {
        TreeMap<Long, PersisterStateBatch> perOffset = new TreeMap<>();
        for (PersisterStateBatch b : batches) {
            for (long offset = b.firstOffset(); offset <= b.lastOffset(); offset++) {
                perOffset.put(offset, new PersisterStateBatch(offset, offset, b.deliveryState(), b.deliveryCount()));
            }
        }
        return perOffset;
    }

    private static void assertSortedDisjointCoalesced(List<PersisterStateBatch> result, int seed) {
        for (int i = 0; i < result.size(); i++) {
            PersisterStateBatch b = result.get(i);
            assertTrue(b.firstOffset() <= b.lastOffset(), "seed " + seed + ": " + b);
            if (i > 0) {
                PersisterStateBatch prev = result.get(i - 1);
                assertTrue(prev.lastOffset() < b.firstOffset(), "seed " + seed + ": " + prev + " overlaps " + b);
                boolean adjacentSame = prev.lastOffset() + 1 == b.firstOffset()
                    && prev.deliveryState() == b.deliveryState()
                    && prev.deliveryCount() == b.deliveryCount();
                assertTrue(!adjacentSame, "seed " + seed + ": " + prev + " and " + b + " should be coalesced");
            }
        }
    }
}
