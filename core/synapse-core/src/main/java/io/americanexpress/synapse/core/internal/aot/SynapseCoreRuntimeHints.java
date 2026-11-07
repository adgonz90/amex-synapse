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
package io.americanexpress.synapse.core.internal.aot;

import java.lang.invoke.SerializedLambda;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.internal.context.ContextValueAccessor;

/**
 * Registers what a native image needs for the core: the service file through which Micrometer's context propagation
 * finds the {@link ContextValueAccessor}, and the accessor's constructor, which the service loader calls
 * reflectively; the serialized form of a component reference, which Synapse reads to find the component it names;
 * and the modules' configuration files ({@code META-INF/synapse/config.yaml}, {@code config-<env>.yaml} and their
 * {@code .properties} forms), which the environment post-processor reads at startup; and the binding hints of the
 * {@link VersionCondition} cases, which the operation types' hints don't reach (Spring's binding registrar doesn't
 * follow a sealed interface's permits). Listed in
 * {@code META-INF/spring/aot.factories}.
 */
public final class SynapseCoreRuntimeHints implements RuntimeHintsRegistrar {

    /** The service file that registers the {@link ContextValueAccessor} with Micrometer's context propagation. */
    static final String THREAD_LOCAL_ACCESSOR_SERVICES = "META-INF/services/io.micrometer.context.ThreadLocalAccessor";

    /** The modules' YAML configuration files, for every environment. */
    static final String MODULE_CONFIG_YAML = "META-INF/synapse/config*.yaml";

    /** The modules' properties configuration files, for every environment. */
    static final String MODULE_CONFIG_PROPERTIES = "META-INF/synapse/config*.properties";

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        hints.resources().registerPattern(THREAD_LOCAL_ACCESSOR_SERVICES);
        hints.resources().registerPattern(MODULE_CONFIG_YAML);
        hints.resources().registerPattern(MODULE_CONFIG_PROPERTIES);
        hints.reflection().registerType(ContextValueAccessor.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(SerializedLambda.class, MemberCategory.INVOKE_DECLARED_METHODS);
        // Models declare the sealed VersionCondition, and the binding registrar doesn't follow its permits.
        new BindingReflectionHintsRegistrar()
                .registerReflectionHints(hints.reflection(), VersionCondition.AnyVersion.class,
                        VersionCondition.Version.class);
    }
}
