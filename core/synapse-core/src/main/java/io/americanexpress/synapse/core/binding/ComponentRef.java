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
package io.americanexpress.synapse.core.binding;

import java.io.Serializable;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.Incubating;

/**
 * A type-checked name for a component of a record, written as a method reference to its accessor, for example
 * {@code GetBookRequest::id}. It must be a method reference to a record component: lambdas and other methods are
 * rejected at startup.
 *
 * @param <T> the record type
 * @param <P> the component type
 */
@Incubating
@FunctionalInterface
public interface ComponentRef<T, P extends @Nullable Object> extends Function<T, P>, Serializable {
}
