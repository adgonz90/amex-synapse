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
package io.americanexpress.synapse.data.autoconfigure;

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
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DataDefaultsEnvironmentPostProcessorTest {

    private static final String LIQUIBASE = "spring.liquibase.enabled";

    private static final String SQL_INIT_MODE = "spring.sql.init.mode";

    private final SpringApplication application = new SpringApplication();

    private final DataDefaultsEnvironmentPostProcessor processor = new DataDefaultsEnvironmentPostProcessor();

    @Test
    void liquibaseIsOffAtLowestPrecedence() {
        MockEnvironment empty = new MockEnvironment();
        processor.postProcessEnvironment(empty, application);
        assertThat(Objects
                .requireNonNull(empty.getPropertySources().get(DefaultPropertiesPropertySource.NAME))
                .getProperty(LIQUIBASE))
                .isEqualTo("false");
        assertThat(empty.getProperty(LIQUIBASE)).isEqualTo("false");
        assertThat(empty.getPropertySources().stream().map(source -> source.getName()).toList())
                .last()
                .isEqualTo(DefaultPropertiesPropertySource.NAME);

        MockEnvironment configured = new MockEnvironment().withProperty(LIQUIBASE, "true");
        processor.postProcessEnvironment(configured, application);
        assertThat(configured.getProperty(LIQUIBASE)).isEqualTo("true");
    }

    @Test
    void sqlInitIsOffAtLowestPrecedence() {
        MockEnvironment empty = new MockEnvironment();
        processor.postProcessEnvironment(empty, application);
        assertThat(Objects
                .requireNonNull(empty.getPropertySources().get(DefaultPropertiesPropertySource.NAME))
                .getProperty(SQL_INIT_MODE))
                .isEqualTo("never");

        MockEnvironment configured = new MockEnvironment().withProperty(SQL_INIT_MODE, "always");
        processor.postProcessEnvironment(configured, application);
        assertThat(configured.getProperty(SQL_INIT_MODE)).isEqualTo("always");
    }

    @Test
    void keepsAnApplicationsOwnDefault() {
        MockEnvironment environment = new MockEnvironment();
        Map<String, Object> own = new HashMap<>(Map.of(LIQUIBASE, "true", "other", "kept"));
        environment.getPropertySources().addLast(new MapPropertySource(DefaultPropertiesPropertySource.NAME, own));

        processor.postProcessEnvironment(environment, application);
        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty(LIQUIBASE)).isEqualTo("true");
        assertThat(environment.getProperty(SQL_INIT_MODE)).isEqualTo("never");
        assertThat(environment.getProperty("other")).isEqualTo("kept");
        assertThat(environment.getPropertySources().stream().filter(DefaultPropertiesPropertySource::hasMatchingName))
                .hasSize(1);
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
        assertThat(registered).anyMatch(names -> names.contains(DataDefaultsEnvironmentPostProcessor.class.getName()));
    }
}
