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

package org.apache.kafka.clients.admin;

import java.util.Map;

/**
 * Options for {@link Admin#deleteRecords(Map, DeleteRecordsOptions)}.
 */
public class DeleteRecordsOptions extends AbstractOptions<DeleteRecordsOptions> {

    private short acks = -1;

    /**
     * The acknowledgement level the broker should use before responding.
     *
     * <ul>
     *   <li>{@code -1} (default): the broker waits for every alive replica to advance its
     *       log start offset past the requested offset. This is the historical behavior and
     *       is required for use cases that rely on cross-replica deletion guarantees
     *       (e.g. privacy / compliance flows).</li>
     *   <li>{@code 1}: the broker responds as soon as the leader has truncated locally,
     *       without waiting for follower acknowledgement. Suitable when deletion is an
     *       operational optimization rather than a correctness guarantee.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if {@code acks} is not {@code -1} or {@code 1}
     */
    public DeleteRecordsOptions acks(short acks) {
        if (acks != -1 && acks != 1) {
            throw new IllegalArgumentException("acks must be -1 or 1, got " + acks);
        }
        this.acks = acks;
        return this;
    }

    public short acks() {
        return acks;
    }
}
