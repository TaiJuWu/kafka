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

import org.apache.kafka.common.message.ListClusterMirrorsRequestData;
import org.apache.kafka.common.message.ListClusterMirrorsResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Readable;

/**
 * The ListClusterMirrors request of cluster mirroring (KIP-1279).
 */
public class ListClusterMirrorsRequest extends AbstractRequest {
    private final ListClusterMirrorsRequestData data;

    public ListClusterMirrorsRequest(ListClusterMirrorsRequestData data, short apiVersion) {
        super(ApiKeys.LIST_CLUSTER_MIRRORS, apiVersion);
        this.data = data;
    }

    @Override
    public ListClusterMirrorsRequestData data() {
        return data;
    }

    @Override
    public ListClusterMirrorsResponse getErrorResponse(int throttleTimeMs, Throwable e) {
        ApiError error = ApiError.fromThrowable(e);
        return new ListClusterMirrorsResponse(new ListClusterMirrorsResponseData()
            .setThrottleTimeMs(throttleTimeMs)
            .setErrorCode(error.error().code())
            .setErrorMessage(error.message()));
    }

    public static ListClusterMirrorsRequest parse(Readable readable, short version) {
        return new ListClusterMirrorsRequest(new ListClusterMirrorsRequestData(readable, version), version);
    }

    public static class Builder extends AbstractRequest.Builder<ListClusterMirrorsRequest> {
        private final ListClusterMirrorsRequestData data;

        public Builder(ListClusterMirrorsRequestData data) {
            super(ApiKeys.LIST_CLUSTER_MIRRORS);
            this.data = data;
        }

        @Override
        public ListClusterMirrorsRequest build(short version) {
            return new ListClusterMirrorsRequest(data, version);
        }

        @Override
        public String toString() {
            return data.toString();
        }
    }
}
