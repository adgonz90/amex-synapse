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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AotEnvironmentCheckTest {

    private static final String SERVICE = "service-books-1.0.jar";

    private static final String CLIENT = "client-accounts-1.4.0.jar";

    private static final String FIXED = ", which no environment-independent file declares. In AOT mode the set of "
            + "clients and stores is fixed at build time.";

    private static final List<String> APPLICATION = List.of("application");

    @TempDir
    Path dir;

    @Test
    void anEnvironmentFileThatAddsAClientFails() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.yaml", "synapse.client.accounts.base-url: http://accounts\n",
                                "META-INF/synapse/config-e2.yaml", "synapse.client.payments.base-url: http://pay\n"));
        assertThatThrownBy(() -> verify(service(""), client))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo("config-e2.yaml (client-accounts-1.4.0.jar) adds the client 'payments'" + FIXED);
                    assertThat(ex.action())
                            .isEqualTo("Declare synapse.client.payments in config.yaml or application.yaml, and set "
                                    + "only its values per environment.");
                });
    }

    @Test
    void aServiceEnvironmentFileThatAddsAStoreFails() throws IOException {
        Path service = ConfigFixtures
                .jar(dir, SERVICE, Map
                        .of("application.yaml", "synapse.data.stores.books.url: jdbc:h2:mem:books\n",
                                "application-e2.yaml", "synapse.data.stores.legacy.url: jdbc:h2:mem:legacy\n"));
        assertThatThrownBy(() -> verify(service))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo("application-e2.yaml (service-books-1.0.jar) adds the store 'legacy'" + FIXED);
                    assertThat(ex.action())
                            .isEqualTo("Declare synapse.data.stores.legacy in config.yaml or application.yaml, and set "
                                    + "only its values per environment.");
                });
    }

    @Test
    void valuesOfDeclaredNamesMayChange() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.yaml", "synapse.client.accounts.base-url: http://accounts\n",
                                "META-INF/synapse/config-e2.yaml",
                                "synapse.client.accounts:\n  base-url: http://accounts-e2\n  read-timeout: 5s\n"));
        Path service = service("synapse.client.accounts.max-connections: 20\n");
        assertThatCode(() -> verify(service, client)).doesNotThrowAnyException();
    }

    @Test
    void clientDefaultsIsNotAName() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.yaml", "synapse.client.accounts.base-url: http://accounts\n",
                                "META-INF/synapse/config-e2.yaml", "synapse.client.defaults.read-timeout: 5s\n"));
        assertThatCode(() -> verify(service(""), client)).doesNotThrowAnyException();
    }

    @Test
    void namesAreComparedCanonically() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.yaml", """
                                synapse:
                                  client.data-books.base-url: http://books
                                  data.stores.data-books.url: jdbc:h2:mem:books
                                """, "META-INF/synapse/config-e2.yaml", """
                                synapse:
                                  client.dataBooks.read-timeout: 5s
                                  data.stores.dataBooks.pool-size: 4
                                """));
        assertThatCode(() -> verify(service(""), client)).doesNotThrowAnyException();
    }

    @Test
    void aNameTheServiceDeclaresMaySetValuesInAModuleEnvironmentFile() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT,
                        Map.of("META-INF/synapse/config-e2.yaml", "synapse.client.accounts.read-timeout: 5s\n"));
        Path service = ConfigFixtures
                .jar(dir, SERVICE, Map
                        .of("application.yaml", "synapse.client.accounts.base-url: http://accounts\n",
                                "application-e2.yaml", ""));
        assertThatCode(() -> verify(service, client)).doesNotThrowAnyException();
    }

    @Test
    void bracketedNamesAreNames() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config.properties", "synapse.client[accounts].base-url=http://accounts\n",
                                "META-INF/synapse/config-e2.properties",
                                "synapse.client.accounts.read-timeout=5s\nsynapse.client[payments].base-url=http://pay\n"));
        assertThatThrownBy(() -> verify(service(""), client))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                        .isEqualTo("config-e2.properties (client-accounts-1.4.0.jar) adds the client 'payments'"
                                + FIXED));
    }

    @Test
    void keysAboveTheNameAreIgnored() throws IOException {
        Path client = ConfigFixtures
                .jar(dir, CLIENT, Map
                        .of("META-INF/synapse/config-e2.properties", """
                                synapse.client=x
                                synapse.data.stores=y
                                synapse.data.other.books=z
                                """));
        assertThatCode(() -> verify(service(""), client)).doesNotThrowAnyException();
    }

    @Test
    void sourcesOtherThanTheFilesAreIgnored() throws IOException {
        Path service = ConfigFixtures
                .jar(dir, SERVICE, Map
                        .of("application.yaml", "spring.config.import: optional:classpath:extra.yaml\n",
                                "application-e2.yaml", "", "extra.yaml", "synapse.client.extra.base-url: http://x\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service);
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader, Map.of("SYNAPSE_CLIENT_ENVIRONMENT_BASEURL", "http://env"))
                        .run("--spring.profiles.active=e2", "--synapse.data.stores.cmdline.url=jdbc:h2:mem:x")) {
            assertThatCode(() -> AotEnvironmentCheck.verify(context.getEnvironment(), "e2", APPLICATION))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void usesTheConfigNames() throws IOException {
        Path service = ConfigFixtures
                .jar(dir, SERVICE, Map
                        .of("books.yaml", "synapse.client.accounts.base-url: http://accounts\n", "books-e2.yaml",
                                "synapse.client.accounts.read-timeout: 5s\nsynapse.client.payments.base-url: http://pay\n"));
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(service);
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader, Map.of())
                        .run("--spring.profiles.active=e2", "--spring.config.name=books")) {
            assertThatThrownBy(() -> AotEnvironmentCheck.verify(context.getEnvironment(), "e2", List.of("books")))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                            .isEqualTo("books-e2.yaml (service-books-1.0.jar) adds the client 'payments'" + FIXED));
        }
    }

    @Test
    void aFileWithoutAnOriginIsNamedAlone() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addLast(new MapPropertySource("Config resource 'class path resource [application-e2.yaml]' via "
                        + "location 'optional:classpath:/'", Map.of("synapse.data.stores.legacy.url", "x")));
        assertThatThrownBy(() -> AotEnvironmentCheck.verify(environment, "e2", APPLICATION))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                        .isEqualTo("application-e2.yaml adds the store 'legacy'" + FIXED));
    }

    /** A service jar with an application.yaml and the given application-e2.yaml. */
    private Path service(String environmentFile) {
        return ConfigFixtures
                .jar(dir, SERVICE, Map
                        .of("application.yaml", "fixture.greeting: hello\n", "application-e2.yaml",
                                environmentFile));
    }

    /** Starts the service as e2 over the given roots, then runs the check on its environment. */
    private static void verify(Path... roots) throws IOException {
        try (URLClassLoader classLoader = ConfigFixtures.classLoader(roots);
                ConfigurableApplicationContext context = ConfigFixtures
                        .application(classLoader, Map.of())
                        .run("--spring.profiles.active=e2")) {
            AotEnvironmentCheck.verify(context.getEnvironment(), "e2", APPLICATION);
        }
    }
}
