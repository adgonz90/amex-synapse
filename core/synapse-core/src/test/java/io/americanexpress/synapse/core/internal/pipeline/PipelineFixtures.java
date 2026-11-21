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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import io.micrometer.tracing.Tracer;
import org.hibernate.validator.HibernateValidator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
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

    /**
     * Captures, in {@link #PATTERN}, every event that reaches the root logger during each test, whatever appenders and
     * pattern the active Logback configuration has. Another test in the same JVM (a {@code @SpringBootTest}, for
     * example) can replace Logback's unconfigured default with Boot's console pattern, which prints neither the
     * key-value pairs nor the unpadded thread name. Fails fast if a logger the tests read ({@link #READ}) or one of its
     * ancestors is not additive: its lines would never reach the root logger, and a test that asserts the absence of a
     * line would pass without seeing anything.
     */
    static final class LogCapture implements BeforeEachCallback, AfterEachCallback {

        /** As Logback's unconfigured default ({@code TTLLLayout}): thread, level, full logger name and key-value pairs. */
        static final String PATTERN = "%d{HH:mm:ss.SSS} [%thread] %level %logger -%kvp- %msg%n";

        /** The loggers whose lines the pipeline tests read. */
        static final List<String> READ = List.of(FAILURE_LOGGER, PAYLOAD_LOGGER, OperationInterceptor.class.getName());

        private final ByteArrayOutputStream lines = new ByteArrayOutputStream();

        private @Nullable OutputStreamAppender<ILoggingEvent> appender;

        @Override
        public void beforeEach(ExtensionContext context) {
            LoggerContext loggers = (LoggerContext) LoggerFactory.getILoggerFactory();
            requireAdditive(loggers);
            lines.reset();
            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(loggers);
            encoder.setPattern(PATTERN);
            encoder.setCharset(StandardCharsets.UTF_8);
            encoder.start();
            OutputStreamAppender<ILoggingEvent> capture = new OutputStreamAppender<>();
            capture.setContext(loggers);
            capture.setName("pipeline-test-capture");
            capture.setEncoder(encoder);
            capture.setOutputStream(lines);
            capture.start();
            loggers.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(capture);
            appender = capture;
        }

        @Override
        public void afterEach(ExtensionContext context) {
            OutputStreamAppender<ILoggingEvent> capture = appender;
            if (capture != null) {
                ((LoggerContext) LoggerFactory.getILoggerFactory())
                        .getLogger(Logger.ROOT_LOGGER_NAME)
                        .detachAppender(capture);
                capture.stop();
                appender = null;
            }
        }

        /** The lines logged so far in the current test. */
        String text() {
            return lines.toString(StandardCharsets.UTF_8);
        }

        private static void requireAdditive(LoggerContext loggers) {
            for (Logger logger : loggers.getLoggerList()) {
                String name = logger.getName();
                List<String> cut = READ.stream().filter(read -> isSelfOrAncestor(name, read)).toList();
                if (!logger.isAdditive() && !cut.isEmpty()) {
                    throw new IllegalStateException("Logger " + name + " is not additive, so the lines of " + cut
                            + " never reach the root logger that LogCapture reads");
                }
            }
        }

        /** Whether {@code candidate} is {@code logger} or one of its ancestors in Logback's name hierarchy. */
        private static boolean isSelfOrAncestor(String candidate, String logger) {
            return logger.equals(candidate) || logger.startsWith(candidate + ".") || logger.startsWith(candidate + "$");
        }
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
