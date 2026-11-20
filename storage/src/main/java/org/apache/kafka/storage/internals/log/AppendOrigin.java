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
package org.apache.kafka.storage.internals.log;

/**
 * The source of an append to the log. This is used when determining required validations.
 */
public enum AppendOrigin {
    /**
     * The log append came through replication from the leader. This typically implies minimal validation.
     * Particularly, we do not decompress record batches in order to validate records individually.
     */
    REPLICATION,

    /**
     * The log append came from either the group coordinator or the transaction coordinator. We validate
     * producer epochs for normal log entries (specifically offset commits from the group coordinator) and
     * we validate coordinate end transaction markers from the transaction coordinator.
     */
    COORDINATOR,

    /**
     * The log append came from the client, which implies full validation.
     */
    CLIENT,

    /**
     * The log append come from the raft leader, which implies the offsets has been assigned
     */
    RAFT_LEADER,

    /**
     * The log append came from a cluster mirror: the leader of a mirror partition copies the record batches
     * fetched from the source cluster as they are, so the offsets, leader epochs and timestamps of the source
     * are kept. Like {@link #REPLICATION}, the records were validated when they were first written.
     */
    MIRROR;

    /**
     * @return true if the records were validated where they were first written, so the append keeps their
     *         offsets and applies lenient validation: {@link #REPLICATION} and {@link #MIRROR}.
     */
    public boolean isCopy() {
        return this == REPLICATION || this == MIRROR;
    }
}
