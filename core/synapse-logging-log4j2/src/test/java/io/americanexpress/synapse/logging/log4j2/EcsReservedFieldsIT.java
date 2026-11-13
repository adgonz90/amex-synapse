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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.MarkerFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystemProperty;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the list of top-level ECS members that core reserves for context attributes against a Spring Boot upgrade.
 * It logs a plain line, a line with an exception and a line with a marker through Boot's real ECS format, and checks
 * that every top-level field of each line is in the list core keeps in {@code BaggageFieldsEnvironmentPostProcessor}
 * (read from that class), or is a field this test set through the MDC or a key-value. A member Boot adds is then a
 * failing build instead of a context attribute name that silently drops lines.
 */
@ExtendWith(OutputCaptureExtension.class)
class EcsReservedFieldsIT {

    private static final String POST_PROCESSOR = "io.americanexpress.synapse.core.autoconfigure."
            + "BaggageFieldsEnvironmentPostProcessor";

    private static final String LOGGER_NAME = "io.americanexpress.synapse.logging.log4j2.drift";

    private static final String MDC_FIELD = "driftMdc";

    private static final String KEY_VALUE_FIELD = "driftKeyValue";

    private static final String[] QUIET = {"--logging.structured.format.console=ecs",
            "--management.logging.export.otlp.enabled=false", "--management.tracing.export.otlp.enabled=false",
            "--management.otlp.metrics.export.enabled=false", "--spring.main.banner-mode=off"};

    private static final JsonMapper JSON = JsonMapper
            .builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    @Test
    void everyTopLevelFieldBootWritesIsReserved(CapturedOutput output) throws Exception {
        logThreeLines();

        List<String> reserved = reservedNames();
        Set<String> allowed = new LinkedHashSet<>(reserved);
        allowed.add(MDC_FIELD);
        allowed.add(KEY_VALUE_FIELD);
        List<JsonNode> lines = output
                .getOut()
                .lines()
                .filter(line -> line.startsWith("{"))
                .filter(line -> line.contains("\"" + LOGGER_NAME + "\""))
                .map(JSON::readTree)
                .toList();
        assertThat(lines).as("lines on the test logger").hasSize(3);
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode line : lines) {
            List<String> names = line.properties().stream().map(Map.Entry::getKey).toList();
            assertThat(names)
                    .as("top-level fields of %s; a field outside the list is a new Boot member that core must reserve",
                            line)
                    .isSubsetOf(allowed)
                    .contains(MDC_FIELD, KEY_VALUE_FIELD);
            seen.addAll(names);
        }
        // The three lines reach every reserved member, so the list holds no name Boot no longer writes.
        assertThat(seen).containsAll(reserved);
    }

    private static void logThreeLines() {
        Logger logger = LoggerFactory.getLogger(LOGGER_NAME);
        String formatProperty = LoggingSystemProperty.CONSOLE_STRUCTURED_FORMAT.getEnvironmentVariableName();
        System.clearProperty(formatProperty);
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(TestApplication.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run(QUIET); MDC.MDCCloseable mdc = MDC.putCloseable(MDC_FIELD, "m")) {
            logger.atInfo().addKeyValue(KEY_VALUE_FIELD, "plain").log("A plain line");
            logger
                    .atWarn()
                    .addKeyValue(KEY_VALUE_FIELD, "exception")
                    .setCause(new IllegalStateException("boom"))
                    .log("A line with an exception");
            logger
                    .atInfo()
                    .addKeyValue(KEY_VALUE_FIELD, "marker")
                    .addMarker(MarkerFactory.getMarker("DRIFT"))
                    .log("A line with a marker");
        } finally {
            System.clearProperty(formatProperty);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> reservedNames() throws ClassNotFoundException {
        List<String> names = (List<String>) ReflectionTestUtils
                .invokeMethod(Class.forName(POST_PROCESSOR), "ecsMemberNames");
        return List.copyOf(Objects.requireNonNull(names));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
