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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.UrlResource;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceConfigFilesTest {

    private static final String ON_PROFILE_ACTION = "Move the document's settings to application-<env>.yaml. "
            + "Synapse uses one file per environment and no on-profile documents.";

    @TempDir
    Path dir;

    @Test
    void aLoadedEnvironmentFileMakesTheEnvironmentValid() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addLast(new MapPropertySource(
                        "Config resource 'class path resource [application-e2.yaml]' via location "
                                + "'optional:classpath:/'",
                        Map.of("fixture.greeting", "hello")));
        environment
                .getPropertySources()
                .addLast(new MapPropertySource(
                        "Config resource 'file [ext\\application-e7.yaml]' via location 'optional:file:ext/' "
                                + "(document #1)",
                        Map.of("fixture.greeting", "hello")));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader()) {
            assertThatNoException()
                    .isThrownBy(() -> ServiceConfigFiles.requireEnvironmentFile(environment, "e2", classLoader));
            assertThatNoException()
                    .isThrownBy(() -> ServiceConfigFiles.requireEnvironmentFile(environment, "e7", classLoader));
            assertThatThrownBy(() -> ServiceConfigFiles.requireEnvironmentFile(environment, "e", classLoader))
                    .isInstanceOf(SynapseConfigurationException.class);
        }
    }

    @Test
    void matchesOnlyTheConfigResourceOfTheExactFile() {
        String classpath = "Config resource 'class path resource [application-e2.yaml]' via location "
                + "'optional:classpath:/'";
        assertThat(ServiceConfigFiles.isEnvironmentFile(classpath, "application", "e2")).isTrue();
        assertThat(ServiceConfigFiles
                .isEnvironmentFile("Config resource 'class path resource [config/application-e2.yml]' via location "
                        + "'optional:classpath:/config/'", "application", "e2"))
                .isTrue();
        assertThat(ServiceConfigFiles
                .isEnvironmentFile("Config resource 'file [/srv/application-e2.properties]' via location "
                        + "'optional:file:/srv/' (document #1)", "application", "e2"))
                .isTrue();
        assertThat(ServiceConfigFiles.isEnvironmentFile(classpath, "application", "E2")).isFalse();
        assertThat(ServiceConfigFiles.isEnvironmentFile(classpath, "application", "e")).isFalse();
        assertThat(ServiceConfigFiles.isEnvironmentFile(classpath, "books", "e2")).isFalse();
        assertThat(ServiceConfigFiles
                .isEnvironmentFile("Config resource 'class path resource [myapplication-e2.yaml]' via location "
                        + "'optional:classpath:/'", "application", "e2"))
                .isFalse();
        assertThat(ServiceConfigFiles
                .isEnvironmentFile("Config resource 'class path resource [application-e2.json]' via location "
                        + "'optional:classpath:/'", "application", "e2"))
                .isFalse();
        assertThat(ServiceConfigFiles
                .isEnvironmentFile("class path resource [application-e2.yaml]", "application",
                        "e2"))
                .isFalse();
        assertThat(ServiceConfigFiles
                .isBaseFile("Config resource 'class path resource [application.yaml]' via location "
                        + "'optional:classpath:/'", "application"))
                .isTrue();
        assertThat(ServiceConfigFiles.isBaseFile(classpath, "application")).isFalse();
    }

    @Test
    void configNamesDefaultToApplication() {
        StandardEnvironment environment = new StandardEnvironment();
        assertThat(ServiceConfigFiles.configNames(environment)).containsExactly("application");
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("names", Map.of("spring.config.name", "books, shared")));
        assertThat(ServiceConfigFiles.configNames(environment)).containsExactly("books", "shared");
    }

    @Test
    void anEmptyFileOnTheClasspathCounts() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map
                        .of("application.yaml", "fixture.greeting: hello\n", "application-e3.yaml", "",
                                "application-e4.yaml", "# nothing here yet\n", "application-e8.yaml", "---\n",
                                "application-e6.properties", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            for (String name : List.of("e3", "e4", "e8", "e6")) {
                try (ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader, Map.of())
                        .run("--spring.profiles.active=" + name)) {
                    assertThat(context.getEnvironment().getActiveProfiles()).containsExactly(name);
                    assertThat(context.getEnvironment().getProperty("fixture.greeting")).isEqualTo("hello");
                }
            }
        }
    }

    @Test
    void aConfigDirectoryFileCounts() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service",
                        Map.of("application.yaml", "fixture.greeting: hello\n", "config/application-e2.yaml", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service);
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader, Map.of())
                        .run("--spring.profiles.active=e2")) {
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("e2");
        }
    }

    @Test
    void aFileInAnExternalLocationCounts() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map.of("application.yaml", "fixture.greeting: hello\n"));
        Path external = ConfigFixtures
                .directory(dir, "ext", Map.of("application-e7.yaml", "fixture.greeting: hello e7\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service);
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader, Map.of())
                        .run("--spring.profiles.active=e7", additionalLocation(external))) {
            assertThat(context.getEnvironment().getProperty("fixture.greeting")).isEqualTo("hello e7");
        }
    }

    @Test
    void anEmptyExternalFileDoesNotCount() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map.of("application.yaml", "fixture.greeting: hello\n"));
        Path external = ConfigFixtures.directory(dir, "ext", Map.of("application-e7.yaml", "# nothing here yet\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(() -> ConfigFixtures
                    .application(classLoader, Map.of())
                    .run("--spring.profiles.active=e7", additionalLocation(external)))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("The environment 'e7' has no file in the service: none of "
                                        + "application-e7.yaml, application-e7.yml, application-e7.properties was "
                                        + "loaded or found on the classpath. Environment names are case-sensitive.");
                        assertThat(ex.action())
                                .isEqualTo("Add application-e7.yaml to the service (it may be empty), or set the "
                                        + "environment to the name of an existing file.");
                    });
        }
    }

    @Test
    void aMissingFileFails() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service",
                        Map.of("application.yaml", "fixture.greeting: hello\n", "application-e2.yaml", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(
                    () -> ConfigFixtures.application(classLoader, Map.of()).run("--spring.profiles.active=e9"))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("The environment 'e9' has no file in the service: none of "
                                        + "application-e9.yaml, application-e9.yml, application-e9.properties was "
                                        + "loaded or found on the classpath. Environment names are case-sensitive.");
                        assertThat(ex.action())
                                .isEqualTo("Add application-e9.yaml to the service (it may be empty), or set the "
                                        + "environment to the name of an existing file.");
                    });
        }
    }

    @Test
    void anyConfigNameCanHoldTheEnvironmentFile() throws IOException {
        Path service = ConfigFixtures.directory(dir, "service", Map.of("shared-e2.yaml", ""));
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("names", Map.of("spring.config.name", "books,shared")));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatNoException()
                    .isThrownBy(() -> ServiceConfigFiles.requireEnvironmentFile(environment, "e2", classLoader));
        }
    }

    @Test
    void wrongCaseInAJarFails() throws IOException {
        Path service = ConfigFixtures
                .jar(dir, "service.jar", Map
                        .of("application.yaml", "spring.profiles.active: ${ENV}\nfixture.greeting: hello\n",
                                "application-e2.yaml", "fixture.greeting: hello e2\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(() -> ConfigFixtures.application(classLoader, Map.of("ENV", "E2")).run())
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .isEqualTo("The environment 'E2' has no file in the service: none of application-E2.yaml, "
                                    + "application-E2.yml, application-E2.properties was loaded or found on the "
                                    + "classpath. Environment names are case-sensitive."));
        }
    }

    @Test
    void onlyAModuleFileDoesNotMakeANameValid() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map.of("application.yaml", "fixture.greeting: hello\n"));
        Path module = ConfigFixtures
                .jar(dir, "client-accounts.jar", Map.of("META-INF/synapse/config-e2.yaml", "fixture.module: e2\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service, module)) {
            assertThatThrownBy(
                    () -> ConfigFixtures.application(classLoader, Map.of()).run("--spring.profiles.active=e2"))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .startsWith("The environment 'e2' has no file in the service:"));
        }
    }

    @Test
    void springConfigNameIsHonoured() throws IOException {
        Path books = ConfigFixtures
                .directory(dir, "books",
                        Map.of("books.yaml", "fixture.greeting: hello\n", "books-e2.yaml", "fixture.greeting: e2\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(books);
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader, Map.of())
                        .run("--spring.config.name=books", "--spring.profiles.active=e2")) {
            assertThat(context.getEnvironment().getProperty("fixture.greeting")).isEqualTo("e2");
        }
        Path service = ConfigFixtures
                .directory(dir, "service",
                        Map.of("application.yaml", "fixture.greeting: hello\n", "application-e2.yaml", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(() -> ConfigFixtures
                    .application(classLoader, Map.of())
                    .run("--spring.config.name=books", "--spring.profiles.active=e2"))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("The environment 'e2' has no file in the service: none of books-e2.yaml, "
                                        + "books-e2.yml, books-e2.properties was loaded or found on the classpath. "
                                        + "Environment names are case-sensitive.");
                        assertThat(ex.action())
                                .isEqualTo("Add books-e2.yaml to the service (it may be empty), or set the "
                                        + "environment to the name of an existing file.");
                    });
        }
    }

    @Test
    void anInactiveOnProfileDocumentFails() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map
                        .of("application.yaml", onProfileYaml(), "application-e2.yaml", "", "application-e5.yaml",
                                ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(
                    () -> ConfigFixtures.application(classLoader, Map.of()).run("--spring.profiles.active=e2"))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("application.yaml (document #1) sets spring.config.activate.on-profile.");
                        assertThat(ex.action()).isEqualTo(ON_PROFILE_ACTION);
                    });
        }
    }

    @Test
    void anActiveOnProfileDocumentFails() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map.of("application.yaml", onProfileYaml(), "application-e5.yaml", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(
                    () -> ConfigFixtures.application(classLoader, Map.of()).run("--spring.profiles.active=e5"))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("application.yaml (document #1) sets spring.config.activate.on-profile.");
                        assertThat(ex.action()).isEqualTo(ON_PROFILE_ACTION);
                    });
        }
    }

    @Test
    void anOnProfileDocumentInAnExternalFileFails() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service",
                        Map.of("application.yaml", "fixture.greeting: hello\n", "application-e2.yaml", ""));
        Path external = ConfigFixtures
                .directory(dir, "ext", Map
                        .of("application.properties", """
                                fixture.greeting=ext
                                #---
                                spring.config.activate.on-profile=e5
                                fixture.greeting=ext e5
                                """));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(() -> ConfigFixtures
                    .application(classLoader, Map.of())
                    .run("--spring.profiles.active=e2", additionalLocation(external)))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("application.properties (document #1) sets "
                                        + "spring.config.activate.on-profile.");
                        assertThat(ex.action()).isEqualTo(ON_PROFILE_ACTION);
                    });
        }
    }

    @Test
    void aFileWhoseOnlyDocumentIsInactiveFails() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map
                        .of("config/application.yml", "spring.config.activate.on-profile: e5\nfixture.greeting: e5\n",
                                "application-e2.yaml", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(
                    () -> ConfigFixtures.application(classLoader, Map.of()).run("--spring.profiles.active=e2"))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .isEqualTo("application.yml sets spring.config.activate.on-profile."));
        }
    }

    @Test
    void onProfileIsFoundInAnyForm() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map
                        .of("books.yaml", "spring:\n  config:\n    activate:\n      onProfile: e5\n",
                                "books-e2.yaml", "", "shared.yaml",
                                "spring.config.activate.on-profile:\n  - e5\n  - e6\n"));
        StandardEnvironment camelCase = new StandardEnvironment();
        camelCase
                .getPropertySources()
                .addFirst(new MapPropertySource("names", Map.of("spring.config.name", "books")));
        StandardEnvironment list = new StandardEnvironment();
        list.getPropertySources().addFirst(new MapPropertySource("names", Map.of("spring.config.name", "shared")));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(() -> ServiceConfigFiles.rejectOnProfileDocuments(camelCase, "e2", classLoader))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description()).isEqualTo("books.yaml sets spring.config.activate.on-profile.");
                        assertThat(ex.action())
                                .isEqualTo("Move the document's settings to books-<env>.yaml. Synapse uses one file "
                                        + "per environment and no on-profile documents.");
                    });
            assertThatThrownBy(() -> ServiceConfigFiles.rejectOnProfileDocuments(list, "e2", classLoader))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .isEqualTo("shared.yaml sets spring.config.activate.on-profile."));
        }
    }

    @Test
    void aFileOutsideBootsNamingIsNamedByItsOwnName() throws IOException {
        Path service = ConfigFixtures.directory(dir, "service", Map.of("application-e2.yaml", ""));
        Path external = ConfigFixtures.directory(dir, "ext", Map.of("custom.yaml", onProfileYaml()));
        String location = external.resolve("custom.yaml").toString().replace('\\', '/');
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(() -> ConfigFixtures
                    .application(classLoader, Map.of())
                    .run("--spring.profiles.active=e2", "--spring.config.additional-location=file:" + location))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("custom.yaml (document #1) sets spring.config.activate.on-profile.");
                        assertThat(ex.action())
                                .isEqualTo("Move the document's settings to custom-<env>.yaml. Synapse uses one file "
                                        + "per environment and no on-profile documents.");
                    });
        }
    }

    @Test
    void anOnProfileDocumentInAServiceJarFailsAndLeavesTheJarUnlocked() throws IOException {
        Path service = ConfigFixtures
                .jar(dir, "service.jar", Map.of("application.yaml", onProfileYaml(), "application-e2.yaml", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(
                    () -> ConfigFixtures.application(classLoader, Map.of()).run("--spring.profiles.active=e2"))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .isEqualTo("application.yaml (document #1) sets spring.config.activate.on-profile."));
        }
        Files.delete(service);
        assertThat(service).doesNotExist();
    }

    /**
     * A {@code jar:} URL over a remote jar is remote too: opening it would download the jar. The URL's handler counts
     * every connection and never connects, so the test does no network I/O.
     */
    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:1/application.yaml", "jar:http://127.0.0.1:1/x.jar!/application.yaml",
            "jar:https://127.0.0.1:1/x.jar!/application.yaml"})
    void aRemoteConfigResourceIsNotOpened(String url) throws IOException {
        AtomicInteger opened = new AtomicInteger();
        UrlResource remote = new UrlResource(URL.of(URI.create(url), new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) throws IOException {
                opened.incrementAndGet();
                throw new IOException("Not opened in this test");
            }
        }));
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addLast(new OriginTrackedMapPropertySource(
                        "Config resource 'URL [" + url + "]' via location '" + url + "'",
                        Map
                                .of("fixture.greeting",
                                        OriginTrackedValue.of("hello", new TextResourceOrigin(remote, null)))));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader()) {
            assertThatNoException()
                    .isThrownBy(() -> ServiceConfigFiles.rejectOnProfileDocuments(environment, "e2", classLoader));
        }
        assertThat(opened).hasValue(0);
    }

    @Test
    void aFileThatCannotBeParsedFailsWithAConfigurationException() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service",
                        Map.of("application.yaml", "fixture: [s3cr3t-unclosed\n", "application-e2.yaml", ""));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(() -> ConfigFixtures
                    .application(classLoader, Map.of())
                    .run("--spring.profiles.active=e2", "--spring.config.location=optional:classpath:/config/"))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description())
                                .isEqualTo("Synapse could not read application.yaml to check it for "
                                        + "spring.config.activate.on-profile.");
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
    void theLongestMatchingConfigNameNamesTheFile() throws IOException {
        Path service = ConfigFixtures
                .directory(dir, "service", Map.of("app-extra.yaml", "spring.config.activate.on-profile: e5\n"));
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("names", Map.of("spring.config.name", "app,app-extra")));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            assertThatThrownBy(() -> ServiceConfigFiles.rejectOnProfileDocuments(environment, "e2", classLoader))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.action())
                            .isEqualTo("Move the document's settings to app-extra-<env>.yaml. Synapse uses one file "
                                    + "per environment and no on-profile documents."));
        }
    }

    private static String onProfileYaml() {
        return "fixture.greeting: hello\n---\nspring.config.activate.on-profile: e5\nfixture.greeting: hello e5\n";
    }

    private static String additionalLocation(Path location) {
        return "--spring.config.additional-location=optional:file:" + location.toString().replace('\\', '/') + "/";
    }
}
