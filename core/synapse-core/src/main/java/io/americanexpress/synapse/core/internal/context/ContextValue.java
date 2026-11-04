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
package io.americanexpress.synapse.core.internal.context;

import java.security.Principal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.context.RequestContext;

/**
 * The Synapse context value held by the current thread and propagated by {@link ContextValueAccessor}.
 *
 * @param correlationId the validated correlation id
 * @param callerOrNull the verified caller, if any
 * @param localeOrNull the locale, if known
 * @param deadlineOrNull the absolute deadline, if any
 * @param attributes the accepted attributes, unmodifiable, in insertion order
 */
public record ContextValue(String correlationId, @Nullable Principal callerOrNull, @Nullable Locale localeOrNull,
        @Nullable Instant deadlineOrNull, Map<String, String> attributes) implements RequestContext {

    private static final ThreadLocal<@Nullable ContextValue> CURRENT = new ThreadLocal<>();

    /**
     * Returns the value bound to the current thread.
     *
     * @return the value, or {@code null} when none is active
     */
    public static @Nullable ContextValue currentOrNull() {
        return CURRENT.get();
    }

    static void bind(@Nullable ContextValue value) {
        if (value == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(value);
        }
    }

    @Override
    public Optional<Principal> principal() {
        return Optional.ofNullable(callerOrNull);
    }

    @Override
    public Optional<Locale> locale() {
        return Optional.ofNullable(localeOrNull);
    }

    @Override
    public Optional<Instant> deadline() {
        return Optional.ofNullable(deadlineOrNull);
    }

    /**
     * Describes the context without the caller or the attribute values, which may be personal or confidential, so
     * that logging {@code RequestContext.current()} does not leak them.
     *
     * @return the correlation id, locale, deadline, whether there is a caller, and the attribute names
     */
    @Override
    public String toString() {
        return "ContextValue[correlationId=" + correlationId + ", locale=" + localeOrNull + ", deadline="
                + deadlineOrNull + ", principal=" + (callerOrNull != null ? "present" : "absent") + ", attributes="
                + attributes.keySet() + "]";
    }
}
