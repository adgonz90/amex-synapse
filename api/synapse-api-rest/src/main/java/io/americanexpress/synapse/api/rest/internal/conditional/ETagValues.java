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
package io.americanexpress.synapse.api.rest.internal.conditional;

import java.lang.reflect.Method;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ETag;
import org.springframework.util.ReflectionUtils;

import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.internal.conditional.EntityTags;
import io.americanexpress.synapse.core.internal.conditional.VersionTokens;

/**
 * The strong {@code ETag} of a route with {@code .etag}: its output component, formatted by core's
 * {@link VersionTokens}, the codec every protocol shares (plain decimal for an integral number, ISO-8601 for a
 * {@code java.time} value, a string as it is), so the token never depends on Spring MVC's formatters, its
 * {@code spring.mvc.format.*} settings or the locale, and a gRPC response carries the same one. A {@code null}
 * component means no tag. A string that is empty or has a character an entity tag can't carry (a double quote, a space,
 * a control character, DEL, or anything above {@code U+00FF}) is an operation bug: it is an
 * {@link IllegalStateException} (500), never a malformed header.
 */
public final class ETagValues {

    /**
     * Returns the strong entity tag of the output.
     *
     * @param route the resolved route
     * @param output the operation's output value (an {@code Optional} already unwrapped)
     * @return the tag, or {@code null} when the route has no {@code .etag} or the component is {@code null}
     * @throws IllegalStateException when the value is not a valid version token (an empty string, or one with a
     *         character outside RFC 9110 {@code etagc}); the message never repeats the value
     */
    public @Nullable ETag of(RouteDefinition route, Object output) {
        ResolvedComponent component = route.conditional().etag();
        if (component == null) {
            return null;
        }
        Method accessor = component.component().getAccessor();
        ReflectionUtils.makeAccessible(accessor);
        Object value = ReflectionUtils.invokeMethod(accessor, output);
        if (value == null) {
            return null;
        }
        try {
            return new ETag(VersionTokens.format(value), false);
        } catch (IllegalArgumentException e) {
            // Spring's ETag.compare never matches an empty tag, so it could never satisfy a conditional request.
            throw new IllegalStateException(
                    "The ETag value computed from the operation's output is not a valid entity tag: " + e.getMessage(),
                    e);
        }
    }

    /**
     * Formats a strong tag as an {@code ETag} header value, with the entity tag grammar every protocol shares
     * ({@link EntityTags#strong}), so the token a REST client reads is the one a gRPC client sends, and the reverse.
     *
     * @param tag the tag, as {@link #of} returns it
     * @return the quoted tag
     * @throws IllegalArgumentException when the tag is empty or has a character outside RFC 9110 {@code etagc}, which
     *         {@link #of} never returns
     */
    public static String header(ETag tag) {
        return EntityTags.strong(tag.tag());
    }
}
