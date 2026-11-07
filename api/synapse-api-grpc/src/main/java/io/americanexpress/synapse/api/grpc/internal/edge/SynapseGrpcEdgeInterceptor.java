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

import java.util.LinkedHashMap;
import java.util.Map;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import io.americanexpress.synapse.core.internal.context.ContextBinder;

/**
 * The outermost Synapse gRPC interceptor. It runs before Boot's observation interceptor, so tracing reads the masked
 * metadata only. For each call it:
 * <ol>
 * <li>reads the correlation candidate: the first value of the correlation metadata, else (absent or empty) the
 * {@code correlationId} baggage candidate;</li>
 * <li>masks the inbound metadata ({@link MetadataMasking}) and passes the copy on;</li>
 * <li>creates the call's {@link GrpcCallState} with the candidates and puts it in the call's gRPC {@link Context}, so
 * every inner interceptor, listener callback and the service see it;</li>
 * <li>wraps the call in a {@link CorrelationEchoCall}, which echoes the resolved correlation id to the caller.</li>
 * </ol>
 */
public final class SynapseGrpcEdgeInterceptor implements ServerInterceptor {

    private final MetadataMasking masking;

    private final Metadata.Key<String> correlationKey;

    /**
     * Creates the interceptor.
     *
     * @param masking the masking of inbound propagation metadata
     * @param correlationKey the metadata key of the correlation id ({@code synapse.correlation.header} in lower case)
     */
    public SynapseGrpcEdgeInterceptor(MetadataMasking masking, Metadata.Key<String> correlationKey) {
        this.masking = masking;
        this.correlationKey = correlationKey;
    }

    /**
     * Returns the masking of inbound propagation metadata.
     *
     * @return the masking
     */
    public MetadataMasking masking() {
        return masking;
    }

    /**
     * Returns the metadata key of the correlation id.
     *
     * @return the key
     */
    public Metadata.Key<String> correlationKey() {
        return correlationKey;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        String correlation = MetadataMasking.firstValue(headers, correlationKey);
        MetadataMasking.Result masked = masking.apply(headers);
        Map<String, String> baggage = new LinkedHashMap<>(masked.candidates());
        String baggageCorrelation = baggage.remove(ContextBinder.CORRELATION_BAGGAGE_FIELD);
        GrpcCallState state = new GrpcCallState(
                correlation == null || correlation.isEmpty() ? baggageCorrelation : correlation, baggage);
        ServerCall<ReqT, RespT> echoCall = new CorrelationEchoCall<>(call, state, correlationKey);
        return Contexts
                .interceptCall(Context.current().withValue(GrpcCallState.KEY, state), echoCall, masked.metadata(),
                        next);
    }
}
