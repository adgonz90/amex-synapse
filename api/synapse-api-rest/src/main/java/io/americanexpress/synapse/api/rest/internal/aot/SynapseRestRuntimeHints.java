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
package io.americanexpress.synapse.api.rest.internal.aot;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.boot.context.properties.bind.BindableRuntimeHintsRegistrar;

import io.americanexpress.synapse.api.rest.internal.edge.ManagementPaths;
import io.americanexpress.synapse.api.rest.internal.paging.PageBody;

/**
 * Registers what a native image needs for the REST plugin's own types: the health groups that {@link ManagementPaths}
 * binds from {@code management.endpoint.health.group} with Spring Boot's {@code Binder}, which constructs them
 * reflectively, and the {@link PageBody} that Jackson writes for a route whose operation returns a {@code PageResult}.
 * The plugin's other types, such as the request wrappers of the edge and {@code RestErrors}, are used
 * directly and need no hints. The types of operation inputs and outputs,
 * which the binder and the response writer read with Jackson and record reflection, are registered by the core's
 * {@code OperationTypesAotProcessor}; the method references in REST bindings by {@link RestBindingsAotProcessor}.
 * Listed in {@code META-INF/spring/aot.factories}.
 */
public final class SynapseRestRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        BindableRuntimeHintsRegistrar.forTypes(ManagementPaths.HealthGroup.class).registerHints(hints);
        new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(), PageBody.class);
    }
}
