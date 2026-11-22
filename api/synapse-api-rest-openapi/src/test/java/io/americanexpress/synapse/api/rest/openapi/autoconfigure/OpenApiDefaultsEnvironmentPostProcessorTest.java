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
package io.americanexpress.synapse.api.rest.openapi.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiDefaultsEnvironmentPostProcessorTest {

    private static final String SWAGGER_UI = "springdoc.swagger-ui.enabled";

    private static final String API_DOCS = "springdoc.api-docs.enabled";

    private static final String VERSION = "springdoc.api-docs.version";

    private final SpringApplication application = new SpringApplication();

    private final OpenApiDefaultsEnvironmentPostProcessor processor = new OpenApiDefaultsEnvironmentPostProcessor();

    @Test
    void setsSpringdocDefaultsAtLowestPrecedence() {
        MockEnvironment empty = new MockEnvironment();
        processor.postProcessEnvironment(empty, application);
        assertThat(empty.getProperty(SWAGGER_UI)).isEqualTo("false");
        // Off by default, as every exposure is: an environment's file turns it on.
        assertThat(empty.getProperty(API_DOCS)).isEqualTo("false");
        assertThat(empty.getProperty(VERSION)).isEqualTo("openapi_3_1");
        assertThat(empty.getPropertySources().stream().map(source -> source.getName()).toList())
                .last()
                .isEqualTo(DefaultPropertiesPropertySource.NAME);

        // As an environment's file turns the document on: any source above the defaults wins.
        MockEnvironment configured = new MockEnvironment();
        configured
                .getPropertySources()
                .addLast(new MapPropertySource("application-local.yaml", Map.of(API_DOCS, "true")));
        processor.postProcessEnvironment(configured, application);
        assertThat(configured.getProperty(API_DOCS)).isEqualTo("true");
        assertThat(configured.getProperty(SWAGGER_UI)).isEqualTo("false");

        // The application's own default properties keep their value for that key; the other keys are added.
        MockEnvironment appDefaults = new MockEnvironment();
        DefaultPropertiesPropertySource
                .addOrMerge(new HashMap<>(Map.of(VERSION, "openapi_3_0")), appDefaults.getPropertySources());
        processor.postProcessEnvironment(appDefaults, application);
        assertThat(appDefaults.getProperty(VERSION)).isEqualTo("openapi_3_0");
        assertThat(appDefaults.getProperty(SWAGGER_UI)).isEqualTo("false");
        assertThat(appDefaults.getPropertySources().stream().filter(DefaultPropertiesPropertySource::hasMatchingName))
                .hasSize(1);
    }

    @Test
    void runningTwiceKeepsASingleDefaultPropertiesSource() {
        MockEnvironment environment = new MockEnvironment();
        processor.postProcessEnvironment(environment, application);
        processor.postProcessEnvironment(environment, application);
        assertThat(environment.getPropertySources().stream().filter(DefaultPropertiesPropertySource::hasMatchingName))
                .hasSize(1);
        assertThat(environment.getProperty(VERSION)).isEqualTo("openapi_3_1");
    }

    @Test
    void runsAfterConfigDataLoading() {
        assertThat(processor.getOrder()).isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
    }

    @Test
    void isRegisteredInSpringFactories() throws IOException {
        Properties factories = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/META-INF/spring.factories")) {
            factories.load(Objects.requireNonNull(in));
        }
        assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
                .contains(OpenApiDefaultsEnvironmentPostProcessor.class.getName());
    }
}
