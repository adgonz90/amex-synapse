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
package io.americanexpress.synapse.data.internal.env;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LowestPrecedenceDefaultsTest {

    @Test
    void addsTheDefaultsAsTheLastSource() {
        MockEnvironment environment = new MockEnvironment().withProperty("set", "by-the-application");

        LowestPrecedenceDefaults.add(environment, Map.of("set", "default", "unset", "default"));

        assertThat(environment.getProperty("set")).isEqualTo("by-the-application");
        assertThat(environment.getProperty("unset")).isEqualTo("default");
        assertThat(environment.getPropertySources().stream().map(PropertySource::getName).toList())
                .last()
                .isEqualTo(DefaultPropertiesPropertySource.NAME);
    }

    @Test
    void keepsTheApplicationsOwnDefaults() {
        MockEnvironment environment = new MockEnvironment();
        Map<String, Object> own = new HashMap<>(Map.of("own", "kept"));
        environment.getPropertySources().addLast(new MapPropertySource(DefaultPropertiesPropertySource.NAME, own));

        LowestPrecedenceDefaults.add(environment, Map.of("own", "replaced", "added", "default"));

        PropertySource<?> defaults = Objects
                .requireNonNull(environment.getPropertySources().get(DefaultPropertiesPropertySource.NAME));
        assertThat(defaults.getProperty("own")).isEqualTo("kept");
        assertThat(defaults.getProperty("added")).isEqualTo("default");
        assertThat(environment.getPropertySources().stream().filter(DefaultPropertiesPropertySource::hasMatchingName))
                .hasSize(1);
    }

    @Test
    void addsNothingWhenEveryKeyIsTheApplicationsOwnDefault() {
        MockEnvironment environment = new MockEnvironment();
        Map<String, Object> own = new HashMap<>(Map.of("own", "kept"));
        MapPropertySource source = new MapPropertySource(DefaultPropertiesPropertySource.NAME, own);
        environment.getPropertySources().addLast(source);

        LowestPrecedenceDefaults.add(environment, Map.of("own", "replaced"));

        assertThat(environment.getPropertySources().get(DefaultPropertiesPropertySource.NAME)).isSameAs(source);
        assertThat(own).containsExactly(Map.entry("own", "kept"));
    }

    @Test
    void runsRightAfterConfigData() {
        assertThat(LowestPrecedenceDefaults.ORDER).isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
    }
}
