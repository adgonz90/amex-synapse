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

import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitch.Mismatch;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static ReflectionAotCheck check(@Nullable String reflection, String... services) {
        return new ReflectionAotCheck(environment(reflection), beans(services),
                ReflectionAotCheckTest.class.getClassLoader());
    }

    private static Mismatch mismatch(String now, String build) {
        return new Mismatch(PROPERTY, now, build);
    }

    @Test
    void reportsTheMismatchWhenTheRuntimeValueDiffers() {
        assertThat(check("true", "catalog").mismatch()).isEqualTo(mismatch("true", "false"));
        assertThat(check("false", "catalog", REFLECTION_SERVICE).mismatch()).isEqualTo(mismatch("false", "true"));
        // Unset is on, as for Boot's condition (Synapse's own default of false is a property source).
        assertThat(check(null, "catalog").mismatch()).isEqualTo(mismatch("true", "false"));
        // Boot's health service is registered after the reflection service, so it never made reflection exist:
        // with another service it still counts.
        assertThat(check("true", "catalog", HEALTH_SERVICE).mismatch()).isEqualTo(mismatch("true", "false"));
    }

    @Test
    void reportsNothingWhenTheyAgree() {
        assertThat(check("true", "catalog", REFLECTION_SERVICE).mismatch()).isNull();
        assertThat(check("false", "catalog").mismatch()).isNull();
        assertThat(check(null, "catalog", REFLECTION_SERVICE).mismatch()).isNull();
        // No service of the application: Boot's reflection bean is @ConditionalOnBean(BindableService), so it is
        // legitimately absent with the property on.
        assertThat(check("true").mismatch()).isNull();
        // Only Boot's health service (spring.grpc.server.health.enabled=true): it is defined after the reflection
        // service's condition ran, so it doesn't count as one.
        assertThat(check("true", HEALTH_SERVICE).mismatch()).isNull();
    }

    @Test
    void reportsNothingWithoutTheReflectionServiceClass() throws Exception {
        // Boot's reflection bean is @ConditionalOnClass(ProtoReflectionServiceV1): without grpc-services it is
        // absent whatever the property says.
        try (FilteredClassLoader classLoader = new FilteredClassLoader(ProtoReflectionServiceV1.class)) {
            ReflectionAotCheck check = new ReflectionAotCheck(environment("true"), beans("catalog"), classLoader);

            assertThat(check.mismatch()).isNull();
        }
    }

    @Test
    void readsThePropertyAsBootsConditionMatchesIt() {
        // Boot's @ConditionalOnBooleanProperty matches only "true", ignoring case: "on" defined no reflection bean.
        assertThat(check("on", "catalog").mismatch()).isNull();
        assertThat(check("TRUE", "catalog", REFLECTION_SERVICE).mismatch()).isNull();
        // A value that is no boolean doesn't match either, and is no conversion failure.
        assertThat(check("maybe", "catalog").mismatch()).isNull();
        assertThat(check("maybe", "catalog", REFLECTION_SERVICE).mismatch()).isEqualTo(mismatch("false", "true"));
    }
}
