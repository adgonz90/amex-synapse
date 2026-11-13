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
package io.americanexpress.synapse.api.rest.openapi.internal.aot;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitch.Mismatch;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiAotCheckTest {

    private static OpenApiAotCheck check(@Nullable String property, boolean documentOnInTheBuild) {
        MockEnvironment environment = new MockEnvironment();
        if (property != null) {
            environment.setProperty("springdoc.api-docs.enabled", property);
        }
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (documentOnInTheBuild) {
            beans.registerSingleton("springDocWebMvcConfiguration", new SpringDocWebMvcConfiguration());
        }
        return new OpenApiAotCheck(environment, beans);
    }

    @Test
    void mismatchIsReported() {
        assertThat(check("false", true).mismatch())
                .isEqualTo(new Mismatch("springdoc.api-docs.enabled", "false", "true"));
        assertThat(check("true", false).mismatch())
                .isEqualTo(new Mismatch("springdoc.api-docs.enabled", "true", "false"));
        // Unset is on, as for springdoc's @ConditionalOnProperty(matchIfMissing = true).
        assertThat(check(null, false).mismatch())
                .isEqualTo(new Mismatch("springdoc.api-docs.enabled", "true", "false"));
        // Any value but false turns it on, in any case.
        assertThat(check("FALSE", true).mismatch())
                .isEqualTo(new Mismatch("springdoc.api-docs.enabled", "false", "true"));
        assertThat(check("on", false).mismatch())
                .isEqualTo(new Mismatch("springdoc.api-docs.enabled", "true", "false"));
    }

    @Test
    void matchIsNull() {
        assertThat(check("true", true).mismatch()).isNull();
        assertThat(check("false", false).mismatch()).isNull();
        assertThat(check(null, true).mismatch()).isNull();
    }
}
