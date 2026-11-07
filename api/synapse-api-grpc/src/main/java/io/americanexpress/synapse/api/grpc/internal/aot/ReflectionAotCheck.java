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

import java.util.Arrays;
import java.util.Set;
import java.util.function.BooleanSupplier;

import io.grpc.BindableService;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * Fails startup in AOT mode when {@value #PROPERTY} no longer matches the build. The property decides whether Spring
 * Boot defines its server reflection bean ({@value #REFLECTION_SERVICE}), and under AOT the bean definitions are fixed
 * by the build, so an environment file that changes the value would otherwise do nothing in a native image or AOT run.
 * The value as resolved now is compared with whether the bean exists. It is read as Boot's
 * {@code @ConditionalOnBooleanProperty} matches it: on when unset or {@code true} (in any case), off for any other
 * value, such as {@code false}, {@code on} or {@code maybe}.
 *
 * <p>
 * Boot defines the bean only when {@value #REFLECTION_SERVICE_CLASS} is on the class path and some other
 * {@link BindableService} bean is defined before it, so the check compares only then: without the class, or without a
 * gRPC service of the application, the bean is absent whatever the property says. Boot's health service
 * ({@value #HEALTH_SERVICE}) is not counted; it is defined after the reflection bean's condition ran, and is there with
 * no service of the application only when {@code spring.grpc.server.health.enabled} is set. Outside AOT mode the
 * property decides at startup and nothing is compared.
 */
public final class ReflectionAotCheck implements SmartInitializingSingleton {

    /** Boot's property that turns server reflection on. */
    static final String PROPERTY = "spring.grpc.server.reflection.enabled";

    /** The name of Boot's server reflection bean. */
    static final String REFLECTION_SERVICE = "grpcServerReflectionService";

    /** The class Boot's server reflection bean requires ({@code io.grpc:grpc-services}). */
    static final String REFLECTION_SERVICE_CLASS = "io.grpc.protobuf.services.ProtoReflectionServiceV1";

    /** The name of Boot's gRPC health service bean. */
    static final String HEALTH_SERVICE = "grpcServerHealthService";

    private static final Set<String> BOOT_SERVICES = Set.of(REFLECTION_SERVICE, HEALTH_SERVICE);

    private final Environment environment;

    private final ListableBeanFactory beans;

    private final @Nullable ClassLoader classLoader;

    private final BooleanSupplier aotMode;

    /**
     * Creates the check.
     *
     * @param environment the environment, for the value of {@value #PROPERTY} now
     * @param beans the bean factory, for the services the build defined
     * @param classLoader the application's class loader, to tell whether {@value #REFLECTION_SERVICE_CLASS} is present;
     *     {@code null} for the default one
     * @param aotMode whether the application runs with AOT-generated artifacts
     */
    public ReflectionAotCheck(Environment environment, ListableBeanFactory beans, @Nullable ClassLoader classLoader,
            BooleanSupplier aotMode) {
        this.environment = environment;
        this.beans = beans;
        this.classLoader = classLoader;
        this.aotMode = aotMode;
    }

    /**
     * Compares the property with the build, in AOT mode.
     *
     * @throws SynapseConfigurationException when the property's value differs from the one the build used
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (!aotMode.getAsBoolean() || !ClassUtils.isPresent(REFLECTION_SERVICE_CLASS, classLoader)
                || !hasApplicationService()) {
            return;
        }
        boolean now = "true".equalsIgnoreCase(environment.getProperty(PROPERTY, "true"));
        boolean then = beans.containsBean(REFLECTION_SERVICE);
        if (now != then) {
            throw new SynapseConfigurationException(
                    PROPERTY + " is " + now + " but the AOT build used " + then
                            + ": in a native image or AOT run it is fixed at build time.",
                    "Set " + PROPERTY + " to " + now + " for the AOT build and build again, or set it to " + then
                            + " for this run.");
        }
    }

    private boolean hasApplicationService() {
        return Arrays
                .stream(beans.getBeanNamesForType(BindableService.class, true, false))
                .anyMatch(name -> !BOOT_SERVICES.contains(name));
    }
}
