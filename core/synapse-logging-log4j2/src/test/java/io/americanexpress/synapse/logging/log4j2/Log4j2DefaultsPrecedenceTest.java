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
package io.americanexpress.synapse.logging.log4j2;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs real applications to prove the ECS default sits below everything the application configures: config files
 * (which Boot's config data loader appends after earlier post-processors) and default properties.
 */
class Log4j2DefaultsPrecedenceTest {

    private static final String FORMAT = "logging.structured.format.console";

    @Test
    void ecsWhenNothingIsSet() {
        assertThat(formatOf(application("log4j2-defaults-none"))).isEqualTo("ecs");
    }

    @Test
    void applicationPropertiesBeatTheDefault() {
        assertThat(formatOf(application("log4j2-defaults-test"))).isEqualTo("logstash");
    }

    @Test
    void defaultPropertiesBeatTheDefault() {
        SpringApplication application = application("log4j2-defaults-none");
        application.setDefaultProperties(Map.of(FORMAT, "logstash"));
        assertThat(formatOf(application)).isEqualTo("logstash");
    }

    private static SpringApplication application(String configName) {
        SpringApplication application = new SpringApplication(Empty.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        System.setProperty("spring.config.name", configName);
        return application;
    }

    private static @Nullable String formatOf(SpringApplication application) {
        try (ConfigurableApplicationContext context = application.run()) {
            return context.getEnvironment().getProperty(FORMAT);
        } finally {
            System.clearProperty("spring.config.name");
        }
    }

    static class Empty {
    }
}
