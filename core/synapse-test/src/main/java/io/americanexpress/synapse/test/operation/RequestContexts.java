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
package io.americanexpress.synapse.test.operation;

import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;
import io.americanexpress.synapse.core.internal.context.InboundContext;

/**
 * Opens a request context around test code, as an inbound edge does. The context is always closed afterwards, also
 * when the code throws.
 *
 * <pre>{@code
 * RequestContexts.with().correlationId("c-9").run(() -> SynapseOperations.invoke(operation, input));
 * }</pre>
 */
public final class RequestContexts {

    // The application's default limits: synapse.context.inbound.max-entries=16 and max-size=4KB, and correlation
    // ids up to 128 characters.
    private static final int MAX_CORRELATION_LENGTH = 128;

    private static final int MAX_ENTRIES = 16;

    private static final long MAX_BYTES = 4096;

    private RequestContexts() {
    }

    /**
     * Starts describing a request context.
     *
     * @return a builder
     */
    public static Builder with() {
        return new Builder();
    }

    /**
     * Describes and opens a request context.
     */
    public static final class Builder {

        private @Nullable String correlationId;

        private @Nullable Principal principal;

        private @Nullable Locale locale;

        private @Nullable Instant deadline;

        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the correlation id. Default: a generated one.
         *
         * @param correlationId the id
         * @return this builder
         */
        public Builder correlationId(String correlationId) {
            this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
            return this;
        }

        /**
         * Sets the authenticated principal.
         *
         * @param principal the principal
         * @return this builder
         */
        public Builder principal(Principal principal) {
            this.principal = Objects.requireNonNull(principal, "principal");
            return this;
        }

        /**
         * Sets the caller's locale.
         *
         * @param locale the locale
         * @return this builder
         */
        public Builder locale(Locale locale) {
            this.locale = Objects.requireNonNull(locale, "locale");
            return this;
        }

        /**
         * Sets the instant by which the request must finish.
         *
         * @param deadline the deadline
         * @return this builder
         */
        public Builder deadline(Instant deadline) {
            this.deadline = Objects.requireNonNull(deadline, "deadline");
            return this;
        }

        /**
         * Adds a context attribute.
         *
         * @param name the attribute name
         * @param value the attribute value
         * @return this builder
         */
        public Builder attribute(String name, String value) {
            attributes.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Runs the code inside the context.
         *
         * @param code the code
         */
        public void run(Runnable code) {
            Objects.requireNonNull(code, "code");
            call(() -> {
                code.run();
                return null;
            });
        }

        /**
         * Runs the code inside the context and returns its result.
         *
         * @param <T> the result type
         * @param code the code
         * @return the code's result
         */
        public <T> T call(Supplier<T> code) {
            Objects.requireNonNull(code, "code");
            ContextBinder binder = new ContextBinder(Tracer.NOOP, new CorrelationIdPolicy(MAX_CORRELATION_LENGTH),
                    true, MAX_ENTRIES, MAX_BYTES);
            InboundContext inbound = new InboundContext(correlationId,
                    Collections.unmodifiableMap(new LinkedHashMap<>(attributes)), principal, locale,
                    deadline);
            ContextScope scope = binder.open(inbound);
            try {
                return code.get();
            } finally {
                scope.close();
            }
        }
    }
}
