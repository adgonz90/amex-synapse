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
package io.americanexpress.synapse.test.grpc;

import io.grpc.BindableService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.server.GrpcServerFactory;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.autoconfigure.SynapseGrpcAutoConfiguration;
import io.americanexpress.synapse.api.grpc.internal.service.ServiceCompiler;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.test.slice.BoundOperationMocksCheck;

/**
 * Auto-configuration of {@link SynapseGrpcTest}: the check that each operation the bindings use has a test double,
 * and the service compiler whose failure for an unbound method says to declare one. Active under the gRPC plugin's
 * conditions, and ordered before the plugin so that the plugin's own compiler backs off. It is imported by the slice
 * only, never by an application.
 */
@AutoConfiguration(before = SynapseGrpcAutoConfiguration.class)
@ConditionalOnClass({GrpcServerFactory.class, BindableService.class})
@ConditionalOnBooleanProperty(name = "spring.grpc.server.enabled", matchIfMissing = true)
public final class SynapseGrpcTestAutoConfiguration {

    /** Appended to the action of an unbound method's failure. */
    static final String UNBOUND_HINT = "In @SynapseGrpcTest, declare a @MockitoBean for it.";

    /**
     * The check that each operation the bindings use has a bean. It is {@code static} because it is a bean factory
     * post-processor. Define a bean named {@code synapseGrpcBoundOperationMocksCheck} to replace it; the name, not the type, backs
     * it off, so that the REST slice's check does not hide this one.
     *
     * @return the check
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseGrpcBoundOperationMocksCheck")
    static BoundOperationMocksCheck synapseGrpcBoundOperationMocksCheck() {
        return new BoundOperationMocksCheck(GrpcBindings.class, "@SynapseGrpcTest");
    }

    /**
     * The gRPC plugin's service compiler, with the slice's hint for a method that {@code .bindAllByName()} leaves
     * unbound: in the slice, that is an operation the test has no test double for. It has the plugin's bean name, and
     * the plugin's own compiler backs off to it.
     *
     * @param cursors core's page cursors
     * @param paging core's paging properties
     * @return the compiler
     */
    @Bean
    @ConditionalOnMissingBean
    ServiceCompiler synapseGrpcServiceCompiler(PageCursors cursors, SynapsePagingProperties paging) {
        return new ServiceCompiler(cursors, paging, UNBOUND_HINT);
    }
}
