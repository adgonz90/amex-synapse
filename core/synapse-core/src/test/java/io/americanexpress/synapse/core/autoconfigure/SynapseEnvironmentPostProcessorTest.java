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
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import io.americanexpress.synapse.core.SynapseConfigurationException;
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
