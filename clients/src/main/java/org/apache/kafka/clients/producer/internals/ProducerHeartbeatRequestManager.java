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
package org.apache.kafka.clients.producer.internals;

import org.apache.kafka.clients.ClientRequest;
import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.clients.KafkaClient;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.message.ProducerHeartbeatRequestData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.ProducerHeartbeatRequest;
import org.apache.kafka.common.requests.ProducerHeartbeatResponse;
import org.apache.kafka.common.utils.ProducerIdAndEpoch;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Timer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

public class ProducerHeartbeatRequestManager {

    final Map<Node, NodeHeartbeatState> nodeHeartbeatStates = new HashMap<>();
    final Time time;
    final ProducerMetadata metadata;
    final ProducerNetworkClient networkClient;
    ProducerIdAndEpoch producerIdAndEpoch = ProducerIdAndEpoch.NONE;
    int requestTimeoutMs = 1000;

    public ProducerHeartbeatRequestManager(ProducerMetadata metadata, ProducerNetworkClient networkClient, Time time) {
        this.metadata = metadata;
        this.networkClient = networkClient;
        this.time = time;
    }

    public boolean maybeSendHeartbeatToNode(Node node) {
        if (nodeHeartbeatStates.containsKey(node)) {
            nodeHeartbeatStates.get(node).addPartition();
            return false;
        }

        // FIXME: need to transfer state and config state
        // FIXME: add retrie logic
        NodeHeartbeatState state = new NodeHeartbeatState(time.timer(0), requestTimeoutMs);
        nodeHeartbeatStates.put(node, state);

        return true;
    }

    private boolean maybeRemoveNodeState(Node node) {
        if (!nodeHeartbeatStates.containsKey(node)) {
            return false;
        }
        // FIXME: metadata update require to make sure leader
        if (nodeHeartbeatStates.get(node).partitionSize() == 0) {
            nodeHeartbeatStates.remove(node);
            return true;
        }

        return false;
    }

    /**
     * Attempt to initiate a heartbeat RPC to the given node. This only updates the local
     * heartbeat state; the caller is expected to actually enqueue the request when this
     * method returns {@code true}.
     */
    private CompletableFuture<ClientResponse> sendProducerHeartbeatRequest(Node node, long currentTimeMs) {

        NodeHeartbeatState state = nodeHeartbeatStates.get(node);
        if (state == null) {
            throw  new IllegalStateException("The heartbeat state should not be null for node=" + node.id());
        };
        if (!state.canSendHeartbeat(currentTimeMs)) return null;
        state.markHeartbeatInFlight(currentTimeMs);

        return networkClient.send(node, buildHeartbeatRequest(), requestTimeoutMs);
    }


    /**
     * Update the heartbeat state after the response is processed.
     */
    public void handleProducerHeartbeatRequest(Node node, boolean success, long currentTimeMs) {
        NodeHeartbeatState state = nodeHeartbeatStates.get(node);
        if (state == null) {
            throw new IllegalStateException("Node id=" + node.idString() + " does not send heartbeatRequest.");
        }

        if (success) {
            state.onHeartbeatSuccess(currentTimeMs);
        } else {
            state.onHeartbeatFailure(currentTimeMs);
        }
    }

    private void timeoutRequest(Node node, long currentTimeMs) {
        NodeHeartbeatState state = nodeHeartbeatStates.get(node);
        if (state == null) {
            return;
        }
        state.onHeartbeatFailure(currentTimeMs);
    }

    private ProducerHeartbeatRequest.Builder buildHeartbeatRequest() {
        ProducerHeartbeatRequestData data = new ProducerHeartbeatRequestData()
                .setProducerId(producerIdAndEpoch.producerId)
                .setProducerEpoch(producerIdAndEpoch.epoch);

        return new ProducerHeartbeatRequest.Builder(data);
    }

    public void resetProducerIdAndEpoch(ProducerIdAndEpoch producerIdAndEpoch) {
        this.producerIdAndEpoch = producerIdAndEpoch;
    }

    public Errors errorForResponse(ProducerHeartbeatResponse response) {
        return Errors.forCode(response.data().errorCode());
    }

    public String errorMessageForResponse(ProducerHeartbeatResponse response) {
        return response.data().errorMessage();
    }



    static class NodeHeartbeatState {
        enum Status {
            START(0),
            IN_FLIGHT(1),
            SUCCESS(2),
            FAIL(3);

            private int status;

            Status(int status) {
                this.status = status;
            }

            public int node() {
                return status;
            }
        }

        Status heartbeatStatus = Status.START;
        long heartbeatIntervalMs;
        private final Timer timer;
        private int partitionSize = 0;


        private NodeHeartbeatState(Timer timer, long heartbeatIntervalMs) {
            this.heartbeatIntervalMs = heartbeatIntervalMs;
            this.timer = timer;
        }

        public boolean isExpired(long currentTimeMs) {
            timer.update(currentTimeMs);
            return timer.isExpired();
        }

        public void resetHeartbeatTimeout() {
            timer.reset(heartbeatIntervalMs);
        }

        public void addPartition() {
            partitionSize += 1;
        }

        public int partitionSize() {
            return partitionSize;
        }

        boolean canSendHeartbeat(long currentTimeMs) {
            if (heartbeatStatus == Status.IN_FLIGHT) return false;
            return isExpired(currentTimeMs);
        }

        void markHeartbeatInFlight(long currentTimeMs) {
            heartbeatStatus = Status.IN_FLIGHT;
            timer.update(currentTimeMs);
        }

        void onHeartbeatSuccess(long currentTimeMs) {
            heartbeatStatus = Status.SUCCESS;
            timer.update(currentTimeMs);
            resetHeartbeatTimeout();
        }

        void onHeartbeatFailure(long currentTimeMs) {
            heartbeatStatus = Status.FAIL;
            timer.update(currentTimeMs);
            resetHeartbeatTimeout();
        }
    }
}
