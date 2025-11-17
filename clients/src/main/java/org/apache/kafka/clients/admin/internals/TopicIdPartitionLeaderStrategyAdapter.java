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
package org.apache.kafka.clients.admin.internals;

import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.requests.AbstractResponse;
import org.apache.kafka.common.utils.LogContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adapter that allows existing {@link PartitionLeaderStrategy} logic, which operates on {@link TopicPartition},
 * to be reused for topic-id aware handlers that operate with {@link TopicIdPartition}.
 */
public class TopicIdPartitionLeaderStrategyAdapter implements AdminApiLookupStrategy<TopicIdPartition> {

    private final PartitionLeaderStrategy delegate;
    private final PartitionLeaderCache partitionLeaderCache;

    public TopicIdPartitionLeaderStrategyAdapter(
        LogContext logContext,
        boolean tolerateUnknownTopics,
        PartitionLeaderCache partitionLeaderCache
    ) {
        this(new PartitionLeaderStrategy(logContext, tolerateUnknownTopics), partitionLeaderCache);
    }

    public TopicIdPartitionLeaderStrategyAdapter(
        PartitionLeaderStrategy delegate,
        PartitionLeaderCache partitionLeaderCache
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.partitionLeaderCache = Objects.requireNonNull(partitionLeaderCache, "partitionLeaderCache");
    }

    @Override
    public ApiRequestScope lookupScope(TopicIdPartition key) {
        return delegate.lookupScope(toTopicPartition(key));
    }

    @Override
    public AbstractRequest.Builder<?> buildRequest(Set<TopicIdPartition> keys) {
        return delegate.buildRequest(convert(keys).topicPartitions());
    }

    @Override
    public LookupResult<TopicIdPartition> handleResponse(Set<TopicIdPartition> keys, AbstractResponse response) {
        ConvertedKeys converted = convert(keys);
        LookupResult<TopicPartition> delegateResult =
            delegate.handleResponse(converted.topicPartitions(), response);
        return adaptResult(delegateResult, converted.reverseMapping());
    }

    @Override
    public Map<TopicIdPartition, Throwable> handleUnsupportedVersionException(
        UnsupportedVersionException exception,
        Set<TopicIdPartition> keys
    ) {
        return convert(keys).reverseMapping().values().stream()
            .collect(Collectors.toMap(k -> k, k -> exception));
    }

    private TopicPartition toTopicPartition(TopicIdPartition key) {
        String topic = key.topic();
        if (topic == null || topic.isEmpty()) {
            if (key.topicId() == null || key.topicId().equals(Uuid.ZERO_UUID)) {
                throw new IllegalStateException("TopicIdPartition must define a topic name or topic id: " + key);
            }
            topic = partitionLeaderCache.getTopicNameById(key.topicId());
            if (topic == null) {
                throw new IllegalStateException("Topic name for id " + key.topicId() + " is not cached yet");
            }
        }
        return new TopicPartition(topic, key.partition());
    }

    private ConvertedKeys convert(Set<TopicIdPartition> keys) {
        Map<TopicPartition, TopicIdPartition> reverse = new HashMap<>();
        for (TopicIdPartition key : keys) {
            TopicPartition tp = toTopicPartition(key);
            reverse.put(tp, key);
        }
        return new ConvertedKeys(reverse);
    }

    private LookupResult<TopicIdPartition> adaptResult(
        LookupResult<TopicPartition> delegateResult,
        Map<TopicPartition, TopicIdPartition> reverse
    ) {
        List<TopicIdPartition> completed =
            delegateResult.completedKeys.stream().map(reverse::get).collect(Collectors.toList());
        Map<TopicIdPartition, Throwable> failed = delegateResult.failedKeys.entrySet().stream()
            .collect(Collectors.toMap(e -> reverse.get(e.getKey()), Map.Entry::getValue));
        Map<TopicIdPartition, Integer> mapped = delegateResult.mappedKeys.entrySet().stream()
            .collect(Collectors.toMap(e -> reverse.get(e.getKey()), Map.Entry::getValue));
        return new LookupResult<>(completed, failed, mapped);
    }

    private static final class ConvertedKeys {
        private final Map<TopicPartition, TopicIdPartition> reverse;

        ConvertedKeys(Map<TopicPartition, TopicIdPartition> reverse) {
            this.reverse = reverse;
        }

        Set<TopicPartition> topicPartitions() {
            return reverse.keySet();
        }

        Map<TopicPartition, TopicIdPartition> reverseMapping() {
            return reverse;
        }
    }
}
