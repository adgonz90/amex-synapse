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
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationReportTest {

    private static final String APPLICATION = "Config resource 'class path resource [application.yaml]' via location "
            + "'optional:classpath:/'";

    private static final String APPLICATION_E2 = "Config resource 'class path resource [application-e2.yaml]' via "
            + "location 'optional:classpath:/'";

    private static final EnvironmentName E2 = new EnvironmentName("e2",
            "spring.profiles.active=${ENV}, application.yaml");

    @TempDir
    Path dir;

    @Test
    void firstLineNamesTheEnvironmentAndWhereItWasSet() throws IOException {
        Path service = ConfigFixtures
                .jar(dir, "service-books-1.0.jar", Map.of("application.yaml", "spring.profiles.active: ${ENV}\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            StandardEnvironment environment = emptyEnvironment();
            environment
                    .getPropertySources()
                    .addLast(new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of("ENV", "e2")));
            environment.getPropertySources().addLast(load(APPLICATION, "application.yaml", classLoader));
            EnvironmentName name = EnvironmentName.resolve(environment, Set.of());
            assertThat(ConfigurationReport.render(environment, name).lines().findFirst())
                    .hasValue("Synapse environment: e2 (spring.profiles.active=${ENV}, application.yaml)");
        }
    }

    @Test
    void listsSourcesHighestFirstWithOrigins() throws IOException {
        Path service = ConfigFixtures
                .jar(dir, "service-books-1.0.jar", Map
                        .of("application.yaml", "spring.profiles.active: ${ENV}\nfixture.a: base\n",
                                "application-e2.yaml", "fixture.a: e2\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            StandardEnvironment environment = emptyEnvironment();
            MutablePropertySources sources = environment.getPropertySources();
            sources.addLast(new SimpleCommandLinePropertySource("--fixture.b=c"));
            sources
                    .addLast(new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of("ENV", "e2")));
            sources.addLast(load(APPLICATION_E2, "application-e2.yaml", classLoader));
            sources.addLast(load(APPLICATION, "application.yaml", classLoader));
            sources
                    .addLast(new MapPropertySource(
                            ModuleConfigFiles.SOURCE_PREFIX + "[config-e2.yaml] client-accounts-1.4.0.jar",
                            Map.of("fixture.client", "e2")));
            sources
                    .addLast(new MapPropertySource(
                            ModuleConfigFiles.SOURCE_PREFIX + "[config.yaml] data-books-1.4.0.jar",
                            Map.of("fixture.data", "base")));
            sources.addLast(new MapPropertySource("defaultProperties", Map.of("fixture.default", "d")));
            assertThat(ConfigurationReport.render(environment, E2)).isEqualTo("""
                    Synapse environment: e2 (spring.profiles.active=${ENV}, application.yaml)
                    Configuration, highest priority first:
                      1. command line arguments
                      2. environment variables
                      3. application-e2.yaml  service-books-1.0.jar
                      4. application.yaml     service-books-1.0.jar
                      5. config-e2.yaml       client-accounts-1.4.0.jar
                      6. config.yaml          data-books-1.4.0.jar
                      7. defaults""");
        }
    }

    @Test
    void neverPrintsValues() throws IOException {
        Path service = ConfigFixtures
                .jar(dir, "service-books-1.0.jar",
                        Map.of("application.yaml", "spring.datasource.url: jdbc:postgresql://db/books\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service)) {
            StandardEnvironment environment = emptyEnvironment();
            MutablePropertySources sources = environment.getPropertySources();
            sources.addLast(new SimpleCommandLinePropertySource("--spring.datasource.password=s3cr3t"));
            sources
                    .addLast(new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of("SPRING_DATASOURCE_PASSWORD", "s3cr3t")));
            sources.addLast(load(APPLICATION, "application.yaml", classLoader));
            sources
                    .addLast(new MapPropertySource(
                            ModuleConfigFiles.SOURCE_PREFIX + "[config.yaml] data-books-1.4.0.jar",
                            Map.of("synapse.data.stores.books.url", "jdbc:postgresql://db/books")));
            assertThat(ConfigurationReport.render(environment, E2))
                    .doesNotContain("s3cr3t")
                    .doesNotContain("jdbc:postgresql://db/books")
                    .doesNotContain("spring.datasource")
                    .contains("  3. application.yaml  service-books-1.0.jar");
        }
    }

    @Test
    void neverPrintsCredentialsOrQueriesOfRemoteLocations() throws IOException {
        StandardEnvironment environment = emptyEnvironment();
        environment
                .getPropertySources()
                .addLast(remote("Config resource 'URL [https://user:pw@host/app.yaml?token=t0k#frag]' via location "
                        + "'https://user:pw@host/app.yaml?token=t0k'", "https://user:pw@host/app.yaml?token=t0k#frag"));
        environment
                .getPropertySources()
                .addLast(remote("Config resource 'URL [https://us%65r:p%40ss@host/base.yaml]' via location "
                        + "'https://us%65r:p%40ss@host/base.yaml'", "https://us%65r:p%40ss@host/base.yaml"));
        assertThat(ConfigurationReport.render(environment, E2))
                .endsWith("\n  1. https://host/app.yaml   https://host\n  2. https://host/base.yaml  https://host")
                .doesNotContain("user:pw")
                .doesNotContain("pw@")
                .doesNotContain("t0k")
                .doesNotContain("frag")
                .doesNotContain("us%65r")
                .doesNotContain("p%40ss")
                .doesNotContain("p@ss")
                .doesNotContain("@host");
    }

    @Test
    void skipsViewsAndEmptySources() {
        StandardEnvironment environment = emptyEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.addLast(new PropertySource.StubPropertySource("servletContextInitParams"));
        sources
                .addLast(new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("ENV", "e2")));
        sources.addLast(new RandomValuePropertySource());
        sources.addLast(new MapPropertySource("empty", Map.of()));
        sources.addLast(new LookupOnlySource("jndiProperties"));
        ConfigurationPropertySources.attach(environment);
        assertThat(ConfigurationReport.render(environment, E2)).isEqualTo("""
                Synapse environment: e2 (spring.profiles.active=${ENV}, application.yaml)
                Configuration, highest priority first:
                  1. environment variables
                  2. jndiProperties""");
    }

    private static StandardEnvironment emptyEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return environment;
    }

    private static PropertySource<?> load(String name, String path, ClassLoader classLoader) throws IOException {
        return new YamlPropertySourceLoader().load(name, new ClassPathResource(path, classLoader)).get(0);
    }

    /** A source Boot loaded from a remote URL; the resource is never opened. */
    private static PropertySource<?> remote(String name, String url) throws IOException {
        Resource resource = new UrlResource(url);
        return new OriginTrackedMapPropertySource(name,
                Map
                        .<String, Object>of("fixture.a",
                                OriginTrackedValue.of("a", new TextResourceOrigin(resource, null))));
    }

    /** A source that can't list its names, such as JNDI: it is always listed. */
    private static final class LookupOnlySource extends PropertySource<Object> {

        LookupOnlySource(String name) {
            super(name, new Object());
        }

        @Override
        public @Nullable Object getProperty(String name) {
            return null;
        }
    }
}
