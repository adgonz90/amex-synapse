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
package io.americanexpress.synapse.api.rest.internal.binding;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.rest.internal.paging.SortKeys;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;

/**
 * How a resolved route that binds {@code PageQuery} reads it from the paging parameters.
 *
 * @param component the input component of type {@code PageQuery}, or {@code null} when {@code PageQuery} is the whole
 *        input
 * @param sortKeys the route's sort allow-list from {@code .sortable(...)}, or {@code null} when the route takes no sort
 *        parameter
 */
public record PagingRules(@Nullable ResolvedComponent component, @Nullable SortKeys sortKeys) {
}
