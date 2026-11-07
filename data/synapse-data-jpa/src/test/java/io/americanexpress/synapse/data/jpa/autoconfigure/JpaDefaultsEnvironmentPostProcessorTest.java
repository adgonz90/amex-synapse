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
package io.americanexpress.synapse.data.jpa.autoconfigure;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class JpaDefaultsEnvironmentPostProcessorTest {

    private static final String OPEN_IN_VIEW = "spring.jpa.open-in-view";

    private static final String REPOSITORIES = "spring.data.jpa.repositories.enabled";

    private final SpringApplication application = new SpringApplication();

    private final JpaDefaultsEnvironmentPostProcessor processor = new JpaDefaultsEnvironmentPostProcessor();

    @Test
    void openInViewAndBootRepositoriesOffAtLowestPrecedence() {
        MockEnvironment empty = new MockEnvironment();
        processor.postProcessEnvironment(empty, application);
        PropertySource<?> defaults = Objects
                .requireNonNull(empty.getPropertySources().get(DefaultPropertiesPropertySource.NAME));
        assertThat(defaults.getProperty(OPEN_IN_VIEW)).isEqualTo("false");
        assertThat(defaults.getProperty(REPOSITORIES)).isEqualTo("false");
        assertThat(empty.getProperty(OPEN_IN_VIEW)).isEqualTo("false");
        assertThat(empty.getProperty(REPOSITORIES)).isEqualTo("false");
        assertThat(empty.getPropertySources().stream().map(PropertySource::getName).toList())
                .last()
                .isEqualTo(DefaultPropertiesPropertySource.NAME);

        MockEnvironment configured = new MockEnvironment()
                .withProperty(OPEN_IN_VIEW, "true")
                .withProperty(REPOSITORIES, "true");
        processor.postProcessEnvironment(configured, application);
        assertThat(configured.getProperty(OPEN_IN_VIEW)).isEqualTo("true");
        assertThat(configured.getProperty(REPOSITORIES)).isEqualTo("true");
    }

    @Test
    void keepsAnApplicationsOwnDefault() {
        MockEnvironment environment = new MockEnvironment();
        Map<String, Object> own = new HashMap<>(Map.of(OPEN_IN_VIEW, "true", "other", "kept"));
        environment.getPropertySources().addLast(new MapPropertySource(DefaultPropertiesPropertySource.NAME, own));

        processor.postProcessEnvironment(environment, application);
        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty(OPEN_IN_VIEW)).isEqualTo("true");
        assertThat(environment.getProperty(REPOSITORIES)).isEqualTo("false");
        assertThat(environment.getProperty("other")).isEqualTo("kept");
        assertThat(environment.getPropertySources().stream().filter(DefaultPropertiesPropertySource::hasMatchingName))
                .hasSize(1);
    }

    @Test
    void addsNothingWhenTheApplicationSetsEveryDefault() {
        MockEnvironment environment = new MockEnvironment();
        Map<String, Object> own = new HashMap<>(Map.of(OPEN_IN_VIEW, "true", REPOSITORIES, "true"));
        environment.getPropertySources().addLast(new MapPropertySource(DefaultPropertiesPropertySource.NAME, own));

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty(OPEN_IN_VIEW)).isEqualTo("true");
        assertThat(environment.getProperty(REPOSITORIES)).isEqualTo("true");
    }

    @Test
    void runsAfterConfigData() {
        assertThat(processor.getOrder()).isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
    }

    @Test
    void isRegistered() throws IOException {
        List<String> registered = new ArrayList<>();
        for (URL url : Collections
                .list(getClass().getClassLoader().getResources(SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION))) {
            Properties factories = PropertiesLoaderUtils.loadProperties(new UrlResource(url));
            registered.add(factories.getProperty(EnvironmentPostProcessor.class.getName(), ""));
        }
        assertThat(registered).anyMatch(names -> names.contains(JpaDefaultsEnvironmentPostProcessor.class.getName()));
    }
}
