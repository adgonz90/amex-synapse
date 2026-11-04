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

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class RestDefaultsEnvironmentPostProcessorTest {

    private static final String SIZE = "server.max-http-request-header-size";

    private final SpringApplication application = new SpringApplication();

    @Test
    void defaultsHeaderSizeTo16KbAtLowestPrecedence() {
        MockEnvironment empty = new MockEnvironment();
        new RestDefaultsEnvironmentPostProcessor().postProcessEnvironment(empty, application);
        assertThat(empty.getProperty(SIZE)).isEqualTo("16KB");
        assertThat(empty.getPropertySources().stream().map(source -> source.getName()).toList())
                .last()
                .isEqualTo(DefaultPropertiesPropertySource.NAME);

        MockEnvironment configured = new MockEnvironment().withProperty(SIZE, "32KB");
        new RestDefaultsEnvironmentPostProcessor().postProcessEnvironment(configured, application);
        assertThat(configured.getProperty(SIZE)).isEqualTo("32KB");

        MockEnvironment appDefaults = new MockEnvironment();
        DefaultPropertiesPropertySource.addOrMerge(Map.of(SIZE, "24KB"), appDefaults.getPropertySources());
        new RestDefaultsEnvironmentPostProcessor().postProcessEnvironment(appDefaults, application);
        assertThat(appDefaults.getProperty(SIZE)).isEqualTo("24KB");
    }

    @Test
    void runningTwiceKeepsASingleDefaultPropertiesSource() {
        MockEnvironment environment = new MockEnvironment();
        RestDefaultsEnvironmentPostProcessor processor = new RestDefaultsEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, application);
        processor.postProcessEnvironment(environment, application);
        assertThat(environment.getPropertySources().stream().filter(DefaultPropertiesPropertySource::hasMatchingName))
                .hasSize(1);
        assertThat(environment.getProperty(SIZE)).isEqualTo("16KB");
    }

    @Test
    void runsAfterConfigDataLoading() {
        assertThat(new RestDefaultsEnvironmentPostProcessor().getOrder())
                .isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
    }

    @Test
    void isRegisteredInSpringFactories() throws IOException {
        Properties factories = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/META-INF/spring.factories")) {
            factories.load(Objects.requireNonNull(in));
        }
        assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
                .contains(RestDefaultsEnvironmentPostProcessor.class.getName());
    }
}
