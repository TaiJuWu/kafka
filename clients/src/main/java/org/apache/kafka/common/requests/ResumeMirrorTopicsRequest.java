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

import org.apache.kafka.common.message.ResumeMirrorTopicsRequestData;
import org.apache.kafka.common.message.ResumeMirrorTopicsResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Readable;

/**
 * The ResumeMirrorTopics request of cluster mirroring (KIP-1279).
 */
public class ResumeMirrorTopicsRequest extends AbstractRequest {
    private final ResumeMirrorTopicsRequestData data;

    public ResumeMirrorTopicsRequest(ResumeMirrorTopicsRequestData data, short apiVersion) {
        super(ApiKeys.RESUME_MIRROR_TOPICS, apiVersion);
        this.data = data;
    }

    @Override
    public ResumeMirrorTopicsRequestData data() {
        return data;
    }

    @Override
    public ResumeMirrorTopicsResponse getErrorResponse(int throttleTimeMs, Throwable e) {
        ApiError error = ApiError.fromThrowable(e);
        return new ResumeMirrorTopicsResponse(new ResumeMirrorTopicsResponseData()
            .setThrottleTimeMs(throttleTimeMs)
            .setErrorCode(error.error().code())
            .setErrorMessage(error.message()));
    }

    public static ResumeMirrorTopicsRequest parse(Readable readable, short version) {
        return new ResumeMirrorTopicsRequest(new ResumeMirrorTopicsRequestData(readable, version), version);
    }

    public static class Builder extends AbstractRequest.Builder<ResumeMirrorTopicsRequest> {
        private final ResumeMirrorTopicsRequestData data;

        public Builder(ResumeMirrorTopicsRequestData data) {
            super(ApiKeys.RESUME_MIRROR_TOPICS);
            this.data = data;
        }

        @Override
        public ResumeMirrorTopicsRequest build(short version) {
            return new ResumeMirrorTopicsRequest(data, version);
        }

        @Override
        public String toString() {
            return data.toString();
        }
    }
}
