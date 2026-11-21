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

import org.apache.kafka.common.message.DescribeClusterMirrorsRequestData;
import org.apache.kafka.common.message.DescribeClusterMirrorsResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Readable;

/**
 * The DescribeClusterMirrors request of cluster mirroring (KIP-1279).
 */
public class DescribeClusterMirrorsRequest extends AbstractRequest {
    private final DescribeClusterMirrorsRequestData data;

    public DescribeClusterMirrorsRequest(DescribeClusterMirrorsRequestData data, short apiVersion) {
        super(ApiKeys.DESCRIBE_CLUSTER_MIRRORS, apiVersion);
        this.data = data;
    }

    @Override
    public DescribeClusterMirrorsRequestData data() {
        return data;
    }

    @Override
    public DescribeClusterMirrorsResponse getErrorResponse(int throttleTimeMs, Throwable e) {
        ApiError error = ApiError.fromThrowable(e);
        return new DescribeClusterMirrorsResponse(new DescribeClusterMirrorsResponseData()
            .setThrottleTimeMs(throttleTimeMs)
            .setErrorCode(error.error().code())
            .setErrorMessage(error.message()));
    }

    public static DescribeClusterMirrorsRequest parse(Readable readable, short version) {
        return new DescribeClusterMirrorsRequest(new DescribeClusterMirrorsRequestData(readable, version), version);
    }

    public static class Builder extends AbstractRequest.Builder<DescribeClusterMirrorsRequest> {
        private final DescribeClusterMirrorsRequestData data;

        public Builder(DescribeClusterMirrorsRequestData data) {
            super(ApiKeys.DESCRIBE_CLUSTER_MIRRORS);
            this.data = data;
        }

        @Override
        public DescribeClusterMirrorsRequest build(short version) {
            return new DescribeClusterMirrorsRequest(data, version);
        }

        @Override
        public String toString() {
            return data.toString();
        }
    }
}
