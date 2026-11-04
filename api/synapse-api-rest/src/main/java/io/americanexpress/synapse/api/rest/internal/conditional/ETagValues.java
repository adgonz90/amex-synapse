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
import org.springframework.core.convert.ConversionService;
import org.springframework.http.ETag;
import org.springframework.util.ReflectionUtils;

import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;

/**
 * The strong {@code ETag} of a route with {@code .etag}: its output component, formatted by the conversion service as
 * output header values are. A {@code null} component means no tag. A formatted value with a character an entity tag
 * can't carry (a double quote, a space, a control character, DEL, or anything above {@code U+00FF}) is an operation
 * bug: it is an {@link IllegalStateException} (500), never a malformed header.
 */
public final class ETagValues {

    private final ConversionService conversionService;

    /**
     * Creates the values.
     *
     * @param conversionService formats the component (in an application, Spring MVC's conversion service bean)
     */
    public ETagValues(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    /**
     * Returns the strong entity tag of the output.
     *
     * @param route the resolved route
     * @param output the operation's output value (an {@code Optional} already unwrapped)
     * @return the tag, or {@code null} when the route has no {@code .etag} or the component is {@code null}
     * @throws IllegalStateException when the formatted value is empty or has a character outside RFC 9110 {@code etagc}; the
     *         message never repeats the value
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
        String text = conversionService.convert(value, String.class);
        if (text == null) {
            return null;
        }
        if (text.isEmpty()) {
            // Spring's ETag.compare never matches an empty tag, so it could never satisfy a conditional request.
            throw new IllegalStateException("The ETag value computed from the operation's output is empty");
        }
        for (int i = 0; i < text.length(); i++) {
            if (!EntityTags.isEtagc(text.charAt(i))) {
                throw new IllegalStateException("The ETag value computed from the operation's output has a character "
                        + "an entity tag can't carry (a double quote, a space, a control character, DEL or a "
                        + "character above U+00FF)");
            }
        }
        return new ETag(text, false);
    }

    /**
     * Formats a strong tag as an {@code ETag} header value.
     *
     * @param tag the tag
     * @return the quoted tag
     */
    public static String header(ETag tag) {
        return "\"" + tag.tag() + "\"";
    }
}
