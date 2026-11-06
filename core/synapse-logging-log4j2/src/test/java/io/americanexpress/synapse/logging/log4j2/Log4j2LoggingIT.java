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
package io.americanexpress.synapse.logging.log4j2;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.async.AsyncLoggerContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots real {@link org.springframework.boot.SpringApplication}s with Log4j2 (no Logback on the test classpath) and
 * checks the console output, the synchronous default and the failure burst limit.
 */
@Tag("smoke")
@ExtendWith(OutputCaptureExtension.class)
class Log4j2LoggingIT {

    private static final String[] QUIET = {"--management.logging.export.otlp.enabled=false",
            "--management.tracing.export.otlp.enabled=false", "--management.otlp.metrics.export.enabled=false",
            "--spring.main.banner-mode=off"};

    @Test
    void writesEcsJsonWithCorrelationId(CapturedOutput output) {
        try (ConfigurableApplicationContext context = run(QUIET)) {
            fail(context, 1);
        }

        String line = failureLines(output).findFirst().orElseThrow();
        assertThat(line).startsWith("{").endsWith("}");
        assertThat(line).contains("\"log\":{\"level\":\"WARN\"").containsPattern("\"correlationId\":\"[^\"]+\"");
        assertThat(line).contains("\"logger\":\"io.americanexpress.synapse.operation\"");
    }

    @Test
    void isSynchronousByDefault() {
        try (ConfigurableApplicationContext ignored = run(QUIET)) {
            assertThat(LogManager.getContext(false)).isNotInstanceOf(AsyncLoggerContext.class);
        }
    }

    @Test
    void burstLimitDropsExcessFailureLines(CapturedOutput output) {
        String[] arguments = {"--synapse.logging.failure-burst.rate=1", "--synapse.logging.failure-burst.max-burst=2",
                QUIET[0], QUIET[1], QUIET[2], QUIET[3]};
        try (ConfigurableApplicationContext context = run(arguments)) {
            fail(context, 20);
        }

        assertThat(failureLines(output).count()).isBetween(2L, 5L);
    }

    @Test
    void payloadLinesDoNotUseTheFailureBudget(CapturedOutput output) {
        // DEBUG on the Synapse root turns payload logs on whatever their logger name is under it.
        String[] arguments = {"--synapse.logging.failure-burst.rate=1", "--synapse.logging.failure-burst.max-burst=2",
                "--synapse.operations.echo.logging.payloads=true", "--logging.level.io.americanexpress.synapse=DEBUG",
                QUIET[0], QUIET[1], QUIET[2], QUIET[3]};
        try (ConfigurableApplicationContext context = run(arguments)) {
            EchoOperation echo = context.getBean(EchoOperation.class);
            for (int i = 0; i < 10; i++) {
                echo.execute("x");
            }
            fail(context, 2);
        }

        assertThat(output.getOut().lines().filter(line -> line.contains("Operation echo ")).count()).isEqualTo(20L);
        assertThat(output.getOut().lines().filter(line -> line.contains("Operation echo ")))
                .allMatch(line -> line.contains("\"logger\":\"io.americanexpress.synapse.payload\""));
        assertThat(failureLines(output).count()).isEqualTo(2L);
    }

    @Test
    void withoutBurstConfigurationEveryFailureIsLogged(CapturedOutput output) {
        try (ConfigurableApplicationContext context = run(QUIET)) {
            fail(context, 20);
        }

        assertThat(failureLines(output).count()).isEqualTo(20L);
    }

    private static java.util.stream.Stream<String> failureLines(CapturedOutput output) {
        return output
                .getOut()
                .lines()
                .filter(line -> line.contains("\"logger\":\"io.americanexpress.synapse.operation\""));
    }

    private static void fail(ConfigurableApplicationContext context, int times) {
        FailingOperation operation = context.getBean(FailingOperation.class);
        for (int i = 0; i < times; i++) {
            try {
                operation.execute("x");
            } catch (SynapseException expected) {
                assertThat(expected.details()).isEqualTo(SynapseErrors.CONFLICT);
            }
        }
    }

    private static ConfigurableApplicationContext run(String... arguments) {
        return new SpringApplicationBuilder(TestApplication.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run(arguments);
    }

    /** A command named {@code failing} that always fails with a conflict (logged at WARN). */
    static class FailingOperation implements CommandOperation<String, String> {

        @Override
        public String execute(String input) {
            throw new SynapseException(SynapseErrors.CONFLICT);
        }
    }

    /** A query named {@code echo} that returns its input. */
    static class EchoOperation implements QueryOperation<String, String> {

        @Override
        public String execute(String input) {
            return input;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        FailingOperation failing() {
            return new FailingOperation();
        }

        @Bean
        EchoOperation echo() {
            return new EchoOperation();
        }
    }
}
