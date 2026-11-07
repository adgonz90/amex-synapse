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

class GrpcDefaultsEnvironmentPostProcessorTest {

    private static final String METADATA_SIZE = "spring.grpc.server.inbound.metadata.max-size";

    private static final String REFLECTION = "spring.grpc.server.reflection.enabled";

    private final SpringApplication application = new SpringApplication();

    @Test
    void defaultsMetadataSizeAndReflectionAtLowestPrecedence() {
        MockEnvironment empty = new MockEnvironment();
        new GrpcDefaultsEnvironmentPostProcessor().postProcessEnvironment(empty, application);
        assertThat(empty.getProperty(METADATA_SIZE)).isEqualTo("16KB");
        assertThat(empty.getProperty(REFLECTION)).isEqualTo("false");
        assertThat(empty.getPropertySources().stream().map(source -> source.getName()).toList())
                .last()
                .isEqualTo(DefaultPropertiesPropertySource.NAME);

        MockEnvironment configured = new MockEnvironment()
                .withProperty(METADATA_SIZE, "32KB")
                .withProperty(REFLECTION, "true");
        new GrpcDefaultsEnvironmentPostProcessor().postProcessEnvironment(configured, application);
        assertThat(configured.getProperty(METADATA_SIZE)).isEqualTo("32KB");
        assertThat(configured.getProperty(REFLECTION)).isEqualTo("true");

        MockEnvironment appDefaults = new MockEnvironment();
        DefaultPropertiesPropertySource
                .addOrMerge(Map.of(METADATA_SIZE, "24KB", REFLECTION, "true"),
                        appDefaults.getPropertySources());
        new GrpcDefaultsEnvironmentPostProcessor().postProcessEnvironment(appDefaults, application);
        assertThat(appDefaults.getProperty(METADATA_SIZE)).isEqualTo("24KB");
        assertThat(appDefaults.getProperty(REFLECTION)).isEqualTo("true");
    }

    @Test
    void anAppDefaultForOneKeyKeepsTheSynapseDefaultForTheOther() {
        MockEnvironment sizeOnly = new MockEnvironment();
        DefaultPropertiesPropertySource.addOrMerge(Map.of(METADATA_SIZE, "24KB"), sizeOnly.getPropertySources());
        new GrpcDefaultsEnvironmentPostProcessor().postProcessEnvironment(sizeOnly, application);
        assertThat(sizeOnly.getProperty(METADATA_SIZE)).isEqualTo("24KB");
        assertThat(sizeOnly.getProperty(REFLECTION)).isEqualTo("false");

        MockEnvironment reflectionOnly = new MockEnvironment();
        DefaultPropertiesPropertySource.addOrMerge(Map.of(REFLECTION, "true"), reflectionOnly.getPropertySources());
        new GrpcDefaultsEnvironmentPostProcessor().postProcessEnvironment(reflectionOnly, application);
        assertThat(reflectionOnly.getProperty(METADATA_SIZE)).isEqualTo("16KB");
        assertThat(reflectionOnly.getProperty(REFLECTION)).isEqualTo("true");
    }

    @Test
    void runningTwiceKeepsASingleDefaultPropertiesSource() {
        MockEnvironment environment = new MockEnvironment();
        GrpcDefaultsEnvironmentPostProcessor processor = new GrpcDefaultsEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, application);
        processor.postProcessEnvironment(environment, application);
        assertThat(environment.getPropertySources().stream().filter(DefaultPropertiesPropertySource::hasMatchingName))
                .hasSize(1);
        assertThat(environment.getProperty(METADATA_SIZE)).isEqualTo("16KB");
        assertThat(environment.getProperty(REFLECTION)).isEqualTo("false");
    }

    @Test
    void runsAfterConfigDataLoading() {
        assertThat(new GrpcDefaultsEnvironmentPostProcessor().getOrder())
                .isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
    }

    @Test
    void isRegisteredInSpringFactories() throws IOException {
        Properties factories = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/META-INF/spring.factories")) {
            factories.load(Objects.requireNonNull(in));
        }
        assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
                .contains(GrpcDefaultsEnvironmentPostProcessor.class.getName());
    }
}
