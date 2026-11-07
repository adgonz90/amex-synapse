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
package io.americanexpress.synapse.api.grpc.autoconfigure;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynapseGrpcPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultsMatchTheSpec() {
        runner.run(context -> {
            SynapseGrpcProperties properties = context.getBean(SynapseGrpcProperties.class);
            assertThat(properties.deadline().defaultTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.deadline().max()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.maxConcurrentCalls()).isEqualTo(200);
            assertThat(properties.context().metadata()).isEmpty();
            assertThat(properties.errors().status()).isEmpty();
            assertThat(properties.operations()).isEmpty();
        });
    }

    @Test
    void rejectsDefaultAboveMax() {
        runner
                .withPropertyValues("synapse.api.grpc.deadline.default=40s", "synapse.api.grpc.deadline.max=30s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("synapse.api.grpc.deadline.default")
                            .contains("synapse.api.grpc.deadline.max");
                });
    }

    @Test
    void rejectsNonPositiveDefaultTimeout() {
        runner.withPropertyValues("synapse.api.grpc.deadline.default=0s").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("synapse.api.grpc.deadline.default");
        });
        runner.withPropertyValues("synapse.api.grpc.deadline.default=-1s").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("synapse.api.grpc.deadline.default");
        });
    }

    @Test
    void rejectsNonPositiveMaxConcurrentCalls() {
        runner.withPropertyValues("synapse.api.grpc.max-concurrent-calls=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("synapse.api.grpc.max-concurrent-calls");
        });
    }

    @Test
    void rejectsInvalidMetadataKeys() {
        for (String key : new String[]{"X-Tenant", "tenant-bin", "a b"}) {
            runner.withPropertyValues("synapse.api.grpc.context.metadata.tenant=" + key).run(context -> {
                assertThat(context).hasFailed();
                assertThat(rootMessage(context.getStartupFailure()))
                        .contains("synapse.api.grpc.context.metadata.tenant")
                        .contains("'" + key + "'");
            });
        }
        assertThatThrownBy(() -> new SynapseGrpcProperties.Context(Map.of("tenant", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("synapse.api.grpc.context.metadata.tenant");
    }

    @Test
    void bindsMapsWithBracketedKeys() {
        runner
                .withPropertyValues("synapse.api.grpc.context.metadata.tenant=x-tenant",
                        "synapse.api.grpc.errors.status.[BOOK_NOT_FOUND]=FAILED_PRECONDITION")
                .run(context -> {
                    SynapseGrpcProperties properties = context.getBean(SynapseGrpcProperties.class);
                    assertThat(properties.context().metadata()).isEqualTo(Map.of("tenant", "x-tenant"));
                    assertThat(properties.errors().status())
                            .isEqualTo(Map.of("BOOK_NOT_FOUND", io.grpc.Status.Code.FAILED_PRECONDITION));
                });
    }

    @Test
    void contextMetadataKeepsTheConfiguredOrderAndIsUnmodifiable() {
        runner
                .withPropertyValues("synapse.api.grpc.context.metadata.tenant=x-tenant",
                        "synapse.api.grpc.context.metadata.channel=x-channel",
                        "synapse.api.grpc.context.metadata.zone=x-zone",
                        "synapse.api.grpc.context.metadata.app=x-app",
                        "synapse.api.grpc.context.metadata.region=x-region",
                        "synapse.api.grpc.context.metadata.brand=x-brand")
                .run(context -> {
                    Map<String, String> metadata = context.getBean(SynapseGrpcProperties.class).context().metadata();
                    assertThat(metadata)
                            .containsExactly(Map.entry("tenant", "x-tenant"),
                                    Map.entry("channel", "x-channel"), Map.entry("zone", "x-zone"),
                                    Map.entry("app", "x-app"),
                                    Map.entry("region", "x-region"), Map.entry("brand", "x-brand"));
                    assertThatThrownBy(() -> metadata.put("other", "x-other"))
                            .isInstanceOf(UnsupportedOperationException.class);
                });
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
    @EnableConfigurationProperties(SynapseGrpcProperties.class)
    static class Config {
    }
}
