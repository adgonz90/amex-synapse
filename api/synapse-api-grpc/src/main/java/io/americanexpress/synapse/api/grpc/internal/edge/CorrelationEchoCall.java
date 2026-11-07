/*
 * Copyright 2026 American Express Travel Related Services Company, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.americanexpress.synapse.api.grpc.internal.edge;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;

/**
 * Echoes the call's resolved correlation id to the caller: in the response headers when they are sent, or in the
 * trailers when the call closes before any headers were sent (a trailers-only response). The echoed value is always
 * the resolved id from the {@link GrpcCallState}, replacing any value set further in; nothing is added while the state
 * has no id yet.
 *
 * @param <ReqT> the request type
 * @param <RespT> the response type
 */
final class CorrelationEchoCall<ReqT, RespT>
        extends
            ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {

    private final GrpcCallState state;

    private final Metadata.Key<String> correlationKey;

    private volatile boolean headersSent;

    /**
     * Wraps a call.
     *
     * @param delegate the call
     * @param state the call's state, which holds the resolved id
     * @param correlationKey the metadata key of the correlation id
     */
    CorrelationEchoCall(ServerCall<ReqT, RespT> delegate, GrpcCallState state,
            Metadata.Key<String> correlationKey) {
        super(delegate);
        this.state = state;
        this.correlationKey = correlationKey;
    }

    @Override
    public void sendHeaders(Metadata headers) {
        echo(headers);
        headersSent = true;
        super.sendHeaders(headers);
    }

    @Override
    public void close(Status status, Metadata trailers) {
        if (!headersSent) {
            echo(trailers);
        }
        super.close(status, trailers);
    }

    private void echo(Metadata metadata) {
        String correlationId = state.correlationId();
        if (correlationId == null) {
            return;
        }
        metadata.discardAll(correlationKey);
        metadata.put(correlationKey, correlationId);
    }
}
