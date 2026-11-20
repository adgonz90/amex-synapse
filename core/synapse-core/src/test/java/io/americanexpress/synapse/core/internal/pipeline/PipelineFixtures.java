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
package io.americanexpress.synapse.core.internal.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.function.Function;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.tracing.Tracer;
import org.hibernate.validator.HibernateValidator;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;
import io.americanexpress.synapse.core.masking.Masked;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

/** Shared fixtures of the pipeline tests. */
final class PipelineFixtures {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final String FAILURE_LOGGER = "io.americanexpress.synapse.operation";

    static final String PAYLOAD_LOGGER = "io.americanexpress.synapse.payload";

    /** English messages regardless of the machine's default locale. */
    static final Validator VALIDATOR = Validation
            .byProvider(HibernateValidator.class)
            .configure()
            .defaultLocale(Locale.ENGLISH)
            .buildValidatorFactory()
            .getValidator();

    /** A value that must never appear in a log line. */
    static final String SECRET = "4111-SECRET-1111";

    private PipelineFixtures() {
    }

    /**
     * A model with a masked component whose constraint message interpolates the rejected value, as Hibernate
     * Validator does for {@code ${validatedValue}} by default.
     */
    record CardLabel(@Masked @Size(max = 3, message = "${validatedValue} is too long") String number) {
    }

    static ContextBinder binder() {
        return new ContextBinder(Tracer.NOOP, new CorrelationIdPolicy(128), true, 16, 4096);
    }

    /** Sets a Logback logger level and returns the previous one. */
    static @Nullable Level setLevel(String logger, @Nullable Level level) {
        Logger target = (Logger) LoggerFactory.getLogger(logger);
        Level previous = target.getLevel();
        target.setLevel(level);
        return previous;
    }

    /** Counts the lines of the output that contain the text. */
    static long linesContaining(String output, String text) {
        return output.lines().filter(line -> line.contains(text)).count();
    }

    enum BookErrors implements ErrorDetails {

        BOOK_NOT_FOUND("Book {0} was not found", ErrorCategory.NOT_FOUND);

        private final String template;

        private final ErrorCategory category;

        BookErrors(String template, ErrorCategory category) {
            this.template = template;
            this.category = category;
        }

        @Override
        public String code() {
            return name();
        }

        @Override
        public String messageTemplate() {
            return template;
        }

        @Override
        public ErrorCategory category() {
            return category;
        }
    }

    /** A query named {@code get-book} that delegates to a function and counts its calls. */
    static class GetBookOperation implements QueryOperation<String, String> {

        private final Function<String, String> body;

        int calls;

        GetBookOperation(Function<String, String> body) {
            this.body = body;
        }

        @Override
        public String execute(String input) {
            calls++;
            return body.apply(input);
        }
    }

    /** A command named {@code save-book} that delegates to a function and counts its calls. */
    static class SaveBookOperation<I> implements CommandOperation<I, String> {

        private final Function<I, String> body;

        int calls;

        SaveBookOperation(Function<I, String> body) {
            this.body = body;
        }

        @Override
        public String execute(I input) {
            calls++;
            return body.apply(input);
        }
    }
}
