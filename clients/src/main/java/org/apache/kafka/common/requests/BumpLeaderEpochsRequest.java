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

import org.apache.kafka.common.message.BumpLeaderEpochsRequestData;
import org.apache.kafka.common.message.BumpLeaderEpochsResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Readable;

/**
 * Sent by the brokers to the controller to bump the leader epochs of mirror partitions to at least the given values
 * (KIP-1279).
 */
public class BumpLeaderEpochsRequest extends AbstractRequest {
    private final BumpLeaderEpochsRequestData data;

    public BumpLeaderEpochsRequest(BumpLeaderEpochsRequestData data, short apiVersion) {
        super(ApiKeys.BUMP_LEADER_EPOCHS, apiVersion);
        this.data = data;
    }

    @Override
    public BumpLeaderEpochsRequestData data() {
        return data;
    }

    @Override
    public AbstractResponse getErrorResponse(int throttleTimeMs, Throwable e) {
        ApiError error = ApiError.fromThrowable(e);
        return new BumpLeaderEpochsResponse(new BumpLeaderEpochsResponseData()
            .setThrottleTimeMs(throttleTimeMs)
            .setErrorCode(error.error().code())
            .setErrorMessage(error.message()));
    }

    public static BumpLeaderEpochsRequest parse(Readable readable, short version) {
        return new BumpLeaderEpochsRequest(new BumpLeaderEpochsRequestData(readable, version), version);
    }

    public static class Builder extends AbstractRequest.Builder<BumpLeaderEpochsRequest> {
        private final BumpLeaderEpochsRequestData data;

        public Builder(BumpLeaderEpochsRequestData data) {
            super(ApiKeys.BUMP_LEADER_EPOCHS);
            this.data = data;
        }

        @Override
        public BumpLeaderEpochsRequest build(short version) {
            return new BumpLeaderEpochsRequest(data, version);
        }

        @Override
        public String toString() {
            return data.toString();
        }
    }
}
