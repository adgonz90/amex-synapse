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
package io.americanexpress.synapse.logging.lines;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystemProperty;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.data.CommitOutcomeUnknownException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Synapse's failure and payload lines in each of Boot's structured formats, with the correlation id in the MDC as the
 * pipeline puts it there: each is written, is valid JSON, has the stack trace fields the format gives the throwable,
 * and keeps Synapse's fields. One source runs on both backends: {@code synapse-logging-log4j2} compiles it with Log4j2
 * on the class path, and {@code synapse-logging-logback-tests} with Logback, Boot's default (each adds this directory,
 * {@code src/shared-it/java}, as a test source root). Boot's ECS writer names the throwable's member {@code error}, so
 * a Synapse key that nests under {@code error} would make it throw and drop the line; Boot's Logback formatters write
 * the MDC and the event's key-values into one object, so a key-value that repeats an MDC name makes the encoder throw
 * and the line is lost. Without baggage ({@code synapse.observability.require-tracing=false}) the MDC has no
 * correlation id, and the lines carry it as a key-value instead.
 */
@ExtendWith(OutputCaptureExtension.class)
class FailureLineStructuredFormatIT {

    private static final String[] QUIET = {"--management.logging.export.otlp.enabled=false",
            "--management.tracing.export.otlp.enabled=false", "--management.otlp.metrics.export.enabled=false",
            "--spring.main.banner-mode=off"};

    private static final JsonMapper JSON = JsonMapper
            .builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** The correlation id in the MDC while the last operation ran. */
    private static final AtomicReference<@Nullable String> MDC_CORRELATION_ID = new AtomicReference<>();

    /** The correlation id of the request context while the last operation ran. */
    private static final AtomicReference<@Nullable String> CONTEXT_CORRELATION_ID = new AtomicReference<>();

    /** Baggage off, so tracing can't put the correlation id in the MDC, and Synapse's tracing check off to allow it. */
    private static final String[] NO_BAGGAGE = {"--management.tracing.baggage.enabled=false",
            "--synapse.observability.require-tracing=false"};

    /** Where each format puts the fields the tests read. */
    enum Format {

        ECS("ecs", "/log/level", "/error/type", "/error/stack_trace", ""),

        LOGSTASH("logstash", "/level", null, "/stack_trace", ""),

        GELF("gelf", "/_level_name", "/_error_type", "/_error_stack_trace", "_");

        final String id;

        final String level;

        final @Nullable String errorType;

        final String stackTrace;

        final String prefix;

        Format(String id, String level, @Nullable String errorType, String stackTrace, String prefix) {
            this.id = id;
            this.level = level;
            this.errorType = errorType;
            this.stackTrace = stackTrace;
            this.prefix = prefix;
        }

        String field(JsonNode line, String name) {
            return line.path(prefix + name).asString();
        }
    }

    @Tag("smoke")
    @ParameterizedTest
    @EnumSource(Format.class)
    void writesTheInternalFailureLine(Format format, CapturedOutput output) {
        run(format, context -> call(context, BrokenOperation.class));

        JsonNode line = failureLine(output);
        assertThat(line.at(format.level).asString()).isEqualTo("ERROR");
        assertErrorType(format, line, SynapseException.class.getName());
        assertThat(line.at(format.stackTrace).asString())
                .contains("Caused by: java.lang.IllegalStateException: ledger connection refused");
        if (format == Format.ECS) {
            assertThat(line.at("/error/message").asString()).isEqualTo("An internal error occurred");
        }
        assertFields(format, line, "broken", "INTERNAL", "INTERNAL");
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void writesTheCommitOutcomeUnknownLine(Format format, CapturedOutput output) {
        run(format, context -> call(context, CommitLostOperation.class));

        JsonNode line = failureLine(output);
        assertThat(line.at(format.level).asString()).isEqualTo("ERROR");
        assertErrorType(format, line, SynapseException.class.getName());
        assertThat(line.at(format.stackTrace).asString())
                .contains("Caused by: " + CommitOutcomeUnknownException.class.getName()
                        + ": The connection was lost while the transaction committed: whether it committed is unknown")
                .contains("Caused by: java.sql.SQLException: An I/O error occurred while sending to the backend.");
        assertFields(format, line, "commit-lost", "COMMIT_OUTCOME_UNKNOWN", "INTERNAL");
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void writesTheUnavailableFailureLineWithItsCause(Format format, CapturedOutput output) {
        run(format, context -> call(context, UnavailableOperation.class));

        JsonNode line = failureLine(output);
        assertThat(line.at(format.level).asString()).isEqualTo("WARN");
        assertErrorType(format, line, IllegalStateException.class.getName());
        assertFields(format, line, "unavailable", "UNAVAILABLE", "UNAVAILABLE");
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void writesAFailureLineWithoutCause(Format format, CapturedOutput output) {
        run(format, context -> call(context, ConflictOperation.class));

        JsonNode line = failureLine(output);
        assertThat(line.at(format.level).asString()).isEqualTo("WARN");
        if (format == Format.ECS) {
            assertThat(line.has("error")).isFalse();
        }
        assertFields(format, line, "conflict", "CONFLICT", "CONFLICT");
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void writesThePayloadLines(Format format, CapturedOutput output) {
        run(format, context -> {
            EchoOperation echo = context.getBean(EchoOperation.class);
            assertThat(echo.execute("x")).isEqualTo("x");
        }, "--synapse.operations.echo.logging.payloads=true", "--logging.level.io.americanexpress.synapse=DEBUG");

        List<JsonNode> lines = lines(output, "io.americanexpress.synapse.payload");
        assertThat(lines).as("payload lines").hasSize(2);
        for (JsonNode line : lines) {
            assertThat(format.field(line, "operation")).isEqualTo("echo");
            assertThat(format.field(line, "correlationId")).isNotEmpty().isEqualTo(MDC_CORRELATION_ID.get());
        }
    }

    /**
     * An extra MDC entry next to the failure line's fields, as a context attribute is one (the baggage bridge puts it
     * there in a service; here the operation puts it itself): the line is still written, with both.
     */
    @ParameterizedTest
    @EnumSource(Format.class)
    void writesTheFailureLineWithAContextAttributeInTheMdc(Format format, CapturedOutput output) {
        try {
            run(format, context -> call(context, TenantOperation.class));
        } finally {
            MDC.remove("tenant");
        }

        JsonNode line = failureLine(output);
        assertThat(format.field(line, "tenant")).isEqualTo("acme");
        assertFields(format, line, "tenant", "CONFLICT", "CONFLICT");
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void writesTheCorrelationIdOnTheFailureLineWithoutBaggage(Format format, CapturedOutput output) {
        run(format, context -> call(context, BrokenOperation.class), NO_BAGGAGE);

        JsonNode line = failureLine(output);
        assertThat(MDC_CORRELATION_ID.get()).as("correlation id in the MDC during the call").isNull();
        assertThat(CONTEXT_CORRELATION_ID.get()).isNotEmpty();
        assertThat(format.field(line, "correlationId")).isEqualTo(CONTEXT_CORRELATION_ID.get());
        assertThat(format.field(line, "errorCode")).isEqualTo("INTERNAL");
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void writesTheCorrelationIdOnThePayloadLinesWithoutBaggage(Format format, CapturedOutput output) {
        String[] arguments = Stream
                .concat(Stream.of(NO_BAGGAGE),
                        Stream
                                .of("--synapse.operations.echo.logging.payloads=true",
                                        "--logging.level.io.americanexpress.synapse=DEBUG"))
                .toArray(String[]::new);
        run(format, context -> context.getBean(EchoOperation.class).execute("x"), arguments);

        List<JsonNode> lines = lines(output, "io.americanexpress.synapse.payload");
        assertThat(lines).as("payload lines").hasSize(2);
        assertThat(MDC_CORRELATION_ID.get()).as("correlation id in the MDC during the call").isNull();
        for (JsonNode line : lines) {
            assertThat(format.field(line, "correlationId")).isNotEmpty().isEqualTo(CONTEXT_CORRELATION_ID.get());
        }
    }

    private static void assertErrorType(Format format, JsonNode line, String type) {
        if (format.errorType != null) {
            assertThat(line.at(format.errorType).asString()).isEqualTo(type);
        } else {
            assertThat(line.at(format.stackTrace).asString()).startsWith(type + ": ");
        }
    }

    private static void assertFields(Format format, JsonNode line, String operation, String code, String category) {
        assertThat(format.field(line, "operation")).isEqualTo(operation);
        assertThat(format.field(line, "errorCode")).isEqualTo(code);
        assertThat(format.field(line, "errorCategory")).isEqualTo(category);
        assertThat(MDC_CORRELATION_ID.get()).as("correlation id in the MDC during the call").isNotEmpty();
        assertThat(format.field(line, "correlationId")).isEqualTo(MDC_CORRELATION_ID.get());
    }

    /** The one line on the failure logger, parsed strictly. */
    private static JsonNode failureLine(CapturedOutput output) {
        List<JsonNode> lines = lines(output, "io.americanexpress.synapse.operation");
        assertThat(lines).as("failure lines on stdout").hasSize(1);
        return lines.getFirst();
    }

    private static List<JsonNode> lines(CapturedOutput output, String logger) {
        return output
                .getOut()
                .lines()
                .filter(line -> line.startsWith("{"))
                .filter(line -> line.contains("\"" + logger + "\""))
                .map(JSON::readTree)
                .toList();
    }

    private static void call(ConfigurableApplicationContext context,
            Class<? extends CommandOperation<String, String>> type) {
        CommandOperation<String, String> operation = context.getBean(type);
        assertThatExceptionOfType(SynapseException.class).isThrownBy(() -> operation.execute("x"));
    }

    /**
     * Boots the application with the format and runs the calls. Boot copies the format into a system property only
     * when it is absent, so the property is cleared before and after the run.
     */
    private static void run(Format format, Calls calls, String... extra) {
        String formatProperty = LoggingSystemProperty.CONSOLE_STRUCTURED_FORMAT.getEnvironmentVariableName();
        String[] arguments = Stream
                .of(Stream.of("--logging.structured.format.console=" + format.id), Stream.of(QUIET),
                        Stream.of(extra))
                .flatMap(stream -> stream)
                .toArray(String[]::new);
        MDC_CORRELATION_ID.set(null);
        CONTEXT_CORRELATION_ID.set(null);
        System.clearProperty(formatProperty);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run(arguments)) {
            calls.run(context);
        } finally {
            System.clearProperty(formatProperty);
        }
    }

    @FunctionalInterface
    interface Calls {

        void run(ConfigurableApplicationContext context);
    }

    private static void recordMdc() {
        MDC_CORRELATION_ID.set(MDC.get("correlationId"));
        CONTEXT_CORRELATION_ID.set(RequestContext.find().map(RequestContext::correlationId).orElse(null));
    }

    /** Fails with a JVM exception, which the pipeline translates to {@code INTERNAL} (logged at ERROR). */
    static class BrokenOperation implements CommandOperation<String, String> {

        @Override
        public String execute(String input) {
            recordMdc();
            throw new IllegalStateException("ledger connection refused");
        }
    }

    /** Loses its connection while committing: the data translator makes it {@code COMMIT_OUTCOME_UNKNOWN}. */
    static class CommitLostOperation implements CommandOperation<String, String> {

        @Override
        public String execute(String input) {
            recordMdc();
            throw new CommitOutcomeUnknownException(
                    new SQLException("An I/O error occurred while sending to the backend.", "08006"));
        }
    }

    /** Fails with {@code UNAVAILABLE} and a downstream cause (logged at WARN with the cause's stack trace). */
    static class UnavailableOperation implements CommandOperation<String, String> {

        @Override
        public String execute(String input) {
            recordMdc();
            throw new SynapseException(SynapseErrors.UNAVAILABLE,
                    new IllegalStateException("ledger connection refused"));
        }
    }

    /** Fails with a conflict and no cause (logged at WARN without a stack trace). */
    static class ConflictOperation implements CommandOperation<String, String> {

        @Override
        public String execute(String input) {
            recordMdc();
            throw new SynapseException(SynapseErrors.CONFLICT);
        }
    }

    /** Fails with a conflict while a context attribute, {@code tenant}, is in the MDC. */
    static class TenantOperation implements CommandOperation<String, String> {

        @Override
        public String execute(String input) {
            recordMdc();
            MDC.put("tenant", "acme");
            throw new SynapseException(SynapseErrors.CONFLICT);
        }
    }

    /** Returns its input; its payloads are logged at DEBUG. */
    static class EchoOperation implements QueryOperation<String, String> {

        @Override
        public String execute(String input) {
            recordMdc();
            return input;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        BrokenOperation broken() {
            return new BrokenOperation();
        }

        @Bean
        CommitLostOperation commitLost() {
            return new CommitLostOperation();
        }

        @Bean
        UnavailableOperation unavailable() {
            return new UnavailableOperation();
        }

        @Bean
        ConflictOperation conflict() {
            return new ConflictOperation();
        }

        @Bean
        TenantOperation tenant() {
            return new TenantOperation();
        }

        @Bean
        EchoOperation echo() {
            return new EchoOperation();
        }
    }
}
