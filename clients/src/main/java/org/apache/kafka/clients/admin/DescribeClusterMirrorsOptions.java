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

/**
 * Options for {@link Admin#describeClusterMirrors} (KIP-1279).
 */
public class DescribeClusterMirrorsOptions extends AbstractOptions<DescribeClusterMirrorsOptions> {
    private boolean includeMirrorState = false;
    private boolean includeMirrorOffset = false;
    private boolean includeAuthorizedOperations = false;
    private String clusterId = null;

    public DescribeClusterMirrorsOptions includeMirrorState(boolean includeMirrorState) {
        this.includeMirrorState = includeMirrorState;
        return this;
    }

    public boolean includeMirrorState() {
        return includeMirrorState;
    }

    public DescribeClusterMirrorsOptions includeMirrorOffset(boolean includeMirrorOffset) {
        this.includeMirrorOffset = includeMirrorOffset;
        return this;
    }

    public boolean includeMirrorOffset() {
        return includeMirrorOffset;
    }

    public DescribeClusterMirrorsOptions includeAuthorizedOperations(boolean includeAuthorizedOperations) {
        this.includeAuthorizedOperations = includeAuthorizedOperations;
        return this;
    }

    public boolean includeAuthorizedOperations() {
        return includeAuthorizedOperations;
    }

    /**
     * The cluster id of the requesting cluster, with which a failback looks up the epochs to truncate to.
     */
    public DescribeClusterMirrorsOptions clusterId(String clusterId) {
        this.clusterId = clusterId;
        return this;
    }

    public String clusterId() {
        return clusterId;
    }
}
