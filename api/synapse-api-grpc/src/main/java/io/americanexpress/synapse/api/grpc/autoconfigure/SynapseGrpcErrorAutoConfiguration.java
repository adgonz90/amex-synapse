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
package io.americanexpress.synapse.api.grpc.autoconfigure;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.grpc.BindableService;
import io.grpc.Metadata;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.ServerBuilderCustomizer;
import org.springframework.util.StringUtils;

import io.americanexpress.synapse.api.grpc.internal.error.FallbackHandlerRegistry;
import io.americanexpress.synapse.api.grpc.internal.error.GrpcErrorStatuses;
import io.americanexpress.synapse.api.grpc.internal.error.GrpcStatusWriter;
import io.americanexpress.synapse.api.grpc.internal.error.SynapseGrpcExceptionHandler;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCorrelationProperties;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;

/**
 * Auto-configuration for the gRPC error handling: how Synapse errors become gRPC statuses and error details, and how
 * calls to unknown methods are answered. It is
 * separate from {@link SynapseGrpcAutoConfiguration}, as the REST plugin's error handling is, and has the same
 * conditions. It comes after Boot's gRPC security auto-configuration: Spring gRPC asks exception handlers in order,
 * and Spring Security's handler is unordered, so between it and the Synapse handler (ordered last) the one defined
 * first answers first.
 */
@AutoConfiguration(after = {SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class,
        SynapseOperationAutoConfiguration.class}, afterName = SynapseGrpcErrorAutoConfiguration.GRPC_SECURITY)
@ConditionalOnClass({GrpcServerFactory.class, BindableService.class})
@ConditionalOnBooleanProperty(name = "spring.grpc.server.enabled", matchIfMissing = true)
@EnableConfigurationProperties(SynapseGrpcProperties.class)
public final class SynapseGrpcErrorAutoConfiguration {

    static final String GRPC_SECURITY = "org.springframework.boot.grpc.server.autoconfigure.security"
            + ".GrpcServerSecurityAutoConfiguration";

    private static final String DEFAULT_DOMAIN = "synapse";

    /**
     * Decides the gRPC status of each error from {@code synapse.api.grpc.errors.status} and the per-operation
     * overrides.
     *
     * @param properties the gRPC properties
     * @return the status table
     */
    @Bean
    @ConditionalOnMissingBean
    GrpcErrorStatuses grpcErrorStatuses(SynapseGrpcProperties properties) {
        Map<String, Map<String, Status.Code>> perOperation = new HashMap<>();
        properties.operations().forEach((name, settings) -> perOperation.put(name, settings.errors().status()));
        return new GrpcErrorStatuses(properties.errors().status(), perOperation);
    }

    /**
     * Writes errors as gRPC statuses with error details. The {@code ErrorInfo} domain is
     * {@code spring.application.name}, or {@value #DEFAULT_DOMAIN} when it is not set.
     *
     * @param statuses the status table
     * @param messages the error messages
     * @param errorProperties the error settings
     * @param environment the environment, for the application name
     * @return the writer
     */
    @Bean
    @ConditionalOnMissingBean
    GrpcStatusWriter grpcStatusWriter(GrpcErrorStatuses statuses, ErrorMessages messages,
            SynapseErrorProperties errorProperties, Environment environment) {
        String name = environment.getProperty("spring.application.name");
        String domain = StringUtils.hasText(name) ? name.strip() : DEFAULT_DOMAIN;
        return new GrpcStatusWriter(statuses, messages, errorProperties, domain);
    }

    /**
     * Turns failures of gRPC calls into statuses; Boot's gRPC server asks it after every other exception handler.
     *
     * @param writer the status writer
     * @return the exception handler
     */
    @Bean
    @ConditionalOnMissingBean
    SynapseGrpcExceptionHandler synapseGrpcExceptionHandler(GrpcStatusWriter writer) {
        return new SynapseGrpcExceptionHandler(writer);
    }

    /**
     * Answers calls to unknown methods in the Synapse error shape: {@code UNIMPLEMENTED} with the reason
     * {@code METHOD_NOT_FOUND} and the correlation id. Boot applies it to the Netty and in-process servers it builds;
     * its in-process test transport applies no customizers, so a test slice answers grpc-java's own
     * {@code UNIMPLEMENTED}. Define a bean with the same name to replace it.
     *
     * @param <T> the server builder type
     * @param writer the status writer
     * @param policy accepts or replaces the caller's correlation id
     * @param correlation the correlation properties: the metadata key is {@code synapse.correlation.header} in lower
     *     case
     * @return the customizer that sets the server's fallback handler registry
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseGrpcFallbackHandlers")
    <T extends ServerBuilder<T>> ServerBuilderCustomizer<T> synapseGrpcFallbackHandlers(GrpcStatusWriter writer,
            CorrelationIdPolicy policy, SynapseCorrelationProperties correlation) {
        FallbackHandlerRegistry registry = new FallbackHandlerRegistry(writer, policy, Metadata.Key
                .of(correlation.header().toLowerCase(Locale.ROOT), Metadata.ASCII_STRING_MARSHALLER));
        return builder -> builder.fallbackHandlerRegistry(registry);
    }
}
