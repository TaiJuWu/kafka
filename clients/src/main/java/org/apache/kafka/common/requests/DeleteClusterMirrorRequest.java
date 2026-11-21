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
package org.apache.kafka.common.requests;

import org.apache.kafka.common.message.DeleteClusterMirrorRequestData;
import org.apache.kafka.common.message.DeleteClusterMirrorResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Readable;

/**
 * The DeleteClusterMirror request of cluster mirroring (KIP-1279).
 */
public class DeleteClusterMirrorRequest extends AbstractRequest {
    private final DeleteClusterMirrorRequestData data;

    public DeleteClusterMirrorRequest(DeleteClusterMirrorRequestData data, short apiVersion) {
        super(ApiKeys.DELETE_CLUSTER_MIRROR, apiVersion);
        this.data = data;
    }

    @Override
    public DeleteClusterMirrorRequestData data() {
        return data;
    }

    @Override
    public DeleteClusterMirrorResponse getErrorResponse(int throttleTimeMs, Throwable e) {
        ApiError error = ApiError.fromThrowable(e);
        return new DeleteClusterMirrorResponse(new DeleteClusterMirrorResponseData()
            .setThrottleTimeMs(throttleTimeMs)
            .setErrorCode(error.error().code())
            .setErrorMessage(error.message()));
    }

    public static DeleteClusterMirrorRequest parse(Readable readable, short version) {
        return new DeleteClusterMirrorRequest(new DeleteClusterMirrorRequestData(readable, version), version);
    }

    public static class Builder extends AbstractRequest.Builder<DeleteClusterMirrorRequest> {
        private final DeleteClusterMirrorRequestData data;

        public Builder(DeleteClusterMirrorRequestData data) {
            super(ApiKeys.DELETE_CLUSTER_MIRROR);
            this.data = data;
        }

        @Override
        public DeleteClusterMirrorRequest build(short version) {
            return new DeleteClusterMirrorRequest(data, version);
        }

        @Override
        public String toString() {
            return data.toString();
        }
    }
}
