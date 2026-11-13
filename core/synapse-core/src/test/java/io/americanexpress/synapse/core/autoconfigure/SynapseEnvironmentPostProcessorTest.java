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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.aot.AbstractAotProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.internal.environment.BakedApplication;
import io.americanexpress.synapse.core.internal.environment.ConfigFixtures;
import io.americanexpress.synapse.core.internal.environment.ModuleConfigFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynapseEnvironmentPostProcessorTest {

    @TempDir
    Path dir;

    @Test
    void runsRightAfterConfigData() {
        assertThat(new SynapseEnvironmentPostProcessor().getOrder())
                .isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
    }

    @Test
    void isRegisteredInSpringFactories() {
        SpringFactoriesLoader loader = SpringFactoriesLoader.forDefaultResourceLocation();
        assertThat(loader.load(EnvironmentPostProcessor.class, null, (type, name, failure) -> {
        })).anyMatch(SynapseEnvironmentPostProcessor.class::isInstance);
        assertThat(loader.load(FailureAnalyzer.class, null, (type, name, failure) -> {
        })).anyMatch(UnresolvedEnvironmentFailureAnalyzer.class::isInstance);
        assertThat(loader.load(ApplicationListener.class, null, (type, name, failure) -> {
        })).anyMatch(ConfigurationReportListener.class::isInstance);
    }

    @Test
    void startupFailsWithoutAnEnvironment() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map.of("application.yaml", "fixture.greeting: hello\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(() -> ConfigFixtures.application(classLoader, Map.of()).run())
                    .isInstanceOfSatisfying(SynapseConfigurationException.class,
                            ex -> assertThat(ex.description())
                                    .isEqualTo("No environment is set: spring.profiles.active has no value."));
        }
    }

    /** The service has a file for the fallback, so only the default itself fails startup. */
    @Test
    void startupFailsWhenSpringProfilesActiveFallsBackToADefault() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map
                        .of("application.yaml", "spring.profiles.active: ${ENV:local}\n", "application-local.yaml",
                                "", "application-e2.yaml", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            for (Map<String, Object> variables : List.<Map<String, Object>>of(Map.of(), Map.of("ENV", "e2"))) {
                assertThatThrownBy(() -> ConfigFixtures.application(classLoader, variables).run())
                        .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                            assertThat(ex.description())
                                    .isEqualTo("spring.profiles.active is '${ENV:local}', which falls back to a "
                                            + "default environment when ENV has no value.");
                            assertThat(ex.action())
                                    .isEqualTo("Remove the default, for example spring.profiles.active: ${ENV}, and "
                                            + "set ENV on the platform. Synapse uses no default environment.");
                        });
            }
        }
    }

    @Test
    void startsWithOneEnvironment() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map
                        .of("application.yaml", "fixture.greeting: hello\n", "application-e2.yaml",
                                "fixture.greeting: hello e2\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service);
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader, Map.of())
                        .run("--spring.profiles.active=e2")) {
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("e2");
            assertThat(context.getEnvironment().getProperty("fixture.greeting")).isEqualTo("hello e2");
        }
    }

    /**
     * As synapse-parent's process-aot runs the application: the command line empties spring.profiles.active (over
     * application.yaml's ${ENV} and the platform's SPRING_PROFILES_ACTIVE) and names the build environment as the
     * default profile. Spring's AOT processor sets spring.aot.processing for the run.
     */
    @Test
    void duringAotProcessingTheEnvironmentIsTheDefaultProfile() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map
                        .of("application.yaml", "spring.profiles.active: ${ENV}\nfixture.greeting: hello\n",
                                "application-build.yaml", "fixture.greeting: hello build\n", "application-e3.yaml",
                                "fixture.greeting: hello e3\n"));
        String[] args = {"--spring.profiles.active=", "--spring.profiles.default=build"};
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            Map<String, Object> variables = Map.of("SPRING_PROFILES_ACTIVE", "e3");
            assertThatThrownBy(() -> ConfigFixtures.application(classLoader, variables).run(args))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class,
                            ex -> assertThat(ex.description())
                                    .isEqualTo("No environment is set: spring.profiles.active has no value. "
                                            + "spring.profiles.default (build) does not count."));
            System.setProperty(AbstractAotProcessor.AOT_PROCESSING, "true");
            try (ConfigurableApplicationContext context = ConfigFixtures
                    .application(classLoader, variables)
                    .run(args)) {
                assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
                assertThat(context.getEnvironment().getDefaultProfiles()).containsExactly("build");
                assertThat(context.getEnvironment().getProperty("fixture.greeting")).isEqualTo("hello build");
            } finally {
                System.clearProperty(AbstractAotProcessor.AOT_PROCESSING);
            }
        }
    }

    /**
     * An include during AOT processing would make Boot ignore the default profile, and with it the build environment's
     * files: the build fails instead of processing the application without them.
     */
    @Test
    void duringAotProcessingAnIncludedProfileFails() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map
                        .of("application.yaml", "spring.profiles.active: ${ENV}\nspring.profiles.include: kafka\n",
                                "application-build.yaml", "fixture.greeting: hello build\n"));
        String[] args = {"--spring.profiles.active=", "--spring.profiles.default=build"};
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            System.setProperty(AbstractAotProcessor.AOT_PROCESSING, "true");
            try {
                assertThatThrownBy(() -> ConfigFixtures.application(classLoader, Map.of()).run(args))
                        .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                                .isEqualTo("During AOT processing the environment is the default profile 'build', "
                                        + "but these profiles are active: kafka. Boot ignores the default profile "
                                        + "while any profile is active, whether spring.profiles.include set it or a library "
                                        + "added it (as spring-cloud-kubernetes adds kubernetes inside a Kubernetes pod), so "
                                        + "AOT processing would not read the build environment's files."));
            } finally {
                System.clearProperty(AbstractAotProcessor.AOT_PROCESSING);
            }
        }
    }

    /**
     * A library that adds a profile at run time, as spring-cloud-kubernetes adds {@code kubernetes} inside a pod
     * ({@link KubernetesLikeProfile}), keeps working on the JVM and in AOT mode: only the profiles that AOT-generated
     * code activates fail.
     */
    @Test
    void aProfileThatALibraryAddsIsAccepted() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map
                        .of("application.yaml", "fixture.greeting: hello\n", "application-e2.yaml", "",
                                "META-INF/spring.factories", "org.springframework.boot.EnvironmentPostProcessor="
                                        + KubernetesLikeProfile.class.getName() + "\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            SpringApplication application = ConfigFixtures.application(classLoader, Map.of());
            try (ConfigurableApplicationContext context = application.run("--spring.profiles.active=e2")) {
                ConfigurableEnvironment environment = context.getEnvironment();
                assertThat(environment.getActiveProfiles()).containsExactlyInAnyOrder("kubernetes", "e2");
                new SynapseEnvironmentPostProcessor(() -> true).postProcessEnvironment(environment, application);
            }
        }
    }

    /**
     * In AOT mode the generated {@code <Main>__EnvironmentPostProcessor} has added the profiles that were active during
     * AOT processing; {@link BakedApplication} has one, as an application processed with {@code profiles} would.
     */
    @Test
    void inAotModeABakedProfileFailsStartup() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service",
                        Map.of("application.yaml", "fixture.greeting: hello\n", "application-e2.yaml", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            SpringApplication application = ConfigFixtures.application(classLoader, Map.of());
            application.setMainApplicationClass(BakedApplication.class);
            try (ConfigurableApplicationContext context = application.run("--spring.profiles.active=e2")) {
                ConfigurableEnvironment environment = context.getEnvironment();
                new SynapseEnvironmentPostProcessor(() -> false).postProcessEnvironment(environment, application);
                assertThatThrownBy(() -> new SynapseEnvironmentPostProcessor(() -> true)
                        .postProcessEnvironment(environment, application))
                        .isInstanceOfSatisfying(SynapseConfigurationException.class,
                                ex -> assertThat(ex.description())
                                        .startsWith("The AOT-generated code activates the profiles build, kafka at "
                                                + "every run"));
            }
        }
    }

    @Test
    void rerunningReplacesTheModuleSources() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service",
                        Map.of("application.yaml", "fixture.greeting: hello\n", "application-e2.yaml", ""));
        Path client = ConfigFixtures
                .jar(dir, "client-accounts-1.4.0.jar", Map
                        .of("META-INF/synapse/config.yaml", "fixture.module: base\n",
                                "META-INF/synapse/config-e2.yaml", "fixture.module: e2\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service, client)) {
            SpringApplication application = ConfigFixtures.application(classLoader, Map.of());
            try (ConfigurableApplicationContext context = application.run("--spring.profiles.active=e2")) {
                ConfigurableEnvironment environment = context.getEnvironment();
                SynapseEnvironmentPostProcessor postProcessor = new SynapseEnvironmentPostProcessor();
                postProcessor.postProcessEnvironment(environment, application);
                postProcessor.postProcessEnvironment(environment, application);
                assertThat(environment.getPropertySources().stream().map(PropertySource::getName))
                        .filteredOn(name -> name.startsWith(ModuleConfigFiles.SOURCE_PREFIX))
                        .containsExactly("Synapse module config [config-e2.yaml] client-accounts-1.4.0.jar",
                                "Synapse module config [config.yaml] client-accounts-1.4.0.jar");
                assertThat(environment.getProperty("fixture.module")).isEqualTo("e2");
            }
        }
    }

    @Test
    void checksNamesOnlyInAotMode() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service",
                        Map.of("application.yaml", "fixture.greeting: hello\n", "application-e2.yaml", ""));
        Path client = ConfigFixtures
                .jar(dir, "client-accounts-1.4.0.jar", Map
                        .of("META-INF/synapse/config.yaml", "synapse.client.accounts.base-url: http://accounts\n",
                                "META-INF/synapse/config-e2.yaml", "synapse.client.payments.base-url: http://pay\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service, client)) {
            SpringApplication application = ConfigFixtures.application(classLoader, Map.of());
            try (ConfigurableApplicationContext context = application.run("--spring.profiles.active=e2")) {
                ConfigurableEnvironment environment = context.getEnvironment();
                assertThatThrownBy(() -> new SynapseEnvironmentPostProcessor(() -> true)
                        .postProcessEnvironment(environment, application))
                        .isInstanceOfSatisfying(SynapseConfigurationException.class,
                                ex -> assertThat(ex.description())
                                        .startsWith("config-e2.yaml (client-accounts-1.4.0.jar) adds the client "
                                                + "'payments'"));
                new SynapseEnvironmentPostProcessor(() -> false).postProcessEnvironment(environment, application);
                assertThat(environment.getProperty("synapse.client.payments.base-url")).isEqualTo("http://pay");
            }
        }
    }
}
