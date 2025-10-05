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

package org.apache.kafka.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class MemorySlab<T> {
    List<T> allocatedSlab = new ArrayList<>();
    Supplier<T> supplier;
    int idx = -1;


    public MemorySlab(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    public MemorySlab(Supplier<T> supplier, int preAllocateSize) {
        this.supplier = supplier;
        for (int i = 0; i < preAllocateSize; i++) {
            allocatedSlab.add(supplier.get());
        }
    }

    public Optional<T> tryAllocate() {
        if (allocatedSlab.isEmpty()) {
            return Optional.empty();
        }

        T object = allocatedSlab.remove(0);
//        usedSlab.add(object);
        return Optional.of(object);
    }

    public void release(T object) {
//        usedSlab.remove(object);
//        allocatedSlab.add(object);
    }

    public T allocate() {
        idx += 1;
        idx = idx % allocatedSlab.size();
        return allocatedSlab.get(idx);
    }

    public void clear() {
        allocatedSlab.clear();
    }

    public void close() {
        allocatedSlab.clear();
    }
}
