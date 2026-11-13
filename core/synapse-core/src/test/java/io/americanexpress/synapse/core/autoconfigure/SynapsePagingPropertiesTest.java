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

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SynapsePagingPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultsMatchTheSpec() {
        runner.run(context -> {
            SynapsePagingProperties paging = context.getBean(SynapsePagingProperties.class);
            assertThat(paging.defaultSize()).isEqualTo(20);
            assertThat(paging.maxSize()).isEqualTo(100);
            assertThat(paging.maxOffset()).isEqualTo(1000);
            assertThat(paging.cursor().key()).isNull();
            assertThat(paging.cursor().previousKeys()).isEmpty();
            assertThat(paging.cursor().maxAge()).isNull();
        });
    }

    @Test
    void bindsMaxOffsetAndTheCursorSettings() {
        runner
                .withPropertyValues("synapse.paging.max-offset=0", "synapse.paging.cursor.key=a2V5",
                        "synapse.paging.cursor.previous-keys[0]=b2xk", "synapse.paging.cursor.max-age=1d")
                .run(context -> {
                    SynapsePagingProperties paging = context.getBean(SynapsePagingProperties.class);
                    assertThat(paging.maxOffset()).isZero();
                    assertThat(paging.cursor().key()).isEqualTo("a2V5");
                    assertThat(paging.cursor().previousKeys()).containsExactly("b2xk");
                    assertThat(paging.cursor().maxAge()).isEqualTo(Duration.ofDays(1));
                });
    }

    @Test
    void maxOffsetMustNotBeNegative() {
        runner.withPropertyValues("synapse.paging.max-offset=-1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure()))
                    .isEqualTo("synapse.paging.max-offset must not be negative but was -1");
        });
    }

    @Test
    void cursorMaxAgeMustBePositive() {
        for (String maxAge : List.of("0s", "-1s")) {
            runner.withPropertyValues("synapse.paging.cursor.max-age=" + maxAge).run(context -> {
                assertThat(context).as(maxAge).hasFailed();
                assertThat(rootMessage(context.getStartupFailure()))
                        .as(maxAge)
                        .startsWith("synapse.paging.cursor.max-age must be positive but was ");
            });
        }
        runner
                .withPropertyValues("synapse.paging.cursor.max-age=1s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void cursorToStringNeverShowsKeys() {
        SynapsePagingProperties.Cursor cursor = new SynapsePagingProperties.Cursor(
                "c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0MTI=", List.of("b2xkb2xkb2xkb2xk"), Duration.ofDays(1));

        assertThat(cursor.toString())
                .contains("key=set")
                .contains("previousKeys=1")
                .doesNotContain("c2VjcmV0")
                .doesNotContain("b2xkb2xk");
        assertThat(new SynapsePagingProperties.Cursor(null, List.of(), null).toString())
                .contains("key=unset")
                .contains("previousKeys=0");
        // A blank key counts as unset and blank previous keys are ignored, as the codec reads them.
        assertThat(new SynapsePagingProperties.Cursor(" \n", List.of("", "b2xkb2xkb2xkb2xk", "  "), null)
                .toString()).contains("key=unset").contains("previousKeys=1");
        // The properties record prints the cursor the same way.
        assertThat(new SynapsePagingProperties(20, 100, 1000, cursor).toString())
                .contains("key=set")
                .doesNotContain("c2VjcmV0");
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
    @EnableConfigurationProperties(SynapsePagingProperties.class)
    static class Config {
    }
}
