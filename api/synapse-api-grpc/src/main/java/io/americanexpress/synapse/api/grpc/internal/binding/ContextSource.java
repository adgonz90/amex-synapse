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
package io.americanexpress.synapse.api.grpc.internal.binding;

import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;

/**
 * A {@code .context(component, value)} declaration: the input component is set from the request context instead of a
 * request field.
 *
 * @param component the input component
 * @param value reads the value from the request context
 */
public record ContextSource(ResolvedComponent component, Function<RequestContext, ? extends @Nullable Object> value) {
}
