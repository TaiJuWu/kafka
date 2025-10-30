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

package org.apache.kafka.raft.internals;

import org.apache.kafka.common.Node;
import org.apache.kafka.common.feature.SupportedVersionRange;
import org.apache.kafka.common.message.AddRaftVoterResponseData;
import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.ApiVersionsRequest;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Timer;
import org.apache.kafka.raft.Endpoints;
import org.apache.kafka.raft.LeaderState;
import org.apache.kafka.raft.LogOffsetMetadata;
import org.apache.kafka.raft.RaftUtil;
import org.apache.kafka.raft.ReplicaKey;
import org.apache.kafka.raft.VoterSet;
import org.apache.kafka.server.common.KRaftVersion;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

/**
 * Serialized AddVoter: 單一變更進行中，其他 AddVoterRequest 進 pendingQueue，
 * 等前一筆 commit（HW > record offset）後自動接續處理。
 */
public final class AddVoterHandler {
    enum AddVoterStates {
        INIT,
        WAIT_KRAFT_VERSION,
        WAITING_ADD_VOTER_REQUEST,
        SENDING_API_REQUEST,
        WAIT_API_RESPONSE,
        COMMITTING_VOTER,
        COMMITTED_VOTER
    }

    private final KRaftControlRecordStateMachine partitionState;
    private final RequestSender requestSender;
    private final Time time;
    private final Logger logger;
    private final long requestTimeoutConfig;
    private final DeadlineTaskManager deadlineTaskManager;
    private final AddVoterStateMachine addVoterStateMachine;

    /** 序列化用途：在有未提交變更或 active handler 狀態時，排隊等下一輪 */
    private final ArrayDeque<PendingAddVoter> pendingQueue = new ArrayDeque<>();

    /** queue 裡放完整執行需要的參數與回傳 future */
    private record PendingAddVoter(
            ReplicaKey voterKey,
            Endpoints voterEndpoints,
            boolean ackWhenCommitted,
            long currentTimeMs,
            long requestTimeoutMs,
            CompletableFuture<AddRaftVoterResponseData> future
    ) {}

    public AddVoterHandler(
        KRaftControlRecordStateMachine partitionState,
        RequestSender requestSender,
        Time time,
        LogContext logContext,
        long requestTimeoutConfig,
        DeadlineTaskManager deadlineTaskManager
    ) {
        this.partitionState = partitionState;
        this.requestSender = requestSender;
        this.time = time;
        this.logger = logContext.logger(AddVoterHandler.class);
        this.requestTimeoutConfig = requestTimeoutConfig;
        this.deadlineTaskManager = deadlineTaskManager;
        this.addVoterStateMachine = new AddVoterStateMachine(new LogContext("addVoterStateMachine"));
    }

    public CompletableFuture<AddRaftVoterResponseData> handleAddVoterRequest(
        LeaderState<?> leaderState,
        ReplicaKey voterKey,
        Endpoints voterEndpoints,
        boolean ackWhenCommitted,
        long currentTimeMs,
        long requestTimeoutMs
    ) {
        // FIXME: think how to do
        // Check that the cluster supports kraft.version >= 1
        KRaftVersion kraftVersion = partitionState.lastKraftVersion();
        if (!kraftVersion.isReconfigSupported()) {
            return CompletableFuture.completedFuture(
                RaftUtil.addVoterResponse(
                    Errors.UNSUPPORTED_VERSION,
                    String.format(
                        "Cluster doesn't support adding voter because the %s feature is %s",
                        kraftVersion.featureName(),
                        kraftVersion.featureLevel()
                    )
                )
            );
        }

        // 如果目前「有未提交的 voter 變更」或「AddVoter handler 已 active」，就進 queue
        // 這樣保證一次只有一個 reconfiguration 在跑
        if (hasUnCommitedVoter(leaderState) || leaderState.addVoterHandlerState().isPresent()) {
            CompletableFuture<AddRaftVoterResponseData> future = new CompletableFuture<>();
            pendingQueue.addLast(new PendingAddVoter(
                    voterKey, voterEndpoints,
                    ackWhenCommitted, currentTimeMs, requestTimeoutMs, future
            ));
            logger.debug("Queued pending AddVoter for {}", voterKey);
            return future;
        }

        // 沒有進行中的變更，直接開始
        return doHandleAddVoterRequest(
                leaderState, voterKey, voterEndpoints,
                ackWhenCommitted, currentTimeMs, requestTimeoutMs
        );
    }

    /** 抽出主要邏輯，供正常入口與 pendingQueue 皆可呼叫 */
    private CompletableFuture<AddRaftVoterResponseData> doHandleAddVoterRequest(
            LeaderState<?> leaderState,
            ReplicaKey voterKey,
            Endpoints voterEndpoints,
            boolean ackWhenCommitted,
            long currentTimeMs,
            long requestTimeoutMs
    ) {
        addVoterStateMachine.transitionTo(AddVoterStates.WAITING_ADD_VOTER_REQUEST);

        Optional<LogHistory.Entry<VoterSet>> votersEntry = partitionState.lastVoterSetEntry();
        // Check that the new voter id is not part of the current voter set
        VoterSet voters = votersEntry.get().value();
        if (voters.voterIds().contains(voterKey.id())) {
            return CompletableFuture.completedFuture(
                RaftUtil.addVoterResponse(
                    Errors.DUPLICATE_VOTER,
                    String.format(
                        "The voter id for %s is already part of the set of voters %s.",
                        voterKey,
                        voters.voterKeys()
                    )
                )
            );
        }

        final Timer timer = time.timer(Math.max(0, Math.min(requestTimeoutMs, requestTimeoutConfig)));
        final long requestDeadlineMs = timer.deadlineMs();

        // 不能延遲的情況（例如已超時、或要求立即回覆但當前有未提交變更）
        if (requestDeadlineMs <= time.milliseconds()
                || (!ackWhenCommitted && hasUnCommitedVoter(leaderState))) {
            return CompletableFuture.completedFuture(
                    RaftUtil.addVoterResponse(
                            Errors.REQUEST_TIMED_OUT,
                            "Request timeout from AddVoterHandler#handleAddVoterRequest"
                    )
            );
        }

        final AddVoterHandlerState state = new AddVoterHandlerState(
                voterKey,
                voterEndpoints,
                ackWhenCommitted,
                timer
        );

        final String taskName = "deffer addVoterRequest " + voterKey;
        logger.debug("DeadlineTask Manager add deadline={} task='{}' ", timer.deadlineMs(), taskName);

        // 註冊 deadline task：到點先執行 action（嘗試送 API_VERSIONS），過了真正 deadline 再 onTimeout
        deadlineTaskManager.addTask(
                taskName,
                new DeadlineTaskManager.DeadlineTask(
                        timer.deadlineMs(),
                        () -> {
                            leaderState.resetAddVoterHandlerState(Errors.UNKNOWN_SERVER_ERROR, null, Optional.of(state));
                            long sendTime = time.milliseconds();
                            logger.debug("Execute task='{}' at timestamp={}", taskName, sendTime);
                            timer.update(sendTime);
                            // 若已過期，直接 timeout
                            if (timer.isExpired()) {
                                leaderState.resetAddVoterHandlerState(Errors.REQUEST_TIMED_OUT,
                                        "AddVoter cannot finish in time", Optional.empty());
                                state.future().complete(RaftUtil.addVoterResponse(
                                        Errors.REQUEST_TIMED_OUT, "AddVoter cannot finish in time"));
                                // 別忘了這次結束後嘗試啟動 queue 的下一筆
                                maybeStartNextPendingIfIdle(leaderState);
                                return;
                            }

                            addVoterStateMachine.transitionTo(AddVoterStates.SENDING_API_REQUEST);
                            OptionalLong timeout = requestSender.send(
                                    voterEndpoints
                                            .address(requestSender.listenerName())
                                            .map(address -> new Node(voterKey.id(),
                                                    address.getHostName(), address.getPort()))
                                            .orElseThrow(() -> new IllegalArgumentException(
                                                    String.format("Provided listeners %s do not contain a listener for %s",
                                                            voterEndpoints, requestSender.listenerName())
                                            )),
                                    this::buildApiVersionsRequest,
                                    sendTime
                            );
                            if (timeout.isEmpty()) {
                                // 無法送出 → 回 REQUEST_TIMED_OUT
                                leaderState.resetAddVoterHandlerState(Errors.UNKNOWN_SERVER_ERROR, null, Optional.empty());
                                state.future().complete(RaftUtil.addVoterResponse(
                                        Errors.REQUEST_TIMED_OUT,
                                        String.format("New voter %s is not ready to receive requests", voterKey)));
                                // 結束後也嘗試拉起下一筆
                                maybeStartNextPendingIfIdle(leaderState);
                                return;
                            }
                            addVoterStateMachine.transitionTo(AddVoterStates.WAIT_API_RESPONSE);
                        },
                        () -> {
                            // 真正超過 deadline 的 onTimeout：保底
                            addVoterStateMachine.transitionTo(AddVoterStates.WAITING_ADD_VOTER_REQUEST);
                            leaderState.resetAddVoterHandlerState(Errors.UNKNOWN_SERVER_ERROR, null, Optional.empty());
                            state.future().complete(RaftUtil.addVoterResponse(
                                    Errors.REQUEST_TIMED_OUT, "AddVoter cannot finish in time"));
                            // 這次超時結束，換下一筆
                            maybeStartNextPendingIfIdle(leaderState);
                        }
                ),
                timer
        );

        // 立刻 poll 一次，讓相同時間片可觸發 action
        deadlineTaskManager.poll(currentTimeMs);

        return state.future();
    }

    public boolean handleApiVersionsResponse(
        LeaderState<?> leaderState,
        Node source,
        Errors error,
        Optional<ApiVersionsResponseData.SupportedFeatureKey> supportedKraftVersions,
        long currentTimeMs
    ) {
        Optional<AddVoterHandlerState> handlerState = leaderState.addVoterHandlerState();
        if (handlerState.isEmpty()) {
            // There are no pending add operation just ignore the api response
            addVoterStateMachine.transitionTo(AddVoterStates.WAITING_ADD_VOTER_REQUEST);
            return true;
        }
        AddVoterHandlerState current = handlerState.get();

        // Check that the API_VERSIONS response matches the id of the voter getting added
        if (!current.expectingApiResponse(source.id())) {
            logger.info("API_VERSIONS response is not expected from {}: voterKey={}, lastOffset={}",
                    source, current.voterKey(), current.lastOffset());
            addVoterStateMachine.transitionTo(AddVoterStates.WAITING_ADD_VOTER_REQUEST);
            return true;
        }

        // Abort operation if the API_VERSIONS returned an error
        if (error != Errors.NONE) {
            logger.info("Aborting add voter for {} at {} since API_VERSIONS error {}",
                    current.voterKey(), current.voterEndpoints(), error);

            leaderState.resetAddVoterHandlerState(
                Errors.REQUEST_TIMED_OUT,
                String.format(
                    "Aborted add voter operation for since API_VERSIONS returned an error %s",
                    error
                ),
                Optional.empty()
            );
            addVoterStateMachine.transitionTo(AddVoterStates.WAITING_ADD_VOTER_REQUEST);

            // 本次流程已終止 → 啟動下一筆 pending
            maybeStartNextPendingIfIdle(leaderState);
            return false;
        }

        KRaftVersion kraftVersion = partitionState.lastKraftVersion();
        if (!validVersionRange(kraftVersion, supportedKraftVersions)) {
            logger.info("Aborting add voter {} at {} since kraft.version range {} is incompatible",
                    current.voterKey(), current.voterEndpoints(), supportedKraftVersions);

            leaderState.resetAddVoterHandlerState(
                    Errors.INVALID_REQUEST,
                    String.format(
                            "Unsupported finalized version %s by range %s for %s",
                            kraftVersion.featureLevel(),
                            supportedKraftVersions.map(r -> "(min: " + r.minVersion() + ", max: " + r.maxVersion() + ")")
                                    .orElse("(min: 0, max: 0)"),
                            KRaftVersion.FEATURE_NAME
                    ),
                    Optional.empty()
            );
            addVoterStateMachine.transitionTo(AddVoterStates.WAIT_KRAFT_VERSION);

            // 失敗 → 拉起下一筆
            maybeStartNextPendingIfIdle(leaderState);
            return true;
        }

        if (!leaderState.isReplicaCaughtUp(current.voterKey(), currentTimeMs)) {
            logger.info("Aborting add voter {} at {} since it is lagging behind: {}",
                    current.voterKey(), current.voterEndpoints(), leaderState.getReplicaState(current.voterKey()));

            leaderState.resetAddVoterHandlerState(
                    Errors.REQUEST_TIMED_OUT,
                    String.format("Aborted add voter for %s since it is lagging", current.voterKey()),
                    Optional.empty()
            );
            addVoterStateMachine.transitionTo(AddVoterStates.WAITING_ADD_VOTER_REQUEST);

            // 失敗後也嘗試下一筆
            maybeStartNextPendingIfIdle(leaderState);
            return true;
        }

        // Append 新的 VotersRecord
        VoterSet newVoters = partitionState
            .lastVoterSet()
            .addVoter(
                VoterSet.VoterNode.of(
                    current.voterKey(),
                    current.voterEndpoints(),
                    new SupportedVersionRange(
                        supportedKraftVersions.get().minVersion(),
                        supportedKraftVersions.get().maxVersion()
                    )
                )
            )
            .orElseThrow(() ->
                new IllegalStateException(
                    String.format(
                        "Unable to add %s to the set of voters %s",
                        current.voterKey(),
                        partitionState.lastVoterSet()
                    )
                )
            );
        current.setLastOffset(leaderState.appendVotersRecord(newVoters, currentTimeMs));
        if (current.ackWhenCommitted()) {
            // 必須等 commit 才回覆：仍保持 active，不啟動下一筆
            addVoterStateMachine.transitionTo(AddVoterStates.COMMITTING_VOTER);
        } else {
            // 允許立即回覆，但仍僅在「沒有未提交變更」時才會啟動下一筆
            if (hasUnCommitedVoter(leaderState)) {
                // If the state need to be acked immediately, it is disallowed when there is any uncommitted voter on leader
                addVoterStateMachine.transitionTo(AddVoterStates.WAITING_ADD_VOTER_REQUEST);
                current.future().complete(RaftUtil.addVoterResponse(
                        Errors.REQUEST_TIMED_OUT,
                        "Request timed out waiting for leader to handle previous voter change request"
                ));
                // 仍有未提交變更，不啟動下一筆
            } else {
                // complete the future to send response, but do not reset the state,
                // since the new voter set is not yet committed
                addVoterStateMachine.transitionTo(AddVoterStates.COMMITTING_VOTER);
                current.future().complete(RaftUtil.addVoterResponse(Errors.NONE, null));
            }
        }

        return true;
    }

    /** HW 變動：commit 完成 → 完成本輪並啟動下一筆 pending（若有） */
    public void highWatermarkUpdated(LeaderState<?> leaderState) {
        leaderState.addVoterHandlerState().ifPresent(current ->
                leaderState.highWatermark().ifPresent(highWatermark ->
                        current.lastOffset().ifPresent(lastOffset -> {
                            if (highWatermark.offset() > lastOffset) {
                                addVoterStateMachine.transitionTo(AddVoterStates.COMMITTED_VOTER);
                                leaderState.resetAddVoterHandlerState(Errors.NONE, null, Optional.empty());
                                long now = time.milliseconds();
                                deadlineTaskManager.checkTimeout(now);
                                deadlineTaskManager.poll(now);
                                addVoterStateMachine.transitionTo(AddVoterStates.WAITING_ADD_VOTER_REQUEST);

                                // 這裡是序列化的關鍵：上一筆已 commit → 啟動下一筆
                                maybeStartNextPendingIfIdle(leaderState);
                            }
                        })
                )
        );
    }

    private ApiVersionsRequestData buildApiVersionsRequest() {
        return new ApiVersionsRequest.Builder().build().data();
    }

    private boolean validVersionRange(
        KRaftVersion finalizedVersion,
        Optional<ApiVersionsResponseData.SupportedFeatureKey> supportedKraftVersions
    ) {
        return supportedKraftVersions.isPresent()
                && (supportedKraftVersions.get().minVersion() <= finalizedVersion.featureLevel()
                && supportedKraftVersions.get().maxVersion() >= finalizedVersion.featureLevel());
    }

    private boolean hasUnCommitedVoter(LeaderState<?> leaderState) {
        Optional<LogHistory.Entry<VoterSet>> lastVoterSet = partitionState.lastVoterSetEntry();
        Optional<Long> highWatermark = leaderState.highWatermark().map(LogOffsetMetadata::offset);
        if (highWatermark.isEmpty() || lastVoterSet.isEmpty()) {
            return true;
        }

        return lastVoterSet.get().offset() >= highWatermark.get();
    }

    // 依序啟動 queue 裡下一筆（需在系統 idle/無 active handler 且無未提交變更時）
    private void maybeStartNextPendingIfIdle(LeaderState<?> leaderState) {
        // 僅在無進行中的 handler 且無未提交變更時執行下一筆
        if (pendingQueue.isEmpty()) return;

        // 這裡從 queue.peek 看條件，不消耗；符合才 poll 取出
        PendingAddVoter next = pendingQueue.peekFirst();

        if (leaderState.addVoterHandlerState().isPresent() || hasUnCommitedVoter(leaderState)) {
            // 還不能動，等下一次呼叫（例如 HW 更新或錯誤重置後）
            return;
        }

        // 可以開始：取出、執行
        pendingQueue.pollFirst();
        logger.debug("Starting pending AddVoter for {}", next.voterKey());
        doHandleAddVoterRequest(
                leaderState, next.voterKey(), next.voterEndpoints(),
                next.ackWhenCommitted(), next.currentTimeMs(), next.requestTimeoutMs()
        ).whenComplete((resp, ex) -> {
            if (ex != null) next.future().completeExceptionally(ex);
            else next.future().complete(resp);
        });
    }

    // Visible for test
    DeadlineTaskManager deadlineTaskManager() {
        return deadlineTaskManager;
    }

    /** 你原本的狀態機，僅微調命名與 log context */
    private static class AddVoterStateMachine {
        private AddVoterStates state = AddVoterStates.INIT;
        private final Logger log;

        AddVoterStateMachine(Logger log) { this.log = log; }
        AddVoterStateMachine(LogContext logContext) { this.log = logContext.logger(getClass()); }

        public AddVoterStates current() { return state; }

        public void transitionTo(AddVoterStates newState) {
            if (!isValidTransition(state, newState)) {
                throw new IllegalStateException("Invalid transition from " + state + " to " + newState);
            }
            log.debug("State transition from {} to {}", state, newState);
            this.state = newState;
        }

        private boolean isValidTransition(AddVoterStates oldState, AddVoterStates newState) {
            return switch (oldState) {
                case INIT -> newState == AddVoterStates.WAIT_KRAFT_VERSION
                        || newState == AddVoterStates.WAITING_ADD_VOTER_REQUEST; // FIXME: need to be fix.
                case WAITING_ADD_VOTER_REQUEST -> newState == AddVoterStates.SENDING_API_REQUEST
                        || newState == AddVoterStates.WAIT_API_RESPONSE;
                case SENDING_API_REQUEST -> newState == AddVoterStates.WAIT_API_RESPONSE
                        || newState == AddVoterStates.WAITING_ADD_VOTER_REQUEST;
                case WAIT_API_RESPONSE -> newState == AddVoterStates.COMMITTING_VOTER
                        || newState == AddVoterStates.WAITING_ADD_VOTER_REQUEST;
                case COMMITTING_VOTER -> newState == AddVoterStates.COMMITTED_VOTER
                        || newState == AddVoterStates.WAITING_ADD_VOTER_REQUEST;
                case COMMITTED_VOTER -> newState == AddVoterStates.WAITING_ADD_VOTER_REQUEST;
                default -> false;
            };
        }
    }
}
