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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentNameTest {

    private static final String APPLICATION_YAML = "Config resource 'class path resource [application.yaml]' via location "
            + "'optional:classpath:/'";

    private static final String NO_ENVIRONMENT_ACTION = "Set exactly one environment, for example ENV=e2 when "
            + "application.yaml maps spring.profiles.active: ${ENV}. Run locally with -Dspring-boot.run.profiles=local "
            + "or an IDE run configuration; Synapse's test slices activate test; other Spring Boot "
            + "tests declare @ActiveProfiles(\"test\").";

    private static final String SEVERAL_ACTION = "Keep one environment in spring.profiles.active and move feature "
            + "profiles to the environment's profile group, spring.profiles.group.<env>.";

    private static final String AOT_ACTIVE = "During AOT processing the environment is the default profile 'build', but "
            + "these profiles are active: %s. Boot ignores the default profile while any profile is active, so AOT "
            + "processing would not read the build environment's files.";

    private static final String INCLUDE_ACTION = "Move feature profiles from spring.profiles.include to each "
            + "environment's profile group, spring.profiles.group.<env>, including the build environment's "
            + "(spring.profiles.group.build).";

    private static final String LIBRARY_ACTION = "Turn off, for AOT processing, what adds %s. A library that follows "
            + "Boot's cloud platform detection, as spring-cloud-kubernetes does, is turned off by "
            + "--spring.main.cloud-platform=none, for example with -Dspring-boot.aot.jvmArguments="
            + "-Dspring.main.cloud-platform=none on the Maven command line.";

    private final StandardEnvironment environment = new StandardEnvironment();

    @Test
    void nameComesFromSpringProfilesActiveAndSaysWhereItWasSet() {
        environmentVariables(Map.of("ENV", "e2"));
        applicationYaml(Map.of("spring.profiles.active", "${ENV}"));
        assertThat(EnvironmentName.resolve(environment, Set.of()))
                .isEqualTo(new EnvironmentName("e2", "spring.profiles.active=${ENV}, application.yaml"));
    }

    @Test
    void anEnvironmentVariableOverridesTheMapping() {
        environmentVariables(Map.of("SPRING_PROFILES_ACTIVE", "e3", "ENV", "e2"));
        applicationYaml(Map.of("spring.profiles.active", "${ENV}"));
        assertThat(EnvironmentName.resolve(environment, Set.of()))
                .isEqualTo(new EnvironmentName("e3", "spring.profiles.active=e3, environment variables"));
    }

    @Test
    void additionalProfilesCount() {
        environmentVariables(Map.of());
        assertThat(EnvironmentName.resolve(environment, Set.of("test")))
                .isEqualTo(new EnvironmentName("test", "SpringApplication additional profiles"));
    }

    @Test
    void sameNameTwiceIsOne() {
        environmentVariables(Map.of());
        applicationYaml(Map.of("spring.profiles.active", "e2"));
        assertThat(EnvironmentName.resolve(environment, Set.of("e2")))
                .isEqualTo(new EnvironmentName("e2", "spring.profiles.active=e2, application.yaml"));
    }

    @Test
    void includedProfilesDoNotCount() {
        environmentVariables(Map.of());
        applicationYaml(Map.of("spring.profiles.active", "e2", "spring.profiles.include", "kafka-local"));
        assertThat(EnvironmentName.resolve(environment, Set.of()).name()).isEqualTo("e2");
    }

    @Test
    void noEnvironmentFails() {
        environmentVariables(Map.of());
        applicationYaml(Map.of("fixture.greeting", "hello"));
        assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of()))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo("No environment is set: spring.profiles.active has no value.");
                    assertThat(ex.action()).isEqualTo(NO_ENVIRONMENT_ACTION);
                });
    }

    @Test
    void blankValueAndDefaultProfileDoNotCount() {
        environmentVariables(Map.of());
        applicationYaml(Map.of("spring.profiles.active", "", "spring.profiles.default", "local"));
        assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of(" ")))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo("No environment is set: spring.profiles.active has no value. "
                                    + "spring.profiles.default (local) does not count.");
                    assertThat(ex.action()).isEqualTo(NO_ENVIRONMENT_ACTION);
                });
    }

    /**
     * synapse-parent's process-aot passes the build environment as the default profile, with an empty
     * spring.profiles.active, so that Spring's AOT code generation bakes in no active profile.
     */
    @Test
    void duringAotProcessingTheDefaultProfileIsTheEnvironment() {
        environmentVariables(Map.of("SPRING_PROFILES_ACTIVE", "e3"));
        commandLine(Map.of("spring.profiles.active", "", "spring.profiles.default", "build"));
        applicationYaml(Map.of("spring.profiles.active", "${ENV}"));
        assertThat(EnvironmentName.resolve(environment, Set.of(), true))
                .isEqualTo(new EnvironmentName("build", "spring.profiles.default=build, command line arguments"));
    }

    @Test
    void outsideAotProcessingTheDefaultProfileStillDoesNotCount() {
        environmentVariables(Map.of());
        commandLine(Map.of("spring.profiles.active", "", "spring.profiles.default", "build"));
        assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of(), false))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo("No environment is set: spring.profiles.active has no value. "
                                    + "spring.profiles.default (build) does not count.");
                    assertThat(ex.action()).isEqualTo(NO_ENVIRONMENT_ACTION);
                });
    }

    @Test
    void duringAotProcessingAnActiveProfileWinsOverTheDefault() {
        environmentVariables(Map.of());
        applicationYaml(Map.of("spring.profiles.active", "e2", "spring.profiles.default", "build"));
        assertThat(EnvironmentName.resolve(environment, Set.of(), true))
                .isEqualTo(new EnvironmentName("e2", "spring.profiles.active=e2, application.yaml"));
    }

    @Test
    void duringAotProcessingTwoDefaultProfilesFail() {
        environmentVariables(Map.of());
        commandLine(Map.of("spring.profiles.active", "", "spring.profiles.default", "build,e2"));
        assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of(), true))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo("Exactly one environment must be active, but there are 2: build, e2.");
                    assertThat(ex.action()).isEqualTo(SEVERAL_ACTION);
                });
    }

    /** An included profile is active, and an active profile makes Boot ignore the default profile. */
    @Test
    void anIncludedProfileBlamesTheInclude() {
        environmentVariables(Map.of());
        commandLine(Map.of("spring.profiles.active", "", "spring.profiles.default", "build"));
        applicationYaml(Map.of("spring.profiles.include", "metrics"));
        environment.setActiveProfiles("metrics");
        assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of(), true))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo(AOT_ACTIVE.formatted("metrics") + " spring.profiles.include activates metrics.");
                    assertThat(ex.action()).isEqualTo(INCLUDE_ACTION);
                });
    }

    /**
     * A profile that no spring.profiles.include names was added by a library or the platform, as spring-cloud-kubernetes
     * adds kubernetes inside a pod.
     */
    @Test
    void aProfileNotFromIncludeBlamesALibrary() {
        environmentVariables(Map.of());
        commandLine(Map.of("spring.profiles.active", "", "spring.profiles.default", "build"));
        environment.addActiveProfile("kubernetes");
        assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of(), true))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo(AOT_ACTIVE.formatted("kubernetes") + " A library or the platform added "
                                    + "kubernetes (as spring-cloud-kubernetes adds kubernetes when Boot detects "
                                    + "Kubernetes).");
                    assertThat(ex.action())
                            .isEqualTo(LIBRARY_ACTION.formatted("kubernetes"))
                            .contains("--spring.main.cloud-platform=none")
                            .doesNotContain("spring.profiles.include");
                });
    }

    /**
     * Boot adds up the includes of every source (here the command line's and application.yaml's) and expands their
     * profile groups, so a group member of an included profile is the include's too.
     */
    @Test
    void bothCausesAreNamedWithTheirOwnAction() {
        environmentVariables(Map.of());
        commandLine(Map
                .of("spring.profiles.active", "", "spring.profiles.default", "build", "spring.profiles.include",
                        "kafka"));
        applicationYaml(Map.of("spring.profiles.include", "metrics", "spring.profiles.group.metrics", "prometheus"));
        environment.setActiveProfiles("kafka", "metrics", "prometheus", "kubernetes", "cloud");
        assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of(), true))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo(AOT_ACTIVE.formatted("kafka, metrics, prometheus, kubernetes, cloud")
                                    + " spring.profiles.include activates kafka, metrics, prometheus. A library or the "
                                    + "platform added kubernetes, cloud (as spring-cloud-kubernetes adds kubernetes "
                                    + "when Boot detects Kubernetes).");
                    assertThat(ex.action())
                            .isEqualTo(INCLUDE_ACTION + " " + LIBRARY_ACTION.formatted("kubernetes, cloud"));
                });
    }

    /**
     * Spring's AOT processor sets spring.aot.processing for the run. A run of AOT-generated artifacts never counts as
     * AOT processing, even when the property is set by hand.
     */
    @Test
    void aotProcessingIsTheSystemPropertyWithoutGeneratedArtifacts() {
        environmentVariables(Map.of());
        commandLine(Map.of("spring.profiles.active", "", "spring.profiles.default", "build"));
        try {
            System.setProperty("spring.aot.processing", "true");
            assertThat(EnvironmentName.resolve(environment, Set.of()).name()).isEqualTo("build");
            System.setProperty("spring.aot.enabled", "true");
            assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of()))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .endsWith("spring.profiles.default (build) does not count."));
        } finally {
            System.clearProperty("spring.aot.processing");
            System.clearProperty("spring.aot.enabled");
        }
    }

    @Test
    void duringAotProcessingNoProfileAtAllStillFails() {
        environmentVariables(Map.of());
        commandLine(Map.of("spring.profiles.active", ""));
        assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of(), true))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                        .isEqualTo("No environment is set: spring.profiles.active has no value."));
    }

    @Test
    void moreThanOneFails() {
        environmentVariables(Map.of());
        applicationYaml(Map.of("spring.profiles.active", "e2,kafka-local"));
        assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of()))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo("Exactly one environment must be active, but there are 2: e2, kafka-local.");
                    assertThat(ex.action()).isEqualTo(SEVERAL_ACTION);
                });
    }

    @Test
    void activeAndAdditionalTogetherFail() {
        environmentVariables(Map.of());
        applicationYaml(Map.of("spring.profiles.active", "e2"));
        assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of("test")))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo("Exactly one environment must be active, but there are 2: e2, test.");
                    assertThat(ex.action()).isEqualTo(SEVERAL_ACTION);
                });
    }

    @Test
    void namesAreCaseSensitive() {
        environmentVariables(Map.of());
        applicationYaml(Map.of("spring.profiles.active", "E2"));
        assertThat(EnvironmentName.resolve(environment, Set.of()).name()).isEqualTo("E2");
        assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of("e2")))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                        .isEqualTo("Exactly one environment must be active, but there are 2: E2, e2."));
    }

    @Test
    void valuesAreTrimmedAndBlanksDropped() {
        environmentVariables(Map.of());
        applicationYaml(Map.of("spring.profiles.active", " e2 , "));
        assertThat(EnvironmentName.resolve(environment, Set.of(" e2 ")))
                .isEqualTo(new EnvironmentName("e2", "spring.profiles.active= e2 , , application.yaml"));
    }

    @Test
    void nameFromAnIndexedListSaysWhereItWasSet() {
        environmentVariables(Map.of());
        applicationYaml(Map.of("spring.profiles.active[0]", "e2"));
        assertThat(EnvironmentName.resolve(environment, Set.of()))
                .isEqualTo(new EnvironmentName("e2", "spring.profiles.active=e2, application.yaml"));
    }

    /** The fallback is wrong even when the variable is set: the file maps a default environment. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"${ENV:local} | ENV | ", "${ENV:local} | ENV | e2", "${ENV:} | ENV | ",
            "${ENV:${FALLBACK:local}} | ENV | e2", "${ENV:${FALLBACK}} | ENV | e2",
            "${REGION}-${ENV:local} | ENV | e2", "${ENV:local},${OTHER:x} | ENV | e2",
            "${ENV:local} | ENV | e2,e3"})
    void aPlaceholderWithADefaultFails(String raw, String variable, @Nullable String value) {
        Map<String, Object> variables = new LinkedHashMap<>(Map.of("REGION", "us", "FALLBACK", "e5"));
        if (value != null) {
            variables.put("ENV", value);
        }
        environmentVariables(variables);
        applicationYaml(Map.of("spring.profiles.active", raw));
        assertThatThrownBy(() -> EnvironmentName.resolve(environment, Set.of()))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo("spring.profiles.active is '" + raw + "', which falls back to a default "
                                    + "environment when " + variable + " has no value.");
                    assertThat(ex.action())
                            .isEqualTo("Remove the default, for example spring.profiles.active: ${" + variable
                                    + "}, and set " + variable
                                    + " on the platform. Synapse uses no default environment.");
                });
    }

    @Test
    void aDefaultInALowerSourceDoesNotCount() {
        environmentVariables(Map.of("SPRING_PROFILES_ACTIVE", "e3"));
        applicationYaml(Map.of("spring.profiles.active", "${ENV:local}"));
        assertThat(EnvironmentName.resolve(environment, Set.of()))
                .isEqualTo(new EnvironmentName("e3", "spring.profiles.active=e3, environment variables"));
    }

    @Test
    void aColonOutsideAPlaceholderOrAnEscapedPlaceholderIsNoDefault() {
        environmentVariables(Map.of("ENV", "e2"));
        applicationYaml(Map.of("spring.profiles.active", "${ENV}"));
        assertThat(EnvironmentName.resolve(environment, Set.of()).name()).isEqualTo("e2");
        assertThat(EnvironmentName.variableWithDefault("e2:x")).isNull();
        assertThat(EnvironmentName.variableWithDefault("\\${ENV:local}")).isNull();
        assertThat(EnvironmentName.variableWithDefault("${ENV")).isNull();
        assertThat(EnvironmentName.variableWithDefault("${ENV:local")).isNull();
    }

    /** Replaces the machine's environment variables, and its system properties, so neither leaks in. */
    private void environmentVariables(Map<String, Object> variables) {
        environment
                .getPropertySources()
                .replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                new LinkedHashMap<>(variables)));
        environment
                .getPropertySources()
                .replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                        new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
    }

    /** Adds Boot's command line source, above every other. */
    private void commandLine(Map<String, Object> properties) {
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME,
                        properties));
    }

    /** Adds a source named as Boot names the classpath {@code application.yaml}, below the environment variables. */
    private void applicationYaml(Map<String, Object> properties) {
        environment.getPropertySources().addLast(new MapPropertySource(APPLICATION_YAML, properties));
    }
}
