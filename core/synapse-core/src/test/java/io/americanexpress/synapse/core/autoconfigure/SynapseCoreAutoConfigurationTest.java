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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseCoreAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SynapseCoreAutoConfiguration.class));

    @Test
    void errorDetailIsHiddenByDefault() {
        contextRunner.run(context -> {
            SynapseErrorProperties properties = context.getBean(SynapseErrorProperties.class);
            assertThat(properties.includeDetail()).isFalse();
        });
    }

    @Test
    void errorDetailCanBeEnabledWithProperty() {
        contextRunner.withPropertyValues("synapse.errors.include-detail=true").run(context -> {
            SynapseErrorProperties properties = context.getBean(SynapseErrorProperties.class);
            assertThat(properties.includeDetail()).isTrue();
        });
    }
}
