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
package io.americanexpress.synapse.api.grpc.internal.error;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.grpc.HandlerRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.StatusException;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.GrpcErrors;
import io.americanexpress.synapse.api.grpc.internal.edge.GrpcCallState;
import io.americanexpress.synapse.api.grpc.internal.edge.MetadataMasking;
import io.americanexpress.synapse.api.grpc.internal.service.RawBytesMarshaller;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;

/**
 * The gRPC server's fallback registry: it answers every method no service registered. The call is closed at once with
 * {@code UNIMPLEMENTED} and the Synapse error details ({@code ErrorInfo} with the reason
 * {@link GrpcErrors#METHOD_NOT_FOUND}), and the correlation id is echoed in the trailers. The id is the caller's first
 * correlation metadata value when {@link CorrelationIdPolicy} accepts it, else a generated one. The request is never
 * read.
 *
 * <p>
 * grpc-java runs a fallback method without the server's interceptors, so neither the edge nor tracing sees these calls:
 * this registry resolves the correlation id itself.
 */
public final class FallbackHandlerRegistry extends HandlerRegistry {

    private final GrpcStatusWriter writer;

    private final CorrelationIdPolicy correlation;

    private final Metadata.Key<String> correlationKey;

    /**
     * Creates the registry.
     *
     * @param writer writes the {@code METHOD_NOT_FOUND} status
     * @param correlation accepts or replaces the caller's correlation id
     * @param correlationKey the metadata key of the correlation id ({@code synapse.correlation.header} in lower case)
     */
    public FallbackHandlerRegistry(GrpcStatusWriter writer, CorrelationIdPolicy correlation,
            Metadata.Key<String> correlationKey) {
        this.writer = writer;
        this.correlation = correlation;
        this.correlationKey = correlationKey;
    }

    /**
     * Returns a definition, named after the called method, whose handler answers {@code UNIMPLEMENTED}.
     *
     * @param methodName the full name of the called method
     * @param authority the call's authority, ignored
     * @return the definition, never {@code null}
     */
    @Override
    public ServerMethodDefinition<?, ?> lookupMethod(String methodName, @Nullable String authority) {
        MethodDescriptor<byte[], byte[]> method = MethodDescriptor
                .newBuilder(RawBytesMarshaller.INSTANCE, RawBytesMarshaller.INSTANCE)
                .setFullMethodName(methodName)
                .setType(MethodDescriptor.MethodType.UNKNOWN)
                .build();
        return ServerMethodDefinition.create(method, new Unimplemented());
    }

    /**
     * Returns no services: the fallback registry serves no service of its own, so reflection lists none from it.
     *
     * @return an empty list
     */
    @Override
    public List<ServerServiceDefinition> getServices() {
        return List.of();
    }

    /** Closes the call with {@code METHOD_NOT_FOUND} without reading the request. */
    private final class Unimplemented implements ServerCallHandler<byte[], byte[]> {

        @Override
        public ServerCall.Listener<byte[]> startCall(ServerCall<byte[], byte[]> call, Metadata headers) {
            String correlationId = correlation.acceptOrGenerate(MetadataMasking.firstValue(headers, correlationKey));
            GrpcCallState state = new GrpcCallState(null, Map.of());
            state.correlationId(correlationId);
            StatusException status = writer.write(new SynapseException(GrpcErrors.METHOD_NOT_FOUND), state);
            // A trailers-only response: the id goes in the trailers, as the edge's echo puts it.
            Metadata trailers = Objects.requireNonNullElseGet(status.getTrailers(), Metadata::new);
            trailers.put(correlationKey, correlationId);
            call.close(status.getStatus(), trailers);
            return new ServerCall.Listener<>() {
            };
        }
    }
}
