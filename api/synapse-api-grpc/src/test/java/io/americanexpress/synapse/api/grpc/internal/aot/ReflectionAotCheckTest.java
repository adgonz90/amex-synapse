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
package io.americanexpress.synapse.api.grpc.internal.aot;

import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.mock.env.MockEnvironment;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReflectionAotCheckTest {

    private static final String PROPERTY = "spring.grpc.server.reflection.enabled";

    private static final String REFLECTION_SERVICE = "grpcServerReflectionService";

    private static final String HEALTH_SERVICE = "grpcServerHealthService";

    private static BindableService service(String name) {
        return () -> ServerServiceDefinition.builder(name).build();
    }

    /** A bean factory with the named gRPC services as singletons. */
    private static DefaultListableBeanFactory beans(String... services) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (String name : services) {
            beans.registerSingleton(name, service(name));
        }
        return beans;
    }

    private static MockEnvironment environment(@Nullable String reflection) {
        MockEnvironment environment = new MockEnvironment();
        if (reflection != null) {
            environment.setProperty(PROPERTY, reflection);
        }
        return environment;
    }

    private static ReflectionAotCheck check(@Nullable String reflection, boolean aotMode, String... services) {
        return new ReflectionAotCheck(environment(reflection), beans(services),
                ReflectionAotCheckTest.class.getClassLoader(),
                () -> aotMode);
    }

    @Test
    void failsWhenTheRuntimeValueDiffersInAotMode() {
        assertThatThrownBy(() -> check("true", true, "catalog").afterSingletonsInstantiated())
                .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                    assertThat(failure.description())
                            .isEqualTo("spring.grpc.server.reflection.enabled is true but the AOT build used false:"
                                    + " in a native image or AOT run it is fixed at build time.");
                    assertThat(failure.action()).contains(PROPERTY);
                });
        assertThatThrownBy(() -> check("false", true, "catalog", REFLECTION_SERVICE).afterSingletonsInstantiated())
                .isInstanceOf(SynapseConfigurationException.class)
                .hasMessage("spring.grpc.server.reflection.enabled is false but the AOT build used true:"
                        + " in a native image or AOT run it is fixed at build time.");
        // Unset is on, as for Boot's condition (Synapse's own default of false is a property source).
        assertThatThrownBy(() -> check(null, true, "catalog").afterSingletonsInstantiated())
                .isInstanceOf(SynapseConfigurationException.class)
                .hasMessageStartingWith("spring.grpc.server.reflection.enabled is true but the AOT build used false");
        // Boot's health service is registered after the reflection service, so it never made reflection exist:
        // with another service it still counts.
        assertThatThrownBy(() -> check("true", true, "catalog", HEALTH_SERVICE).afterSingletonsInstantiated())
                .isInstanceOf(SynapseConfigurationException.class);
    }

    @Test
    void passesWhenTheyAgreeOrOutsideAot() {
        // They agree.
        assertThatCode(() -> check("true", true, "catalog", REFLECTION_SERVICE).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> check("false", true, "catalog").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> check(null, true, "catalog", REFLECTION_SERVICE).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        // Outside AOT the property decides at startup, so there is nothing to compare.
        assertThatCode(() -> check("true", false, "catalog").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> check("false", false, "catalog", REFLECTION_SERVICE).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        // No service of the application: Boot's reflection bean is @ConditionalOnBean(BindableService), so it is
        // legitimately absent with the property on.
        assertThatCode(() -> check("true", true).afterSingletonsInstantiated()).doesNotThrowAnyException();
        // Only Boot's health service (spring.grpc.server.health.enabled=true): it is defined after the reflection
        // service's condition ran, so it doesn't count as one.
        assertThatCode(() -> check("true", true, HEALTH_SERVICE).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    @Test
    void passesWithoutTheReflectionServiceClass() throws Exception {
        // Boot's reflection bean is @ConditionalOnClass(ProtoReflectionServiceV1): without grpc-services it is
        // absent whatever the property says.
        try (FilteredClassLoader classLoader = new FilteredClassLoader(ProtoReflectionServiceV1.class)) {
            ReflectionAotCheck check = new ReflectionAotCheck(environment("true"), beans("catalog"), classLoader,
                    () -> true);

            assertThatCode(check::afterSingletonsInstantiated).doesNotThrowAnyException();
        }
    }

    @Test
    void readsThePropertyAsBootsConditionMatchesIt() {
        // Boot's @ConditionalOnBooleanProperty matches only "true", ignoring case: "on" defined no reflection bean.
        assertThatCode(() -> check("on", true, "catalog").afterSingletonsInstantiated()).doesNotThrowAnyException();
        assertThatCode(() -> check("TRUE", true, "catalog", REFLECTION_SERVICE).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        // A value that is no boolean doesn't match either, and is no conversion failure.
        assertThatCode(() -> check("maybe", true, "catalog").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> check("maybe", true, "catalog", REFLECTION_SERVICE).afterSingletonsInstantiated())
                .isInstanceOf(SynapseConfigurationException.class)
                .hasMessageStartingWith("spring.grpc.server.reflection.enabled is false but the AOT build used true");
    }
}
