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

import java.util.List;

import io.grpc.BindableService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.grpc.server.GrpcServerFactory;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.autoconfigure.SynapseGrpcAutoConfiguration;
import io.americanexpress.synapse.api.grpc.autoconfigure.SynapseGrpcErrorAutoConfiguration;
import io.americanexpress.synapse.api.grpc.internal.service.GrpcBindingsAccess;
import io.americanexpress.synapse.api.grpc.internal.service.ServiceCompiler;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.test.grpc.fixture.proto.InventoryServiceGrpc;
import io.americanexpress.synapse.test.slice.BoundOperationMocksCheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SynapseGrpcTestAutoConfigurationTest {

    private static final BoundOperationMocksCheck USER_CHECK = new BoundOperationMocksCheck(Object.class, "user");

    private static final String UNBOUND_ACTION = "Bind it with .unary(...), or add .bindAllByName() and an operation"
            + " named get-stock.";

    // The plugin's auto-configuration is listed first: the slice's must still be ordered before it.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseGrpcAutoConfiguration.class, SynapseCoreAutoConfiguration.class,
                            SynapseContextAutoConfiguration.class, SynapseGrpcErrorAutoConfiguration.class,
                            SynapseGrpcTestAutoConfiguration.class))
            .withBean(ErrorMessages.class, () -> new ErrorMessages(new StaticMessageSource()));

    /** Compiles bindings whose methods have no operation, and returns the action of the failure. */
    private static String unboundAction(ServiceCompiler compiler) {
        GrpcBindings bindings = GrpcBindings
                .service(InventoryServiceGrpc.getServiceDescriptor())
                .bindAllByName()
                .build();
        return catchThrowableOfType(SynapseConfigurationException.class,
                () -> compiler.compile(GrpcBindingsAccess.declaration(bindings), List.of())).action();
    }

    @Test
    void grpcSliceRegistersItsCheck() {
        runner.run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(BoundOperationMocksCheck.class)
                    .hasBean("synapseGrpcBoundOperationMocksCheck")
                    .hasSingleBean(ServiceCompiler.class);
            assertThat(unboundAction(context.getBean(ServiceCompiler.class)))
                    .isEqualTo(UNBOUND_ACTION + " In @SynapseGrpcTest, declare a @MockitoBean for it.");
        });
    }

    @Test
    void aUserBeanWithTheSameNameReplacesTheCheck() {
        runner
                .withUserConfiguration(UserCheckConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(BoundOperationMocksCheck.class)
                        .hasBean("synapseGrpcBoundOperationMocksCheck")
                        .getBean("synapseGrpcBoundOperationMocksCheck")
                        .isSameAs(USER_CHECK));
    }

    @Test
    void backsOffToAUserCompiler() {
        runner.withUserConfiguration(UserCompilerConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(ServiceCompiler.class).hasBean("userCompiler");
            assertThat(unboundAction(context.getBean(ServiceCompiler.class))).isEqualTo(UNBOUND_ACTION);
        });
    }

    @Test
    void isAbsentWhenTheGrpcServerIsDisabled() {
        runner
                .withPropertyValues("spring.grpc.server.enabled=false")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(BoundOperationMocksCheck.class)
                        .doesNotHaveBean(ServiceCompiler.class));
    }

    @Test
    void isAbsentWithoutGrpc() {
        runner
                .withClassLoader(new FilteredClassLoader(GrpcServerFactory.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(BoundOperationMocksCheck.class)
                        .doesNotHaveBean(ServiceCompiler.class));
        runner
                .withClassLoader(new FilteredClassLoader(BindableService.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(BoundOperationMocksCheck.class)
                        .doesNotHaveBean(ServiceCompiler.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class UserCheckConfiguration {

        @Bean
        static BoundOperationMocksCheck synapseGrpcBoundOperationMocksCheck() {
            return USER_CHECK;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserCompilerConfiguration {

        @Bean
        ServiceCompiler userCompiler(PageCursors cursors, SynapsePagingProperties paging) {
            return new ServiceCompiler(cursors, paging);
        }
    }
}
