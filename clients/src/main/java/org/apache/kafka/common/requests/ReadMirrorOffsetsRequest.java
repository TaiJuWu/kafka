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

import org.apache.kafka.common.message.ReadMirrorOffsetsRequestData;
import org.apache.kafka.common.message.ReadMirrorOffsetsResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Readable;

/**
 * The ReadMirrorOffsets request of cluster mirroring (KIP-1279).
 */
public class ReadMirrorOffsetsRequest extends AbstractRequest {
    private final ReadMirrorOffsetsRequestData data;

    public ReadMirrorOffsetsRequest(ReadMirrorOffsetsRequestData data, short apiVersion) {
        super(ApiKeys.READ_MIRROR_OFFSETS, apiVersion);
        this.data = data;
    }

    @Override
    public ReadMirrorOffsetsRequestData data() {
        return data;
    }

    @Override
    public ReadMirrorOffsetsResponse getErrorResponse(int throttleTimeMs, Throwable e) {
        ApiError error = ApiError.fromThrowable(e);
        return new ReadMirrorOffsetsResponse(new ReadMirrorOffsetsResponseData()
            .setThrottleTimeMs(throttleTimeMs)
            .setErrorCode(error.error().code())
            .setErrorMessage(error.message()));
    }

    public static ReadMirrorOffsetsRequest parse(Readable readable, short version) {
        return new ReadMirrorOffsetsRequest(new ReadMirrorOffsetsRequestData(readable, version), version);
    }

    public static class Builder extends AbstractRequest.Builder<ReadMirrorOffsetsRequest> {
        private final ReadMirrorOffsetsRequestData data;

        public Builder(ReadMirrorOffsetsRequestData data) {
            super(ApiKeys.READ_MIRROR_OFFSETS);
            this.data = data;
        }

        @Override
        public ReadMirrorOffsetsRequest build(short version) {
            return new ReadMirrorOffsetsRequest(data, version);
        }

        @Override
        public String toString() {
            return data.toString();
        }
    }
}
