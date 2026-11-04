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

import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;

/**
 * A resolved route's conditional request rules (RFC 9110): the output component its {@code ETag} is made from, whether
 * {@code If-Match} is required, and the input sources bound from {@code If-Match} and {@code If-None-Match}.
 *
 * @param etag the output component written as the strong {@code ETag}, or {@code null} without {@code .etag}
 * @param requireIfMatch whether the route declares {@code .requireIfMatch()}
 * @param ifMatch the input source bound from {@code If-Match}, declared or by convention, or {@code null}
 * @param ifNoneMatch the input source bound from {@code If-None-Match}, declared or by convention, or {@code null}
 */
public record ConditionalRules(@Nullable ResolvedComponent etag, boolean requireIfMatch,
        InputSource.@Nullable Header ifMatch, InputSource.@Nullable Header ifNoneMatch) {

    /** The rules of a route with no conditional declarations and no {@code If-Match}/{@code If-None-Match} source. */
    public static final ConditionalRules NONE = new ConditionalRules(null, false, null, null);
}
