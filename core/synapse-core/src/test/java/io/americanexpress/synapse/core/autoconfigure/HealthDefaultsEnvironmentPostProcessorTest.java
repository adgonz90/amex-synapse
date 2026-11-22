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
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

import io.americanexpress.synapse.core.internal.environment.ConfigFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The readiness group's default {@code include}, set only when the application configures the group. The cases where
 * Boot's binder decides which source wins run a real {@link SpringApplication} over fixture files, with the
 * post-processor found through {@code spring.factories}, and read the key as Boot binds it (a set of names).
 */
class HealthDefaultsEnvironmentPostProcessorTest {

    private static final String INCLUDE = "management.endpoint.health.group.readiness.include";

    private static final String PATH = "management.endpoint.health.group.readiness.additional-path: server:/health\n";

    private static final String ACTIVE = "spring.profiles.active: ${ENV}\n";

    @TempDir
    Path dir;

    /** Without a key of the group, Boot keeps its own probe group: nothing is set. */
    @Test
    void noReadinessKeySetsNothing() throws IOException {
        assertThat(include(Map.of("application.yaml", ACTIVE, "application-e1.yaml", ""))).isNull();
        // Another group's key doesn't define the readiness group.
        assertThat(include(Map
                .of("application.yaml", ACTIVE + "management.endpoint.health.group.liveness.additional-path: "
                        + "server:/live\n", "application-e1.yaml", "")))
                .isNull();
    }

    @Test
    void additionalPathAloneAppliesTheDefault() throws IOException {
        assertThat(include(Map.of("application.yaml", ACTIVE + PATH, "application-e1.yaml", "")))
                .containsExactly("readinessState");
    }

    @Test
    void anEnvironmentVariableReadinessKeyAppliesTheDefault() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map.of("application.yaml", ACTIVE, "application-e1.yaml", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service);
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader, Map
                                .of("ENV", "e1", "MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_ADDITIONALPATH",
                                        "server:/health"))
                        .run()) {
            assertThat(bind(context.getEnvironment())).containsExactly("readinessState");
        }
    }

    /** The default joins the end of the sources, below every file. */
    @Test
    void theDefaultIsTheLowestPrecedenceSource() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map.of("application.yaml", ACTIVE + PATH, "application-e1.yaml", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service);
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader, Map.of("ENV", "e1"))
                        .run()) {
            ConfigurableEnvironment environment = context.getEnvironment();
            assertThat(environment.getPropertySources().stream().map(source -> source.getName()).toList())
                    .last()
                    .isEqualTo(DefaultPropertiesPropertySource.NAME);
            assertThat(Objects
                    .requireNonNull(environment.getPropertySources().get(DefaultPropertiesPropertySource.NAME))
                    .getProperty(INCLUDE)).isEqualTo("readinessState");
        }
    }

    @Test
    void userListIncludeWins() throws IOException {
        Map<String, String> files = Map
                .of("application.yaml", ACTIVE + PATH + INCLUDE + ":\n  - readinessState\n  - ping\n",
                        "application-e1.yaml", "");
        assertThat(include(files)).containsExactlyInAnyOrder("readinessState", "ping");
    }

    @Test
    void indexedIncludeWins() throws IOException {
        Map<String, String> files = Map
                .of("application.yaml", ACTIVE + PATH, "application-e1.yaml", "", "application.properties",
                        INCLUDE + "[0]=ping\n");
        assertThat(include(files)).containsExactly("ping");
    }

    @Test
    void commaSeparatedIncludeWins() throws IOException {
        Map<String, String> files = Map
                .of("application.yaml", ACTIVE + PATH + INCLUDE + ": ping,diskSpace\n", "application-e1.yaml", "");
        assertThat(include(files)).containsExactlyInAnyOrder("ping", "diskSpace");
    }

    @Test
    void environmentFileIncludeWins() throws IOException {
        Map<String, String> files = Map
                .of("application.yaml", ACTIVE + PATH, "application-e1.yaml", INCLUDE + ": ping\n",
                        "application-e2.yaml", INCLUDE + ":\n  - ping\n  - diskSpace\n", "application-e3.yaml", "");
        assertThat(include(files)).containsExactly("ping");
        assertThat(include(files, "e2")).containsExactlyInAnyOrder("ping", "diskSpace");
        // The environment's file only: another environment keeps the default.
        assertThat(include(files, "e3")).containsExactly("readinessState");
    }

    /** An exclude defines the group too, and binds apart from the include. */
    @Test
    void excludeAloneKeepsTheDefaultInclude() throws IOException {
        Map<String, String> files = Map
                .of("application.yaml", ACTIVE + "management.endpoint.health.group.readiness.exclude: ping\n",
                        "application-e1.yaml", "");
        assertThat(include(files)).containsExactly("readinessState");
    }

    /** A module's file is read before the check, so a module that configures the group gets the default. */
    @Test
    void aModuleFileThatConfiguresTheGroupAppliesTheDefault() throws IOException {
        Path module = ConfigFixtures.jar(dir, "lib-health-1.0.0.jar", Map.of("META-INF/synapse/config.yaml", PATH));
        assertThat(include(Map.of("application.yaml", ACTIVE, "application-e1.yaml", ""), "e1", module))
                .containsExactly("readinessState");
    }

    /** A module's file that turns the probes off is seen too: Boot then has no readinessState to include. */
    @Test
    void aModuleFileThatTurnsProbesOffSetsNothing() throws IOException {
        Path module = ConfigFixtures
                .jar(dir, "lib-probes-1.0.0.jar",
                        Map.of("META-INF/synapse/config.yaml", "management.endpoint.health.probes.enabled: false\n"));
        assertThat(include(Map.of("application.yaml", ACTIVE + PATH, "application-e1.yaml", ""), "e1", module))
                .isNull();
    }

    @Test
    void probesOffSetsNothing() throws IOException {
        for (String off : List.of("false", "FALSE", "off")) {
            Map<String, String> files = Map
                    .of("application.yaml",
                            ACTIVE + PATH + "management.endpoint.health.probes.enabled: \"" + off + "\"\n",
                            "application-e1.yaml", "");
            assertThat(include(files)).as(off).isNull();
        }
        // Boot's condition ignores the case of true.
        Map<String, String> on = Map
                .of("application.yaml", ACTIVE + PATH + "management.endpoint.health.probes.enabled: \"TRUE\"\n",
                        "application-e1.yaml", "");
        assertThat(include(on)).containsExactly("readinessState");
    }

    @Test
    void withoutBootHealthSetsNothing() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map.of("application.yaml", ACTIVE + PATH, "application-e1.yaml", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service);
                FilteredClassLoader withoutProbes = new FilteredClassLoader(classLoader,
                        AvailabilityProbesAutoConfiguration.class);
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(withoutProbes, Map.of("ENV", "e1"))
                        .run()) {
            assertThat(context.getEnvironment().containsProperty(INCLUDE)).isFalse();
        }
    }

    /** An application's own default properties keep their value, as with the REST header-size default. */
    @Test
    void anApplicationsOwnDefaultWins() {
        MockEnvironment environment = configured();
        DefaultPropertiesPropertySource.addOrMerge(Map.of(INCLUDE, "ping"), environment.getPropertySources());
        new HealthDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        assertThat(bind(environment)).containsExactly("ping");
    }

    /** Indexed keys too: within one source a scalar would win over them, so no scalar is merged in. */
    @Test
    void anApplicationsOwnIndexedDefaultWins() {
        MockEnvironment environment = configured();
        DefaultPropertiesPropertySource
                .addOrMerge(Map.of(INCLUDE + "[0]", "ping", INCLUDE + "[1]", "diskSpace"),
                        environment.getPropertySources());
        new HealthDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        assertThat(bind(environment)).containsExactlyInAnyOrder("ping", "diskSpace");
    }

    /** The group may also be configured in the application's own default properties. */
    @Test
    void aKeyInTheApplicationsDefaultPropertiesConfiguresTheGroup() {
        MockEnvironment environment = new MockEnvironment();
        DefaultPropertiesPropertySource
                .addOrMerge(new HashMap<>(Map.of("management.endpoint.health.group.readiness.show-details", "never")),
                        environment.getPropertySources());
        new HealthDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        assertThat(bind(environment)).containsExactly("readinessState");
    }

    @Test
    void runningTwiceKeepsASingleDefaultPropertiesSource() {
        MockEnvironment environment = configured();
        HealthDefaultsEnvironmentPostProcessor processor = new HealthDefaultsEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, new SpringApplication());
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getPropertySources().stream().filter(DefaultPropertiesPropertySource::hasMatchingName))
                .hasSize(1);
        assertThat(environment.getProperty(INCLUDE)).isEqualTo("readinessState");
    }

    /** An include alone, in any form, doesn't count as configuring the group: it is the key the default sets. */
    @Test
    void anIncludeAloneDoesNotConfigureTheGroup() {
        for (String key : List.of(INCLUDE, INCLUDE + "[0]")) {
            MockEnvironment environment = new MockEnvironment().withProperty(key, "ping");
            new HealthDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
            assertThat(environment.getPropertySources().contains(DefaultPropertiesPropertySource.NAME))
                    .as(key)
                    .isFalse();
        }
    }

    @Test
    void runsAfterSynapseAddsTheModuleFiles() {
        assertThat(new HealthDefaultsEnvironmentPostProcessor().getOrder())
                .isGreaterThan(new SynapseEnvironmentPostProcessor().getOrder())
                .isLessThan(new BaggageFieldsEnvironmentPostProcessor().getOrder());
    }

    @Test
    void isRegisteredInSpringFactories() throws IOException {
        Properties factories = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/META-INF/spring.factories")) {
            factories.load(Objects.requireNonNull(in));
        }
        assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
                .contains(HealthDefaultsEnvironmentPostProcessor.class.getName());
    }

    /** An environment where the application configures the readiness group. */
    private static MockEnvironment configured() {
        return new MockEnvironment()
                .withProperty("management.endpoint.health.group.readiness.additional-path", "server:/health");
    }

    /** The include an application with these files binds in the environment {@code e1}. */
    private @Nullable Set<String> include(Map<String, String> files) throws IOException {
        return include(files, "e1");
    }

    private @Nullable Set<String> include(Map<String, String> files, String env, Path... libraries)
            throws IOException {
        Path service = ConfigFixtures.directory(dir, "service-" + env + "-" + files.hashCode(), files);
        Path[] roots = new Path[libraries.length + 1];
        roots[0] = service;
        System.arraycopy(libraries, 0, roots, 1, libraries.length);
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(roots);
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader, Map.of("ENV", env))
                        .run()) {
            return bind(context.getEnvironment());
        }
    }

    private static @Nullable Set<String> bind(ConfigurableEnvironment environment) {
        return Binder.get(environment).bind(INCLUDE, Bindable.setOf(String.class)).orElse(null);
    }
}
