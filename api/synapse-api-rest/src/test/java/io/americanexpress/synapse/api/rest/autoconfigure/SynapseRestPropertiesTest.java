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
package io.americanexpress.synapse.api.rest.autoconfigure;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynapseRestPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultsMatchTheSpec() {
        runner.run(context -> {
            SynapseRestProperties properties = context.getBean(SynapseRestProperties.class);
            assertThat(properties.deadline().header()).isEqualTo("Request-Timeout");
            assertThat(properties.deadline().defaultTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.deadline().max()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.maxConcurrentRequests()).isEqualTo(200);
            assertThat(properties.maxRequestSize()).isEqualTo(DataSize.ofMegabytes(1));
            assertThat(properties.context().headers()).isEmpty();
            assertThat(properties.errors().status()).isEmpty();
            assertThat(properties.operations()).isEmpty();
        });
    }

    @Test
    void pagingDefaultsMatchTheSpec() {
        runner.run(context -> {
            SynapseRestProperties.Paging paging = context.getBean(SynapseRestProperties.class).paging();
            assertThat(paging.parameters().cursor()).isEqualTo("cursor");
            assertThat(paging.parameters().offset()).isEqualTo("offset");
            assertThat(paging.parameters().size()).isEqualTo("size");
            assertThat(paging.parameters().sort()).isEqualTo("sort");
        });
    }

    @Test
    void theMovedPagingKeysAreNotBoundHere() {
        // synapse.paging.max-offset and synapse.paging.cursor.* are core's now; the old keys bind to nothing.
        runner
                .withPropertyValues("synapse.api.rest.paging.max-offset=-1",
                        "synapse.api.rest.paging.cursor.max-age=0s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsDuplicateOrInvalidParameterNames() {
        runner.withPropertyValues("synapse.api.rest.paging.parameters.size=offset").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("synapse.api.rest.paging.parameters");
        });
        runner.withPropertyValues("synapse.api.rest.paging.parameters.cursor=pa ge").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("synapse.api.rest.paging.parameters");
        });
        runner.withPropertyValues("synapse.api.rest.paging.parameters.sort= ").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("synapse.api.rest.paging.parameters");
        });
    }

    @Test
    void rejectsDefaultAboveMax() {
        runner
                .withPropertyValues("synapse.api.rest.deadline.default=40s", "synapse.api.rest.deadline.max=30s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("synapse.api.rest.deadline.default")
                            .contains("synapse.api.rest.deadline.max");
                });
    }

    @Test
    void rejectsNonPositiveDefaultTimeout() {
        runner.withPropertyValues("synapse.api.rest.deadline.default=0s").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("synapse.api.rest.deadline.default");
        });
    }

    @Test
    void rejectsNonPositiveLimits() {
        runner.withPropertyValues("synapse.api.rest.max-concurrent-requests=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("synapse.api.rest.max-concurrent-requests");
        });
        runner.withPropertyValues("synapse.api.rest.max-request-size=0B").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("synapse.api.rest.max-request-size");
        });
    }

    @Test
    void bindsMapsWithBracketedKeys() {
        runner
                .withPropertyValues("synapse.api.rest.context.headers.tenant=X-Tenant",
                        "synapse.api.rest.errors.status.[BOOK_NOT_FOUND]=423")
                .run(context -> {
                    SynapseRestProperties properties = context.getBean(SynapseRestProperties.class);
                    assertThat(properties.context().headers()).isEqualTo(Map.of("tenant", "X-Tenant"));
                    assertThat(properties.errors().status()).isEqualTo(Map.of("BOOK_NOT_FOUND", 423));
                });
    }

    @Test
    void contextHeadersKeepTheConfiguredOrder() {
        runner
                .withPropertyValues("synapse.api.rest.context.headers.tenant=X-Tenant",
                        "synapse.api.rest.context.headers.channel=X-Channel",
                        "synapse.api.rest.context.headers.zone=X-Zone",
                        "synapse.api.rest.context.headers.app=X-App",
                        "synapse.api.rest.context.headers.region=X-Region",
                        "synapse.api.rest.context.headers.brand=X-Brand")
                .run(context -> assertThat(context.getBean(SynapseRestProperties.class).context().headers())
                        .containsExactly(Map.entry("tenant", "X-Tenant"), Map.entry("channel", "X-Channel"),
                                Map.entry("zone", "X-Zone"), Map.entry("app", "X-App"),
                                Map.entry("region", "X-Region"), Map.entry("brand", "X-Brand")));
    }

    @Test
    void contextHeadersAreUnmodifiable() {
        Map<String, String> headers = new SynapseRestProperties.Context(Map.of("tenant", "X-Tenant")).headers();

        assertThatThrownBy(() -> headers.put("channel", "X-Channel"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void corsDefaultsMatchTheSpec() {
        runner.run(context -> {
            SynapseRestProperties.Cors cors = context.getBean(SynapseRestProperties.class).cors();
            assertThat(cors.allowedOrigins()).isEmpty();
            assertThat(cors.allowedMethods()).containsExactly("GET", "HEAD", "POST", "PUT", "DELETE");
            assertThat(cors.allowedHeaders()).containsExactly("*");
            assertThat(cors.exposedHeaders()).isEmpty();
            assertThat(cors.allowCredentials()).isFalse();
            assertThat(cors.maxAge()).isEqualTo(Duration.ofMinutes(30));
        });
    }

    @Test
    void rejectsInvalidOrigins() {
        for (String origin : List
                .of("https://a.example.com/", "https://a.example.com/x", "https://*.example.com",
                        "a.example.com", "https://a.example.com?x=1", "*.example.com", "ftp://a.example.com",
                        "https://")) {
            runner.withPropertyValues("synapse.api.rest.cors.allowed-origins=" + origin).run(context -> {
                assertThat(context).as(origin).hasFailed();
                assertThat(rootMessage(context.getStartupFailure()))
                        .as(origin)
                        .contains("synapse.api.rest.cors.allowed-origins")
                        .contains(origin);
            });
        }
        runner
                .withPropertyValues("synapse.api.rest.cors.allowed-origins=http://localhost:3000,https://a.example.com,"
                        + "http://[::1]:8080,*")
                .run(context -> assertThat(context.getBean(SynapseRestProperties.class).cors().allowedOrigins())
                        .containsExactly("http://localhost:3000", "https://a.example.com", "http://[::1]:8080", "*"));
    }

    @Test
    void rejectsWildcardWithCredentials() {
        assertThatThrownBy(() -> cors(List.of("https://a.example.com", "*"), List.of("GET"), true, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("synapse.api.rest.cors.allow-credentials can't be true while"
                        + " synapse.api.rest.cors.allowed-origins contains *: browsers refuse credentials for any"
                        + " origin. List the exact origins.");
        assertThat(cors(List.of("https://a.example.com"), List.of("GET"), true, Duration.ZERO).allowCredentials())
                .isTrue();
    }

    @Test
    void rejectsOriginPortsOutOfRange() {
        for (String origin : List.of("http://a.example.com:0", "https://a.example.com:65536", "http://[::1]:99999")) {
            runner.withPropertyValues("synapse.api.rest.cors.allowed-origins=" + origin).run(context -> {
                assertThat(context).as(origin).hasFailed();
                assertThat(rootMessage(context.getStartupFailure()))
                        .as(origin)
                        .isEqualTo("synapse.api.rest.cors.allowed-origins must each have a port from 1 to 65535,"
                                + " but one was '" + origin + "'");
            });
        }
        runner
                .withPropertyValues("synapse.api.rest.cors.allowed-origins=http://a.example.com:1,"
                        + "https://a.example.com:65535")
                .run(context -> assertThat(context.getBean(SynapseRestProperties.class).cors().allowedOrigins())
                        .containsExactly("http://a.example.com:1", "https://a.example.com:65535"));
    }

    @Test
    void rejectsWildcardExposedHeadersWithCredentials() {
        String message = "synapse.api.rest.cors.allow-credentials can't be true while"
                + " synapse.api.rest.cors.exposed-headers contains *: browsers read it as a header named * on a"
                + " request with credentials, not as any header. List the exact headers.";
        runner
                .withPropertyValues("synapse.api.rest.cors.allowed-origins=https://a.example.com",
                        "synapse.api.rest.cors.allow-credentials=true", "synapse.api.rest.cors.exposed-headers=X-A,*")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).isEqualTo(message);
                });
        // Without credentials, * exposes every header.
        runner
                .withPropertyValues("synapse.api.rest.cors.allowed-origins=https://a.example.com",
                        "synapse.api.rest.cors.exposed-headers=*")
                .run(context -> assertThat(context.getBean(SynapseRestProperties.class).cors().exposedHeaders())
                        .containsExactly("*"));
    }

    @Test
    void rejectsUnknownMethodsAndNegativeMaxAge() {
        for (String method : List.of("FETCH", "get", "GET,*x")) {
            runner.withPropertyValues("synapse.api.rest.cors.allowed-methods=" + method).run(context -> {
                assertThat(context).as(method).hasFailed();
                assertThat(rootMessage(context.getStartupFailure()))
                        .as(method)
                        .contains("synapse.api.rest.cors.allowed-methods");
            });
        }
        runner
                .withPropertyValues("synapse.api.rest.cors.allowed-methods=*")
                .run(context -> assertThat(context.getBean(SynapseRestProperties.class).cors().allowedMethods())
                        .containsExactly("*"));
        runner
                .withPropertyValues("synapse.api.rest.cors.allowed-methods=PATCH,OPTIONS,TRACE")
                .run(context -> assertThat(context.getBean(SynapseRestProperties.class).cors().allowedMethods())
                        .containsExactly("PATCH", "OPTIONS", "TRACE"));
        runner.withPropertyValues("synapse.api.rest.cors.max-age=-1s").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("synapse.api.rest.cors.max-age");
        });
        runner
                .withPropertyValues("synapse.api.rest.cors.max-age=0s")
                .run(context -> assertThat(context.getBean(SynapseRestProperties.class).cors().maxAge())
                        .isEqualTo(Duration.ZERO));
    }

    @Test
    void rejectsEmptyMethods() {
        String message = "synapse.api.rest.cors.allowed-methods must list at least one method (or *) but was empty";
        for (String value : List.of("", " ")) {
            runner.withPropertyValues("synapse.api.rest.cors.allowed-methods=" + value).run(context -> {
                assertThat(context).as("'" + value + "'").hasFailed();
                assertThat(rootMessage(context.getStartupFailure())).as("'" + value + "'").isEqualTo(message);
            });
        }
        // Without any origin too: CORS is off, but the setting is still wrong.
        assertThatThrownBy(() -> cors(List.of(), List.of(), false, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    void originSchemeAndHostAreLowerCased() {
        runner
                .withPropertyValues("synapse.api.rest.cors.allowed-origins=HTTPS://App.Example.com:8443,"
                        + "Http://LOCALHOST:3000,http://[::FFFF:7F00:1],*")
                .run(context -> assertThat(context.getBean(SynapseRestProperties.class).cors().allowedOrigins())
                        .containsExactly("https://app.example.com:8443", "http://localhost:3000",
                                "http://[::ffff:7f00:1]", "*"));
    }

    @Test
    void corsListsAreUnmodifiable() {
        List<String> origins = cors(List.of("https://a.example.com"), List.of("GET"), false, Duration.ZERO)
                .allowedOrigins();
        assertThatThrownBy(() -> origins.add("https://b.example.com"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static SynapseRestProperties.Cors cors(List<String> origins, List<String> methods,
            boolean allowCredentials, Duration maxAge) {
        return new SynapseRestProperties.Cors(origins, methods, List.of("*"), List.of(), allowCredentials, maxAge);
    }

    private static String rootMessage(@Nullable Throwable failure) {
        Objects.requireNonNull(failure);
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SynapseRestProperties.class)
    static class Config {
    }
}
