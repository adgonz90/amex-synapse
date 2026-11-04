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
