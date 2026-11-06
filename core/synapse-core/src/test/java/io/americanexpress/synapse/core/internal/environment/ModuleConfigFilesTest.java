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
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleConfigFilesTest {

    private static final String CLIENT = "client-accounts-1.4.0.jar";

    private static final String DATA = "data-books-1.4.0.jar";

    private static final String ROGUE = "rogue-1.0.jar";

    private static final String COLLISION_ACTION = "Set it in one module only. The service's application files can "
            + "override any module value.";

    private static final Map<String, Object> ENV = Map.of("ENV", "e2");

    private static final List<String> APPLICATION = List.of("application");

    @TempDir
    Path dir;

    @Test
    void precedenceFromModuleDefaultsToTheCommandLine() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map
                        .of("application.yaml", """
                                spring.profiles.active: ${ENV}
                                fixture:
                                  c: service
                                  d: service
                                """, "application-e2.yaml", """
                                fixture:
                                  d: service e2
                                  e: service e2
                                """));
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.yaml", "fixture:\n  a: module\n  b: module\n",
                                "META-INF/synapse/config-e2.yaml", "fixture:\n  b: module e2\n  c: module e2\n"));
        Path data = ConfigFixtures.jar(dir, DATA, Map.of("META-INF/synapse/config.yaml", "fixture.g: module\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service, client, data)) {
            SpringApplication application = ConfigFixtures
                    .application(classLoader, Map.of("ENV", "e2", "FIXTURE_E", "env", "FIXTURE_F", "env"));
            application.setDefaultProperties(Map.of("fixture.g", "default"));
            try (ConfigurableApplicationContext context = application.run("--fixture.f=cmdline")) {
                ConfigurableEnvironment environment = context.getEnvironment();
                assertThat(environment.getProperty("fixture.a")).isEqualTo("module");
                assertThat(environment.getProperty("fixture.b")).isEqualTo("module e2");
                assertThat(environment.getProperty("fixture.c")).isEqualTo("service");
                assertThat(environment.getProperty("fixture.d")).isEqualTo("service e2");
                assertThat(environment.getProperty("fixture.e")).isEqualTo("env");
                assertThat(environment.getProperty("fixture.f")).isEqualTo("cmdline");
                assertThat(environment.getProperty("fixture.g")).isEqualTo("module");
            }
        }
    }

    @Test
    void sourcesSitBetweenConfigDataAndDefaults() throws IOException {
        Path service = service();
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.yaml", "fixture.client: base\n", "META-INF/synapse/config-e2.yaml",
                                "fixture.client-e2: e2\n"));
        Path data = ConfigFixtures
                .jar(dir, DATA, Map
                        .of("META-INF/synapse/config.yaml", "fixture.data: base\n", "META-INF/synapse/config-e2.yaml",
                                "fixture.data-e2: e2\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service, client, data)) {
            SpringApplication application = ConfigFixtures.application(classLoader, ENV);
            application.setDefaultProperties(Map.of("fixture.default", "default"));
            try (ConfigurableApplicationContext context = application.run()) {
                // synapseBaggageFields, logCorrelation and applicationInfo are added later, by other post-processors.
                assertThat(names(context.getEnvironment()))
                        .containsExactly("configurationProperties", "synapseBaggageFields", "systemProperties",
                                "systemEnvironment", "random",
                                "Config resource 'class path resource [application-e2.yaml]' via location "
                                        + "'optional:classpath:/'",
                                "Config resource 'class path resource [application.yaml]' via location "
                                        + "'optional:classpath:/'",
                                "Synapse module config [config-e2.yaml] client-accounts-1.4.0.jar",
                                "Synapse module config [config-e2.yaml] data-books-1.4.0.jar",
                                "Synapse module config [config.yaml] client-accounts-1.4.0.jar",
                                "Synapse module config [config.yaml] data-books-1.4.0.jar", "logCorrelation",
                                "applicationInfo", "defaultProperties");
            }
        }
    }

    @Test
    void readsPropertiesFiles() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.properties", "fixture.a=base\nfixture.b=base\n",
                                "META-INF/synapse/config-e2.properties", "fixture.b=e2\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client);
                ConfigurableApplicationContext context = ConfigFixtures.application(classLoader, ENV).run()) {
            assertThat(context.getEnvironment().getProperty("fixture.a")).isEqualTo("base");
            assertThat(context.getEnvironment().getProperty("fixture.b")).isEqualTo("e2");
            assertThat(names(context.getEnvironment()))
                    .contains("Synapse module config [config-e2.properties] client-accounts-1.4.0.jar",
                            "Synapse module config [config.properties] client-accounts-1.4.0.jar");
        }
    }

    @Test
    void ymlModuleFilesAreNotRead() throws IOException {
        Path client = ConfigFixtures.jar(dir, CLIENT, Map.of("META-INF/synapse/config.yml", "fixture.a: yml\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client);
                ConfigurableApplicationContext context = ConfigFixtures.application(classLoader, ENV).run()) {
            assertThat(context.getEnvironment().getProperty("fixture.a")).isNull();
        }
    }

    @Test
    void otherEnvironmentsFilesAreIgnored() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.yaml", "fixture.a: base\n", "META-INF/synapse/config-e1.yaml",
                                "fixture.a: e1\nfixture.b: e1\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client);
                ConfigurableApplicationContext context = ConfigFixtures.application(classLoader, ENV).run()) {
            assertThat(context.getEnvironment().getProperty("fixture.a")).isEqualTo("base");
            assertThat(context.getEnvironment().getProperty("fixture.b")).isNull();
            assertThat(names(context.getEnvironment())).noneMatch(name -> name.contains("config-e1"));
        }
    }

    @Test
    void sameKeyInTwoModulesFails() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT,
                        Map.of("META-INF/synapse/config.yaml", "synapse.client.accounts.base-url: http://accounts\n"));
        Path rogue = ConfigFixtures
                .jar(dir, ROGUE, Map
                        .of("META-INF/synapse/config.yaml",
                                "synapse:\n  client:\n    accounts:\n      baseUrl: http://rogue\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client, rogue)) {
            assertThatThrownBy(() -> ConfigFixtures.application(classLoader, ENV).run())
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("synapse.client.accounts.base-url is set by two modules: "
                                        + "client-accounts-1.4.0.jar (config.yaml) and rogue-1.0.jar (config.yaml).");
                        assertThat(ex.action()).isEqualTo(COLLISION_ACTION);
                    });
        }
    }

    @Test
    void anEnvironmentFileCollidesWithAnotherModulesBaseFile() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map.of("META-INF/synapse/config.yaml", "synapse.client.accounts.timeout: 5s\n"));
        Path rogue = ConfigFixtures
                .jar(dir, ROGUE,
                        Map.of("META-INF/synapse/config-e2.properties", "synapse.client.accounts.timeout=9s\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client, rogue)) {
            assertThatThrownBy(() -> ConfigFixtures.application(classLoader, ENV).run())
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .isEqualTo("synapse.client.accounts.timeout is set by two modules: "
                                    + "client-accounts-1.4.0.jar (config.yaml) and rogue-1.0.jar "
                                    + "(config-e2.properties)."));
        }
    }

    @Test
    void listIndexesCollideOnTheList() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.yaml",
                                "synapse.client.accounts.allowed-hosts[0]: accounts.example\n"));
        Path rogue = ConfigFixtures
                .jar(dir, ROGUE, Map
                        .of("META-INF/synapse/config.yaml",
                                "synapse.client.accounts.allowed-hosts[1]: rogue.example\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client, rogue)) {
            assertThatThrownBy(() -> ConfigFixtures.application(classLoader, ENV).run())
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .isEqualTo("synapse.client.accounts.allowed-hosts is set by two modules: "
                                    + "client-accounts-1.4.0.jar (config.yaml) and rogue-1.0.jar (config.yaml)."));
        }
        Path commaList = ConfigFixtures
                .jar(dir, DATA, Map
                        .of("META-INF/synapse/config.yaml",
                                "synapse.client.accounts.allowed-hosts: a.example,b.example\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(commaList, rogue)) {
            assertThatThrownBy(() -> ModuleConfigFiles.load(classLoader, "e2", APPLICATION))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .isEqualTo("synapse.client.accounts.allowed-hosts is set by two modules: "
                                    + "data-books-1.4.0.jar (config.yaml) and rogue-1.0.jar (config.yaml)."));
        }
    }

    @Test
    void aModuleMayOverrideItsOwnConfigInItsEnvironmentFile() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.yaml", "synapse.client.accounts.base-url: http://base\n",
                                "META-INF/synapse/config-e2.yaml", "synapse.client.accounts.baseUrl: http://e2\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client);
                ConfigurableApplicationContext context = ConfigFixtures.application(classLoader, ENV).run()) {
            assertThat(context.getEnvironment().getProperty("synapse.client.accounts.base-url"))
                    .isEqualTo("http://e2");
        }
    }

    @Test
    void laterDocumentsWinAsInBoot() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map
                        .of("application.yaml", "fixture.service: first\n---\nfixture.service: second\n",
                                "application-e2.yaml", ""));
        Path client = ConfigFixtures
                .jar(dir, CLIENT,
                        Map.of("META-INF/synapse/config.yaml", "fixture.module: first\n---\nfixture.module: second\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service, client);
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader, Map.of())
                        .run("--spring.profiles.active=e2")) {
            assertThat(context.getEnvironment().getProperty("fixture.service")).isEqualTo("second");
            assertThat(context.getEnvironment().getProperty("fixture.module")).isEqualTo("second");
            assertThat(names(context.getEnvironment()))
                    .containsSubsequence(
                            "Config resource 'class path resource [application.yaml]' via location "
                                    + "'optional:classpath:/' (document #1)",
                            "Config resource 'class path resource [application.yaml]' via location "
                                    + "'optional:classpath:/' (document #0)",
                            "Synapse module config [config.yaml] client-accounts-1.4.0.jar (document #1)",
                            "Synapse module config [config.yaml] client-accounts-1.4.0.jar (document #0)");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.profiles.active", "spring.profiles.include", "spring.config.import",
            "spring.config.activate.on-profile", "synapse.environment.anything"})
    void moduleFilesMayNotDecideTheRun(String key) throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map.of("META-INF/synapse/config.yaml", "fixture.a: base\n" + key + ": e5\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client)) {
            assertThatThrownBy(() -> ConfigFixtures.application(classLoader, ENV).run())
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("client-accounts-1.4.0.jar (config.yaml) sets " + key + ". Module files may "
                                        + "not set spring.profiles.*, spring.config.* or synapse.environment.*.");
                        assertThat(ex.action()).isEqualTo("Remove it from the module; the service decides the run.");
                    });
        }
    }

    @Test
    void forbiddenKeysAreFoundInAnyForm() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.properties", "fixture.a=base\n",
                                "META-INF/synapse/config-e2.yaml",
                                "spring:\n  profiles:\n    include:\n      - kafka\n"));
        Path rogue = ConfigFixtures.jar(dir, ROGUE, Map.of("META-INF/synapse/config.properties", "spring.config=x\n"));
        Path data = ConfigFixtures
                .jar(dir, DATA,
                        Map.of("META-INF/synapse/config.yaml", "fixture.a: base\n---\nsynapse.environment: x\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client)) {
            assertThatThrownBy(() -> ConfigFixtures.application(classLoader, ENV).run())
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .startsWith("client-accounts-1.4.0.jar (config-e2.yaml) sets spring.profiles.include. "));
        }
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(rogue)) {
            assertThatThrownBy(() -> ModuleConfigFiles.load(classLoader, "e2", APPLICATION))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .startsWith("rogue-1.0.jar (config.properties) sets spring.config. "));
        }
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(data)) {
            assertThatThrownBy(() -> ModuleConfigFiles.load(classLoader, "e2", APPLICATION))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .startsWith("data-books-1.4.0.jar (config.yaml (document #1)) sets synapse.environment. "));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yaml", "application.properties", "application-e2.yml"})
    void aModuleWithARootApplicationFileFails(String file) throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map.of("META-INF/synapse/config.yaml", "fixture.a: base\n", file, ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client)) {
            assertThatThrownBy(() -> ConfigFixtures.application(classLoader, ENV).run())
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("client-accounts-1.4.0.jar ships META-INF/synapse/ and also " + file
                                        + " at its root, which can hide the service's own file.");
                        assertThat(ex.action())
                                .isEqualTo("Move the module's settings to META-INF/synapse/config.yaml or "
                                        + "config-<env>.yaml and remove " + file + ".");
                    });
        }
    }

    @Test
    void theRootFileCheckUsesTheConfigName() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config-e2.yaml", "fixture.a: e2\n", "application.yaml", "",
                                "books-e2.properties", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(client)) {
            assertThatThrownBy(() -> ModuleConfigFiles.load(classLoader, "e2", List.of("books")))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .isEqualTo("client-accounts-1.4.0.jar ships META-INF/synapse/ and also "
                                    + "books-e2.properties at its root, which can hide the service's own file."));
            assertThat(ModuleConfigFiles.load(classLoader, "e5", List.of("books"))).isEmpty();
        }
    }

    @Test
    void aRootFileWithoutModuleFilesIsTheServicesOwn() throws IOException {
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service())) {
            assertThat(ModuleConfigFiles.load(classLoader, "e2", APPLICATION)).isEmpty();
        }
    }

    @Test
    void sameFileInBothFormatsFails() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.yaml", "fixture.a: yaml\n", "META-INF/synapse/config.properties",
                                "fixture.b=properties\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client)) {
            assertThatThrownBy(() -> ConfigFixtures.application(classLoader, ENV).run())
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("client-accounts-1.4.0.jar ships both config.yaml and config.properties.");
                        assertThat(ex.action()).isEqualTo("Keep one of them.");
                    });
        }
    }

    @Test
    void sameEnvironmentFileInBothFormatsFails() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config-e2.yaml", "fixture.a: yaml\n",
                                "META-INF/synapse/config-e2.properties", "fixture.b=properties\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(client)) {
            assertThatThrownBy(() -> ModuleConfigFiles.load(classLoader, "e2", APPLICATION))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .isEqualTo("client-accounts-1.4.0.jar ships both config-e2.yaml and "
                                    + "config-e2.properties."));
        }
    }

    @Test
    void aModuleDirectoryWorksLikeAJar() throws IOException {
        Path client = ConfigFixtures
                .directory(dir, "client-accounts/target/classes", Map
                        .of("META-INF/synapse/config.yaml", "fixture.a: base\n", "META-INF/synapse/config-e2.yaml",
                                "fixture.b: e2\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client);
                ConfigurableApplicationContext context = ConfigFixtures.application(classLoader, ENV).run()) {
            assertThat(context.getEnvironment().getProperty("fixture.a")).isEqualTo("base");
            assertThat(context.getEnvironment().getProperty("fixture.b")).isEqualTo("e2");
            assertThat(names(context.getEnvironment()))
                    .contains("Synapse module config [config-e2.yaml] client-accounts",
                            "Synapse module config [config.yaml] client-accounts");
        }
    }

    @Test
    void modulesWithTheSameOriginAreNamedByTheirLocation() throws IOException {
        Path first = ConfigFixtures
                .directory(dir, "a/client/target/classes", Map.of("META-INF/synapse/config.yaml", "fixture.a: a\n"));
        Path second = ConfigFixtures
                .directory(dir, "b/client/target/classes", Map.of("META-INF/synapse/config.yaml", "fixture.b: b\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), first, second);
                ConfigurableApplicationContext context = ConfigFixtures.application(classLoader, ENV).run()) {
            assertThat(context.getEnvironment().getProperty("fixture.a")).isEqualTo("a");
            assertThat(context.getEnvironment().getProperty("fixture.b")).isEqualTo("b");
            List<String> moduleSources = names(context.getEnvironment())
                    .stream()
                    .filter(name -> name.startsWith(ModuleConfigFiles.SOURCE_PREFIX))
                    .toList();
            assertThat(moduleSources).hasSize(2);
            assertThat(moduleSources.get(0)).endsWith("/a/client/target/classes");
            assertThat(moduleSources.get(1)).endsWith("/b/client/target/classes");
        }
    }

    @Test
    void rerunningReplacesTheModuleSources() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.yaml", "fixture.a: base\n", "META-INF/synapse/config-e2.yaml",
                                "fixture.b: e2\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client);
                ConfigurableApplicationContext context = ConfigFixtures.application(classLoader, ENV).run()) {
            MutablePropertySources sources = context.getEnvironment().getPropertySources();
            ModuleConfigFiles.addTo(sources, ModuleConfigFiles.load(classLoader, "e2", APPLICATION));
            ModuleConfigFiles.addTo(sources, ModuleConfigFiles.load(classLoader, "e2", APPLICATION));
            assertThat(names(context.getEnvironment())
                    .stream()
                    .filter(name -> name.startsWith(ModuleConfigFiles.SOURCE_PREFIX))
                    .toList())
                    .containsExactly("Synapse module config [config-e2.yaml] client-accounts-1.4.0.jar",
                            "Synapse module config [config.yaml] client-accounts-1.4.0.jar");
        }
    }

    @Test
    void withoutConfigDataTheSourcesGoBeforeDefaults() {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("commandLineArgs", Map.of()));
        sources.addLast(new MapPropertySource("defaultProperties", Map.of()));
        sources.addLast(new MapPropertySource(ModuleConfigFiles.SOURCE_PREFIX + "[config.yaml] stale.jar", Map.of()));
        ModuleConfigFiles
                .addTo(sources, List.of(moduleSource("[config-e2.yaml] a.jar"), moduleSource("[config.yaml] a.jar")));
        assertThat(sources.stream().map(PropertySource::getName))
                .containsExactly("commandLineArgs", ModuleConfigFiles.SOURCE_PREFIX + "[config-e2.yaml] a.jar",
                        ModuleConfigFiles.SOURCE_PREFIX + "[config.yaml] a.jar", "defaultProperties");
    }

    @Test
    void withoutConfigDataOrDefaultsTheSourcesGoLast() {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("commandLineArgs", Map.of()));
        ModuleConfigFiles
                .addTo(sources, List.of(moduleSource("[config-e2.yaml] a.jar"), moduleSource("[config.yaml] a.jar")));
        assertThat(sources.stream().map(PropertySource::getName))
                .containsExactly("commandLineArgs", ModuleConfigFiles.SOURCE_PREFIX + "[config-e2.yaml] a.jar",
                        ModuleConfigFiles.SOURCE_PREFIX + "[config.yaml] a.jar");
    }

    @Test
    void tellsEnvironmentSourcesApart() {
        assertThat(ModuleConfigFiles.isEnvironmentSource("Synapse module config [config-e2.yaml] a.jar")).isTrue();
        assertThat(
                ModuleConfigFiles.isEnvironmentSource("Synapse module config [config-e2.properties] a (document #1)"))
                .isTrue();
        assertThat(ModuleConfigFiles.isEnvironmentSource("Synapse module config [config.yaml] a.jar")).isFalse();
        assertThat(ModuleConfigFiles
                .isEnvironmentSource("Config resource 'class path resource [config-e2.yaml]' via location 'x'"))
                .isFalse();
    }

    @Test
    void noModuleFilesAddNothing() throws IOException {
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service());
                ConfigurableApplicationContext context = ConfigFixtures.application(classLoader, ENV).run()) {
            assertThat(names(context.getEnvironment()))
                    .noneMatch(name -> name.startsWith(ModuleConfigFiles.SOURCE_PREFIX));
            assertThat(ModuleConfigFiles.load(classLoader, "e2", APPLICATION)).isEmpty();
        }
    }

    @Test
    void aFileThatCannotBeParsedFailsWithAConfigurationException() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map.of("META-INF/synapse/config-e2.yaml", "fixture: [s3cr3t-unclosed\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client)) {
            assertThatThrownBy(() -> ConfigFixtures.application(classLoader, ENV).run())
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("Synapse could not read config-e2.yaml (client-accounts-1.4.0.jar).");
                        assertThat(ex.action())
                                .isEqualTo("Check that the file is readable and valid YAML or properties.");
                        // The cause keeps only the parser failure's type and position, never the file's text.
                        assertThat(ex.getCause()).hasMessage("ParserException at line 2, column 1").hasNoCause();
                        assertThat(ex.getCause()).asString().doesNotContain("s3cr3t");
                        assertThat(ex.getMessage()).doesNotContain("s3cr3t");
                    });
        }
    }

    @Test
    void moduleJarsAreLeftUnlocked() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.yaml", "fixture.a: base\n", "META-INF/synapse/config-e2.yaml",
                                "fixture.b: e2\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client);
                ConfigurableApplicationContext context = ConfigFixtures.application(classLoader, ENV).run()) {
            assertThat(context.getEnvironment().getProperty("fixture.b")).isEqualTo("e2");
        }
        Files.delete(client);
        assertThat(client).doesNotExist();
    }

    @Test
    void aClasspathThatCannotBeSearchedFailsWithTheLookupFailuresTypeAsCause() {
        IOException lookupFailure = new IOException("Not searched in this test");
        ClassLoader classLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                throw lookupFailure;
            }
        };
        assertThatThrownBy(() -> ModuleConfigFiles.load(classLoader, "e2", APPLICATION))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo("Synapse could not look up META-INF/synapse/config.yaml on the classpath.");
                    assertThat(ex.action()).isEqualTo("Check that the classpath's jars and directories are readable.");
                    // The cause keeps only the lookup failure's type and stack, never its message.
                    assertThat(ex.getCause()).hasMessage("IOException").hasNoCause();
                    assertThat(ex.getCause())
                            .extracting(Throwable::getStackTrace)
                            .isEqualTo(lookupFailure.getStackTrace());
                });
    }

    @ParameterizedTest
    @CsvSource({"http://127.0.0.1:1/META-INF/synapse/config.yaml, http://127.0.0.1:1",
            "jar:http://127.0.0.1:1/remote.jar!/META-INF/synapse/config.yaml, remote.jar"})
    void aRemoteModuleFileFailsWithoutBeingFetched(String url, String origin) throws IOException {
        AtomicInteger opened = new AtomicInteger();
        URL remote = URL.of(URI.create(url), new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) throws IOException {
                opened.incrementAndGet();
                throw new IOException("Not opened in this test");
            }
        });
        ClassLoader classLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                List<URL> urls = new ArrayList<>(Collections.list(super.getResources(name)));
                if (name.equals("META-INF/synapse/config.yaml")) {
                    urls.add(remote);
                }
                return Collections.enumeration(urls);
            }
        };
        assertThatThrownBy(() -> ModuleConfigFiles.load(classLoader, "e2", APPLICATION))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo(
                                    origin + " (config.yaml) is not a local file or jar, so Synapse cannot read it.");
                    assertThat(ex.action()).isEqualTo("Put the module on the classpath as a jar or directory.");
                });
        assertThat(opened).hasValue(0);
    }

    @Test
    void bracketedMapKeysInTwoModulesDoNotCollide() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map.of("META-INF/synapse/config.properties", "fixture.levels[com.a]=DEBUG\n"));
        Path rogue = ConfigFixtures
                .jar(dir, ROGUE, Map.of("META-INF/synapse/config.properties", "fixture.levels[com.b]=WARN\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service(), client, rogue);
                ConfigurableApplicationContext context = ConfigFixtures.application(classLoader, ENV).run()) {
            assertThat(Binder
                    .get(context.getEnvironment())
                    .bind("fixture.levels", Bindable.mapOf(String.class, String.class))
                    .get()).containsExactlyInAnyOrderEntriesOf(Map.of("com.a", "DEBUG", "com.b", "WARN"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring[profiles].active", "[spring].profiles.active", "spring[config].import",
            "synapse[environment].x"})
    void bracketedForbiddenKeysAreRejected(String key) throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map.of("META-INF/synapse/config.properties", "fixture.a=base\n" + key + "=e5\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(client)) {
            assertThatThrownBy(() -> ModuleConfigFiles.load(classLoader, "e2", APPLICATION))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("client-accounts-1.4.0.jar (config.properties) sets " + key + ". Module "
                                        + "files may not set spring.profiles.*, spring.config.* or "
                                        + "synapse.environment.*.");
                        assertThat(ex.action()).isEqualTo("Remove it from the module; the service decides the run.");
                    });
        }
    }

    @Test
    void aCamelCaseKeyIsNamedInItsDashedForm() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT,
                        Map.of("META-INF/synapse/config.yaml", "synapse.client.accounts.baseUrl: http://accounts\n"));
        Path rogue = ConfigFixtures
                .jar(dir, ROGUE,
                        Map.of("META-INF/synapse/config.yaml", "synapse.client.accounts.base-url: http://r\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(client, rogue)) {
            assertThatThrownBy(() -> ModuleConfigFiles.load(classLoader, "e2", APPLICATION))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .isEqualTo("synapse.client.accounts.base-url is set by two modules: "
                                    + "client-accounts-1.4.0.jar (config.yaml) and rogue-1.0.jar (config.yaml)."));
        }
        Path forbidden = ConfigFixtures
                .jar(dir, DATA, Map.of("META-INF/synapse/config.yaml", "spring.profiles.includeMe: x\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(forbidden)) {
            assertThatThrownBy(() -> ModuleConfigFiles.load(classLoader, "e2", APPLICATION))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .startsWith("data-books-1.4.0.jar (config.yaml) sets spring.profiles.include-me. "));
        }
    }

    @Test
    void rootFileAndBothFormatsMessagesUseTheUniqueOrigin() throws IOException {
        Path first = ConfigFixtures
                .directory(dir, "a/client/target/classes",
                        Map.of("META-INF/synapse/config.yaml", "fixture.a: a\n", "application.yaml", ""));
        Path second = ConfigFixtures
                .directory(dir, "b/client/target/classes", Map
                        .of("META-INF/synapse/config.yaml", "fixture.b: b\n", "META-INF/synapse/config.properties",
                                "fixture.c=c\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(first, second)) {
            assertThatThrownBy(() -> ModuleConfigFiles.load(classLoader, "e2", APPLICATION))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .endsWith("/a/client/target/classes ships META-INF/synapse/ and also application.yaml at "
                                    + "its root, which can hide the service's own file."));
        }
        try (URLClassLoader classLoader = ConfigFixtures
                .classLoader(second, ConfigFixtures
                        .directory(dir, "c/client/target/classes",
                                Map.of("META-INF/synapse/config.yaml", "fixture.d: d\n")))) {
            assertThatThrownBy(() -> ModuleConfigFiles.load(classLoader, "e2", APPLICATION))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .endsWith("/b/client/target/classes ships both config.yaml and config.properties."));
        }
    }

    private Path service() {
        return ConfigFixtures
                .directory(dir, "service", Map
                        .of("application.yaml", "spring.profiles.active: ${ENV}\nfixture.service: base\n",
                                "application-e2.yaml", "fixture.service: e2\n"));
    }

    private static PropertySource<?> moduleSource(String name) {
        return new MapPropertySource(ModuleConfigFiles.SOURCE_PREFIX + name, Map.of());
    }

    private static List<String> names(ConfigurableEnvironment environment) {
        return environment.getPropertySources().stream().map(PropertySource::getName).toList();
    }
}
