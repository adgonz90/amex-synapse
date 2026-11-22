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
package io.americanexpress.synapse.core.autoconfigure;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import io.americanexpress.synapse.core.internal.environment.ConfigFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class UnresolvedEnvironmentFailureAnalyzerTest {

    private static final String DESCRIPTION = "No environment is set: spring.profiles.active is '${ENV}', and ENV has no value.";

    private final UnresolvedEnvironmentFailureAnalyzer analyzer = new UnresolvedEnvironmentFailureAnalyzer();

    @TempDir
    Path dir;

    @Test
    void explainsAnUnresolvedPlaceholder() throws IOException {
        assertThat(analyzer.analyze(unsetEnvFailure()))
                .isNotNull()
                .extracting(FailureAnalysis::getDescription, FailureAnalysis::getAction)
                .containsExactly(DESCRIPTION,
                        "Set ENV to the name of the environment (the service has an application-<env>.yaml for "
                                + "each). Run locally with -Dspring-boot.run.profiles=local or an IDE run "
                                + "configuration; Synapse's test slices activate test; other Spring Boot "
                                + "tests declare @ActiveProfiles(\"test\").");
    }

    /**
     * In an executable jar, spring-boot comes before synapse-core on the classpath, so Boot's analyzers are listed
     * first. Boot's {@code BindFailureAnalyzer} also answers this failure; Synapse's must be sorted ahead of it.
     */
    @Test
    void answersBeforeBootsBindFailureAnalyzer() throws Exception {
        Throwable failure = unsetEnvFailure();
        // Package-private in Boot; BeanUtils makes its constructor accessible.
        FailureAnalyzer boot = BeanUtils
                .instantiateClass(Class.forName("org.springframework.boot.diagnostics.analyzer.BindFailureAnalyzer"),
                        FailureAnalyzer.class);
        assertThat(boot.analyze(failure)).isNotNull();

        // Boot's FailureAnalyzers loads the analyzers with SpringFactoriesLoader, which sorts them this way, and
        // uses the first analysis.
        List<FailureAnalyzer> analyzers = new ArrayList<>(List.of(boot, analyzer));
        AnnotationAwareOrderComparator.sort(analyzers);
        FailureAnalysis first = analyzers
                .stream()
                .map(candidate -> candidate.analyze(failure))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow();
        assertThat(first.getDescription()).isEqualTo(DESCRIPTION);
    }

    @Test
    void ignoresOtherFailures() {
        assertThat(analyzer.analyze(new IllegalStateException("x"))).isNull();
        assertThat(analyzer
                .analyze(new IllegalStateException("Invalid profile property value found in Environment under "
                        + "'spring.profiles.active'",
                        new IllegalStateException("Profile 'a b' must contain a letter, digit or allowed char"))))
                .isNull();
    }

    @Test
    void explainsAnUnresolvedPlaceholderInAnIndexedList() throws IOException {
        assertThat(analyzer.analyze(failure("spring.profiles.active[0]: ${ENV}\n")))
                .isNotNull()
                .extracting(FailureAnalysis::getDescription)
                .isEqualTo(DESCRIPTION);
    }

    /**
     * Boot validates the profile names of {@code spring.profiles.include}, {@code spring.profiles.default} and profile
     * groups too. An unresolved placeholder there is not the environment, so Boot's own message stands.
     */
    @ParameterizedTest
    @ValueSource(strings = {"spring.profiles.active: e2\nspring.profiles.include: ${FEATURES}\n",
            "spring.profiles.active: e2\nspring.profiles.include[0]: ${FEATURES}\n",
            "spring.profiles.default: ${FEATURES}\n",
            "spring.profiles.active: e2\nspring.profiles.group.e2: ${FEATURES}\n"})
    void ignoresAnUnresolvedPlaceholderInAnotherProfileProperty(String applicationYaml) throws IOException {
        assertThat(analyzer.analyze(failure(applicationYaml))).isNull();
    }

    @Test
    void readsThePropertyFromBootsValidationWrapper() {
        IllegalStateException unresolved = new IllegalStateException(
                "Profile '${ENV}' must contain a letter, digit or allowed char ('-', '_', '.', '+', '@')");
        assertThat(analyzer
                .analyze(new IllegalStateException(
                        "Invalid profile property value found in Environment under 'spring.profiles.active'",
                        unresolved)))
                .isNotNull()
                .extracting(FailureAnalysis::getDescription)
                .isEqualTo(DESCRIPTION);
        assertThat(analyzer
                .analyze(new IllegalStateException(
                        "Invalid profile property value found in Environment under 'spring.profiles.include'",
                        unresolved)))
                .isNull();
        assertThat(analyzer.analyze(unresolved)).isNull();
    }

    /** The failure of a run whose application.yaml maps spring.profiles.active: ${ENV}, with no ENV. */
    private Throwable unsetEnvFailure() throws IOException {
        return failure("spring.profiles.active: ${ENV}\n");
    }

    /** The failure of a run over the given application.yaml (with an application-e2.yaml), with no variables. */
    private Throwable failure(String applicationYaml) throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service-" + Integer.toHexString(applicationYaml.hashCode()),
                        Map.of("application.yaml", applicationYaml, "application-e2.yaml", ""));
        Throwable failure;
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            failure = catchThrowable(() -> ConfigFixtures.application(classLoader, Map.of()).run());
        }
        assertThat(failure).isNotNull();
        return failure;
    }
}
