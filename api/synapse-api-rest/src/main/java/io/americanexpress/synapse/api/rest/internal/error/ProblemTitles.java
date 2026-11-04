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
package io.americanexpress.synapse.api.rest.internal.error;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;

import io.americanexpress.synapse.core.error.ErrorCategory;

/**
 * Resolves the problem title of an error category. A {@link MessageSource} entry keyed
 * {@code synapse.category.<CATEGORY>} overrides the default.
 */
public final class ProblemTitles {

    private static final Map<ErrorCategory, String> DEFAULTS = new EnumMap<>(ErrorCategory.class);

    static {
        DEFAULTS.put(ErrorCategory.INVALID_INPUT, "Invalid Input");
        DEFAULTS.put(ErrorCategory.UNAUTHENTICATED, "Unauthenticated");
        DEFAULTS.put(ErrorCategory.FORBIDDEN, "Forbidden");
        DEFAULTS.put(ErrorCategory.NOT_FOUND, "Not Found");
        DEFAULTS.put(ErrorCategory.CONFLICT, "Conflict");
        DEFAULTS.put(ErrorCategory.PRECONDITION_FAILED, "Precondition Failed");
        DEFAULTS.put(ErrorCategory.RATE_LIMITED, "Rate Limited");
        DEFAULTS.put(ErrorCategory.UNAVAILABLE, "Unavailable");
        DEFAULTS.put(ErrorCategory.TIMEOUT, "Timeout");
        DEFAULTS.put(ErrorCategory.INTERNAL, "Internal Error");
    }

    private final MessageSource messages;

    /**
     * Creates the resolver.
     *
     * @param messages the message source whose entries override the default titles
     */
    public ProblemTitles(MessageSource messages) {
        this.messages = messages;
    }

    /**
     * Returns the title of a category.
     *
     * @param category the category
     * @param locale the caller's locale, or {@code null} for {@link Locale#ROOT}
     * @return the {@code MessageSource} entry for the category, or the default title
     */
    public String title(ErrorCategory category, @Nullable Locale locale) {
        String fallback = DEFAULTS.getOrDefault(category, category.name());
        String title = messages
                .getMessage("synapse.category." + category.name(), null, fallback,
                        locale == null ? Locale.ROOT : locale);
        return title == null ? fallback : title;
    }
}
