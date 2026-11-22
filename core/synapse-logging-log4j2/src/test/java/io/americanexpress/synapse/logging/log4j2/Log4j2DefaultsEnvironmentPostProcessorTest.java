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

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Log4j2DefaultsEnvironmentPostProcessorTest {

    private static final String FORMAT = "logging.structured.format.console";

    private final SpringApplication application = new SpringApplication();

    @Test
    void defaultsStructuredFormatToEcsAtLowestPrecedence() {
        MockEnvironment empty = new MockEnvironment();
        new Log4j2DefaultsEnvironmentPostProcessor().postProcessEnvironment(empty, application);
        assertThat(empty.getProperty(FORMAT)).isEqualTo("ecs");

        MockEnvironment configured = new MockEnvironment().withProperty(FORMAT, "logstash");
        new Log4j2DefaultsEnvironmentPostProcessor().postProcessEnvironment(configured, application);
        assertThat(configured.getProperty(FORMAT)).isEqualTo("logstash");
        assertThat(configured.getPropertySources().stream().map(source -> source.getName()).toList())
                .last()
                .isEqualTo(DefaultPropertiesPropertySource.NAME);
    }

    @Test
    void runningTwiceKeepsASingleDefaultPropertiesSource() {
        MockEnvironment environment = new MockEnvironment();
        Log4j2DefaultsEnvironmentPostProcessor processor = new Log4j2DefaultsEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, application);
        processor.postProcessEnvironment(environment, application);
        assertThat(environment.getPropertySources().stream().filter(DefaultPropertiesPropertySource::hasMatchingName))
                .hasSize(1);
        assertThat(environment.getProperty(FORMAT)).isEqualTo("ecs");
    }

    @Test
    void failsWhenLogbackIsPresent() {
        ClassLoader withLogback = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("ch.qos.logback.classic.LoggerContext")) {
                    return Object.class;
                }
                return super.loadClass(name);
            }
        };
        Log4j2DefaultsEnvironmentPostProcessor processor = new Log4j2DefaultsEnvironmentPostProcessor(withLogback);
        MockEnvironment environment = new MockEnvironment();

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                    assertThat(failure.description()).contains("Logback");
                    assertThat(failure.action()).contains("spring-boot-starter-logging");
                });
        assertThat(environment.containsProperty(FORMAT)).isFalse();
    }

    @Test
    void usesTheApplicationClassLoaderWhenNoneIsGiven() {
        SpringApplication custom = new SpringApplication();
        custom.setResourceLoader(new org.springframework.core.io.DefaultResourceLoader(getClass().getClassLoader()));
        MockEnvironment environment = new MockEnvironment();
        new Log4j2DefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, custom);
        assertThat(environment.getProperty(FORMAT)).isEqualTo("ecs");
    }

    @Test
    void runsAfterConfigDataAndBeforeLogging() {
        assertThat(new Log4j2DefaultsEnvironmentPostProcessor().getOrder())
                .isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
        // All post-processors run in one listener, which Boot orders before the logging listener.
        assertThat(EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER)
                .isLessThan(org.springframework.boot.context.logging.LoggingApplicationListener.DEFAULT_ORDER);
    }

    @Test
    void logbackFailureIsRenderedByTheSynapseFailureAnalyzerWithTheAction() {
        ClassLoader withLogback = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                return name.equals("ch.qos.logback.classic.LoggerContext") ? Object.class : super.loadClass(name);
            }
        };
        Throwable failure = org.assertj.core.api.Assertions
                .catchThrowable(
                        () -> new Log4j2DefaultsEnvironmentPostProcessor(withLogback)
                                .postProcessEnvironment(new MockEnvironment(), application));

        FailureAnalysis analysis = SpringFactoriesLoader
                .forDefaultResourceLocation(getClass().getClassLoader())
                .load(FailureAnalyzer.class,
                        SpringFactoriesLoader.ArgumentResolver
                                .of(org.springframework.beans.factory.BeanFactory.class,
                                        new org.springframework.beans.factory.support.DefaultListableBeanFactory())
                                .and(org.springframework.core.env.Environment.class, new MockEnvironment()),
                        SpringFactoriesLoader.FailureHandler
                                .logging(org.apache.commons.logging.LogFactory.getLog(getClass())))
                .stream()
                .map(analyzer -> analyzer.analyze(failure))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
        assertThat(analysis.getDescription()).contains("Logback");
        assertThat(analysis.getAction()).contains("Exclude spring-boot-starter-logging");
    }
}
