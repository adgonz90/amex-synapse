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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ConfigSourcesTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @CsvSource({"file:/w/service/target/classes/application.yaml, true",
            "jar:file:/r/client.jar!/META-INF/synapse/config.yaml, true",
            "nested:/app/books.jar/!BOOT-INF/lib/client.jar, true",
            "JAR:FILE:/r/client.jar!/application.yaml, true", "http://host/application.yaml, false",
            "https://host/application.yaml, false", "jar:http://host/x.jar!/application.yaml, false",
            "jar:https://host/x.jar!/application.yaml, false",
            "ftp://host/application.yaml, false"})
    void tellsWhetherAUrlIsLocal(String url, boolean local) throws IOException {
        // The JDK's handlers parse a URL without connecting; Boot's nested: handler is not registered in this test.
        URL parsed = url.startsWith("nested:") ? URL.of(URI.create(url), new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) throws IOException {
                throw new IOException("Not opened in this test");
            }
        }) : URI.create(url).toURL();
        assertThat(ConfigSources.isLocal(parsed)).isEqualTo(local);
    }

    @ParameterizedTest
    @MethodSource("bootSources")
    void labelsBootSources(String name, String label) {
        assertThat(ConfigSources.label(new MapPropertySource(name, Map.of()))).isEqualTo(label);
    }

    static Stream<Arguments> bootSources() {
        return Stream
                .of(arguments("commandLineArgs", "command line arguments"),
                        arguments("systemProperties", "Java system properties"),
                        arguments("systemEnvironment", "environment variables"),
                        arguments("defaultProperties", "defaults"),
                        arguments("active-test-profiles", "@ActiveProfiles"),
                        arguments("Inlined Test Properties", "test properties"),
                        arguments("synapseBaggageFields", "Synapse baggage fields"),
                        arguments("Config resource 'class path resource [application.yaml]' via location "
                                + "'optional:classpath:/'", "application.yaml"),
                        arguments("Config resource 'class path resource [application-e2.yaml]' via location "
                                + "'optional:classpath:/' (document #1)", "application-e2.yaml (document #1)"),
                        arguments("Config resource 'class path resource [config/application.yaml]' via location "
                                + "'optional:classpath:/config/'", "config/application.yaml"),
                        arguments("Config resource 'file [ext\\application-e7.yaml]' via location "
                                + "'optional:file:./ext/'", "ext\\application-e7.yaml"),
                        arguments("Synapse module config [config.yaml] client-accounts-1.4.0.jar", "config.yaml"),
                        arguments("Synapse module config [config-e2.yaml] data-books (test) (document #1)",
                                "config-e2.yaml (document #1)"),
                        arguments("random", "random"),
                        arguments("servletContextInitParams", "servletContextInitParams"));
    }

    @ParameterizedTest
    @MethodSource("localUrls")
    void originNamesJarsAndModuleDirectories(String url, String resourcePath, String origin) throws IOException {
        assertThat(ConfigSources.origin(URI.create(url).toURL(), resourcePath)).isEqualTo(origin);
    }

    static Stream<Arguments> localUrls() {
        return Stream
                .of(arguments("jar:file:/r/client-accounts-1.4.0.jar!/META-INF/synapse/config.yaml",
                        "META-INF/synapse/config.yaml", "client-accounts-1.4.0.jar"),
                        arguments("file:/w/client-accounts/target/classes/META-INF/synapse/config.yaml",
                                "META-INF/synapse/config.yaml", "client-accounts"),
                        arguments("file:/w/service-books/target/test-classes/application-test.yaml",
                                "application-test.yaml", "service-books (test)"),
                        arguments("file:/w/out/META-INF/synapse/config.yaml", "META-INF/synapse/config.yaml", "/w/out"),
                        arguments("jar:file:/r/my%20libs/client-accounts%201.4.0.jar!/META-INF/synapse/config.yaml",
                                "META-INF/synapse/config.yaml", "client-accounts 1.4.0.jar"),
                        arguments("file:/w/my%20out/META-INF/synapse/config.yaml", "META-INF/synapse/config.yaml",
                                "/w/my out"),
                        arguments("file:///w/out/META-INF/synapse/config.yaml", "META-INF/synapse/config.yaml",
                                "/w/out"),
                        arguments("jar:file:/app.jar!/BOOT-INF/lib/client-accounts-1.4.0.jar!/"
                                + "META-INF/synapse/config.yaml", "META-INF/synapse/config.yaml",
                                "client-accounts-1.4.0.jar"),
                        arguments("jar:file:/app.jar!/BOOT-INF/classes!/application.yaml", "application.yaml",
                                "app.jar"));
    }

    @ParameterizedTest
    @MethodSource("nestedUrls")
    void originNamesNestedJars(String url, String resourcePath, String origin) throws IOException {
        // The JDK can't parse a jar:nested: URL without Boot's loader handler, so the URL gets a handler of its own.
        URL nested = URL.of(URI.create(url), new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) throws IOException {
                throw new IOException("Not opened in this test");
            }
        });
        assertThat(ConfigSources.origin(nested, resourcePath)).isEqualTo(origin);
    }

    static Stream<Arguments> nestedUrls() {
        return Stream
                .of(arguments("jar:nested:/app/books.jar/!BOOT-INF/lib/client-accounts-1.4.0.jar!/"
                        + "META-INF/synapse/config.yaml", "META-INF/synapse/config.yaml", "client-accounts-1.4.0.jar"),
                        arguments("jar:nested:/app/books.jar/!BOOT-INF/classes/!/application.yaml", "application.yaml",
                                "books.jar"));
    }

    @ParameterizedTest
    @MethodSource("otherUrls")
    void originOfAnyOtherLocationIsTheLocation(String url, String resourcePath, String origin) throws IOException {
        assertThat(ConfigSources.origin(URI.create(url).toURL(), resourcePath)).isEqualTo(origin);
    }

    @ParameterizedTest
    @MethodSource("moduleSources")
    void originOfAModuleSourceIsInItsName(String name, String origin) {
        assertThat(ConfigSources.origin(new MapPropertySource(name, Map.of()))).isEqualTo(origin);
    }

    static Stream<Arguments> moduleSources() {
        return Stream
                .of(arguments("Synapse module config [config.yaml] client-accounts-1.4.0.jar",
                        "client-accounts-1.4.0.jar"),
                        arguments("Synapse module config [config-e2.yaml] data-books (test) (document #1)",
                                "data-books (test)"),
                        arguments("Synapse module config [config.yaml] data-books (test)", "data-books (test)"),
                        arguments("Synapse module config [config.yaml] /w/a b/client.jar", "/w/a b/client.jar"));
    }

    @Test
    void originOfAConfigResourceIsItsClasspathRoot() throws IOException {
        Path service = ConfigFixtures
                .jar(dir, "service-books-1.0.jar",
                        Map.of("config/application-e2.yaml", "fixture.a: e2\n", "application.yaml", "fixture.a: b\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            for (String path : List.of("config/application-e2.yaml", "application.yaml")) {
                PropertySource<?> source = new YamlPropertySourceLoader()
                        .load("Config resource 'class path resource [" + path
                                + "]' via location 'optional:classpath:/'",
                                new ClassPathResource(path, classLoader))
                        .get(0);
                assertThat(ConfigSources.origin(source)).isEqualTo("service-books-1.0.jar");
            }
        }
    }

    @Test
    void originOfAConfigResourceFileIsItsDirectory() throws IOException {
        Path ext = ConfigFixtures.directory(dir, "ext", Map.of("application-e7.yaml", "fixture.a: e7\n"));
        PropertySource<?> source = new YamlPropertySourceLoader()
                .load("Config resource 'file [ext/application-e7.yaml]' via location 'optional:file:./ext/'",
                        new FileSystemResource(ext.resolve("application-e7.yaml")))
                .get(0);
        assertThat(ConfigSources.origin(source))
                .isEqualTo(ConfigSources
                        .origin(ext.resolve("application-e7.yaml").toUri().toURL(),
                                "application-e7.yaml"))
                .endsWith("/ext");
    }

    @Test
    void otherSourcesHaveNoOrigin() {
        assertThat(ConfigSources.origin(new MapPropertySource("systemEnvironment", Map.of("A", "b")))).isNull();
        assertThat(ConfigSources.origin(new MapPropertySource("synapseBaggageFields", Map.of("a", "b")))).isNull();
        assertThat(ConfigSources
                .origin(new MapPropertySource(
                        "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'",
                        Map.of("fixture.a", "no origin"))))
                .isNull();
    }

    @Test
    void aConfigResourceWithoutAFileNameOrUrlHasNoOrigin() {
        String name = "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'";
        for (Resource resource : List
                .of(new ByteArrayResource(new byte[0]), new ClassPathResource("no-such-dir/application.yaml"))) {
            PropertySource<?> source = new OriginTrackedMapPropertySource(name,
                    Map
                            .<String, Object>of("fixture.a",
                                    OriginTrackedValue.of("a", new TextResourceOrigin(resource, null))));
            assertThat(ConfigSources.origin(source)).isNull();
        }
    }

    static Stream<Arguments> otherUrls() {
        return Stream
                .of(arguments("http://repo.example/libs/META-INF/synapse/config.yaml", "META-INF/synapse/config.yaml",
                        "http://repo.example/libs"),
                        arguments("file:/w/out/META-INF/synapse/config.yaml", "other.yaml",
                                "/w/out/META-INF/synapse/config.yaml"),
                        arguments("https://user:pw@host/conf/app.yaml?token=t0k#frag", "app.yaml", "https://host/conf"),
                        arguments("https://us%65r:p%40ss@host:8443/app.yaml", "app.yaml", "https://host:8443"),
                        arguments("https://host/conf/app.yaml?X-Amz-Signature=s1g", "other.yaml",
                                "https://host/conf/app.yaml"),
                        arguments("jar:https://user:pw@host/libs/client.jar?sig=s1g!/META-INF/synapse/config.yaml",
                                "META-INF/synapse/config.yaml", "client.jar"));
    }

    @ParameterizedTest
    @MethodSource("urlNames")
    void labelsDropCredentialsQueriesAndFragmentsFromUrls(String name, String label) {
        assertThat(ConfigSources.label(new MapPropertySource(name, Map.of()))).isEqualTo(label);
    }

    static Stream<Arguments> urlNames() {
        return Stream
                .of(arguments("Config resource 'URL [https://user:pw@host/app.yaml?token=t0k#frag]' via location "
                        + "'https://user:pw@host/app.yaml?token=t0k'", "https://host/app.yaml"),
                        arguments("Config resource 'URL [https://us%65r:p%40ss@host/app.yaml]' via location "
                                + "'https://us%65r:p%40ss@host/app.yaml'", "https://host/app.yaml"),
                        arguments("Config resource 'URL [jar:https://user:pw@host/libs/a.jar?sig=s1g!/app.yaml]' "
                                + "via location 'jar:https://user:pw@host/libs/a.jar?sig=s1g!/app.yaml' "
                                + "(document #1)", "jar:https://host/libs/a.jar!/app.yaml (document #1)"),
                        arguments("Config resource 'URL [https://host/a b/app.yaml?sig=s1g]' via location 'x'",
                                "https://host/a b/app.yaml"),
                        arguments("remote https://user:pw@host/app.yaml?sig=s1g and ftp://u@files/x#f",
                                "remote https://host/app.yaml and ftp://files/x"),
                        arguments("Config resource 'file [file:///w/out/application.yaml]' via location 'x'",
                                "file:///w/out/application.yaml"));
    }

    @Test
    void moduleOriginsAndLocationsDropCredentials() {
        assertThat(ConfigSources
                .origin(new MapPropertySource("Synapse module config [config.yaml] https://user:pw@host/libs?t=t0k",
                        Map.of())))
                .isEqualTo("https://host/libs");
        assertThat(ConfigSources.location("jar:https://user:pw@host/libs/a.jar?sig=s1g!/"))
                .isEqualTo("https://host/libs/a.jar");
        assertThat(ConfigSources.location("jar:file:/r/a%20b/client.jar!/")).isEqualTo("/r/a b/client.jar");
    }
}
