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
package io.americanexpress.synapse.core.internal.environment;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Starts real applications over a service jar and two module jars, as a packaged service runs: the environment comes
 * from {@code ENV}, the module files load below the service's, and the startup report names every source without a
 * value. Fixture runs leave Boot's logging system out, so only message text is asserted, never the log line format.
 */
@ExtendWith(OutputCaptureExtension.class)
class EnvironmentStartupIT {

    private static final String REPORT_START = "Synapse environment: ";

    /** A credential-like value, which no output may show. */
    private static final String SECRET = "s3cr3t";

    /** Text in an unparsable file, which the parser quotes in its message. */
    private static final String PARSER_TEXT = SECRET + "-unclosed";

    /** What the cause keeps of the parser's failure on that file: its type and 1-based position. */
    private static final String PARSER_POSITION = "ParserException at line 2, column 1";

    /** The parser's package, in the frames of its stack trace (its exceptions print no class name). */
    private static final String PARSER_PACKAGE = "org.yaml.snakeyaml.";

    @TempDir
    Path dir;

    @Test
    @Tag("smoke")
    void startsAndReportsAServiceWithTwoModules(CapturedOutput output) throws IOException {
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client(), data());
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader,
                                Map
                                        .of("ENV", "e2", "SYNAPSE_CLIENT_ACCOUNTS_MAXCONNECTIONS", "99",
                                                "SYNAPSE_DATA_STORES_BOOKS_PASSWORD", "s3cr3t-from-env"))
                        .run("--synapse.client.accounts.base-url=http://cmdline")) {
            Environment environment = context.getEnvironment();
            assertThat(environment.getActiveProfiles()).containsExactly("e2");
            // The command line beats every file; environment variables beat every file.
            assertThat(environment.getProperty("synapse.client.accounts.base-url")).isEqualTo("http://cmdline");
            assertThat(environment.getProperty("synapse.client.accounts.max-connections")).isEqualTo("99");
            assertThat(environment.getProperty("synapse.data.stores.books.password")).isEqualTo("s3cr3t-from-env");
            // application-e2.yaml beats application.yaml, which beats the modules' files.
            assertThat(environment.getProperty("synapse.client.accounts.timeout")).isEqualTo("4s");
            assertThat(environment.getProperty("synapse.data.stores.books.pool-size")).isEqualTo("8");
            // A module's config-e2.yaml beats its config.yaml.
            assertThat(environment.getProperty("synapse.data.stores.books.url"))
                    .isEqualTo("jdbc:postgresql://e2/books");
            assertThat(environment.getProperty("synapse.client.accounts.retries")).isEqualTo("3");
        }
        String report = report(output);
        assertThat(report).startsWith("""
                Synapse environment: e2 (spring.profiles.active=${ENV}, application.yaml)
                Configuration, highest priority first:
                  1. Synapse baggage fields
                  2. command line arguments
                  3. Java system properties
                  4. environment variables
                  5. application-e2.yaml  service-books-1.0.jar
                  6. application.yaml     service-books-1.0.jar
                  7. config-e2.yaml       client-accounts-1.4.0.jar
                  8. config-e2.yaml       data-books-1.4.0.jar
                  9. config.yaml          client-accounts-1.4.0.jar
                  10. config.yaml          data-books-1.4.0.jar
                """);
        assertThat(report)
                .doesNotContain("http://cmdline")
                .doesNotContain("http://accounts")
                .doesNotContain("jdbc:postgresql")
                .doesNotContain("s3cr3t")
                .doesNotContain("4s")
                .doesNotContain("max-connections");
        assertThat(output.getOut()).doesNotContain("s3cr3t").doesNotContain("http://cmdline");
    }

    @Test
    void unsetEnvIsExplained(CapturedOutput output) throws IOException {
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client(), data())) {
            assertThat(catchThrowable(() -> ConfigFixtures.application(classLoader, Map.of()).run())).isNotNull();
        }
        assertThat(output)
                .contains("APPLICATION FAILED TO START")
                .contains("No environment is set: spring.profiles.active is '${ENV}', and ENV has no value.")
                .doesNotContain(REPORT_START);
    }

    @Test
    void wrongCaseFailsWithTheServiceInAJar(CapturedOutput output) throws IOException {
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client(), data())) {
            assertThat(catchThrowable(() -> ConfigFixtures.application(classLoader, Map.of("ENV", "E2")).run()))
                    .isNotNull();
        }
        assertThat(output)
                .contains("APPLICATION FAILED TO START")
                .contains("The environment 'E2' has no file in the service: none of application-E2.yaml, "
                        + "application-E2.yml, application-E2.properties was loaded or found on the classpath. "
                        + "Environment names are case-sensitive.")
                .doesNotContain(REPORT_START);
    }

    @Test
    void twoModulesWithOneKeyFail(CapturedOutput output) throws IOException {
        Path rogue = ConfigFixtures
                .jar(dir, "rogue-1.0.jar",
                        Map.of("META-INF/synapse/config.yaml", "synapse.client.accounts.retries: 5\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client(), data(), rogue)) {
            assertThat(catchThrowable(() -> ConfigFixtures.application(classLoader, Map.of("ENV", "e2")).run()))
                    .isNotNull();
        }
        assertThat(output)
                .contains("APPLICATION FAILED TO START")
                .contains("synapse.client.accounts.retries is set by two modules: client-accounts-1.4.0.jar "
                        + "(config.yaml) and rogue-1.0.jar (config.yaml).")
                .doesNotContain(REPORT_START);
    }

    @Test
    void aFileThatCannotBeParsedKeepsTheParserTextOutOfTheStartupMessage(CapturedOutput output) throws IOException {
        Throwable failure = withReporterLevel(LogLevel.INFO, this::runWithAnUnparsableModuleFile);
        // The cause keeps only the parser failure's type and position, which quote nothing of the file.
        assertThat(failure)
                .isInstanceOf(SynapseConfigurationException.class)
                .cause()
                .hasMessage(PARSER_POSITION)
                .hasNoCause();
        // At the default level, Boot prints the description and action only.
        assertThat(output)
                .contains("APPLICATION FAILED TO START")
                .contains("Synapse could not read config.yaml (rogue-1.0.jar).")
                .contains("Check that the file is readable and valid YAML or properties.")
                .doesNotContain(PARSER_TEXT)
                .doesNotContain(PARSER_PACKAGE)
                .doesNotContain("Application failed to start due to an exception");
    }

    @Test
    void theDebugLogShowsTheParserFailuresTypeAndPositionButNoneOfTheFile(CapturedOutput output)
            throws IOException {
        withReporterLevel(LogLevel.DEBUG, this::runWithAnUnparsableModuleFile);
        // Boot logs the whole cause chain at DEBUG. The cause has the parser's stack frames, so the failing frame is
        // still found, but no text of the file.
        assertThat(output)
                .contains("Application failed to start due to an exception")
                .contains("Caused by: ")
                .contains(PARSER_POSITION)
                .contains(PARSER_PACKAGE)
                .doesNotContain(SECRET);
    }

    private Throwable runWithAnUnparsableModuleFile() throws IOException {
        Path rogue = ConfigFixtures
                .jar(dir, "rogue-1.0.jar", Map.of("META-INF/synapse/config.yaml", "fixture: [" + PARSER_TEXT + "\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), rogue)) {
            return catchThrowable(() -> ConfigFixtures.application(classLoader, Map.of("ENV", "e2")).run());
        }
    }

    /**
     * Runs with Boot's failure reporter logging at the given level, then restores the level it inherits. Fixture runs
     * leave Boot's logging system out, so the level is set here rather than by the test JVM's logging configuration.
     */
    private static Throwable withReporterLevel(LogLevel level, FailingRun run) throws IOException {
        LoggingSystem loggingSystem = LoggingSystem.get(EnvironmentStartupIT.class.getClassLoader());
        loggingSystem.setLogLevel(LoggingFailureAnalysisReporter.class.getName(), level);
        try {
            return run.run();
        } finally {
            loggingSystem.setLogLevel(LoggingFailureAnalysisReporter.class.getName(), null);
        }
    }

    @FunctionalInterface
    private interface FailingRun {

        Throwable run() throws IOException;
    }

    private Path service() {
        return ConfigFixtures
                .jar(dir, "service-books-1.0.jar", Map
                        .of("application.yaml",
                                "spring.profiles.active: ${ENV}\nsynapse.client.accounts.timeout: 3s\n"
                                        + "synapse.data.stores.books.pool-size: 8\n",
                                "application-e2.yaml", "synapse.client.accounts.timeout: 4s\n"));
    }

    private Path client() {
        return ConfigFixtures
                .jar(dir, "client-accounts-1.4.0.jar", Map
                        .of("META-INF/synapse/config.yaml",
                                "synapse:\n  client:\n    accounts:\n      base-url: http://accounts-base\n"
                                        + "      max-connections: 10\n      timeout: 1s\n      retries: 3\n",
                                "META-INF/synapse/config-e2.yaml",
                                "synapse.client.accounts.base-url: http://accounts-e2\n"
                                        + "synapse.client.accounts.timeout: 2s\n"));
    }

    private Path data() {
        return ConfigFixtures
                .jar(dir, "data-books-1.4.0.jar", Map
                        .of("META-INF/synapse/config.yaml",
                                "synapse.data.stores.books.url: jdbc:postgresql://base/books\n"
                                        + "synapse.data.stores.books.pool-size: 5\n",
                                "META-INF/synapse/config-e2.yaml",
                                "synapse.data.stores.books.url: jdbc:postgresql://e2/books\n"));
    }

    /**
     * The report in the captured output: from {@code Synapse environment: } (after the log line's prefix) through its
     * last numbered line, with line endings normalised.
     */
    private static String report(CapturedOutput output) {
        String out = output.getOut().replace("\r\n", "\n");
        int start = out.indexOf(REPORT_START);
        assertThat(start).as("the startup report in:%n%s", out).isNotNegative();
        StringBuilder report = new StringBuilder();
        String[] lines = out.substring(start).split("\n", -1);
        report.append(lines[0]).append('\n').append(lines[1]).append('\n');
        for (int i = 2; i < lines.length && lines[i].matches("^  \\d+\\. .*"); i++) {
            report.append(lines[i]).append('\n');
        }
        return report.toString();
    }
}
