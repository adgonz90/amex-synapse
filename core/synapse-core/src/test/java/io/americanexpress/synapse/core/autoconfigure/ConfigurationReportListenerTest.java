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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationReportListenerTest {

    private static final String LOGGER = "io.americanexpress.synapse.environment";

    @Test
    void logsTheReportAsOneInfoEventOnTheEnvironmentLogger() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("fixture", Map.of("spring.profiles.active", "e2")));
        List<ILoggingEvent> events = publish(environment, Level.INFO);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLoggerName()).isEqualTo(LOGGER);
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .startsWith("Synapse environment: e2 (spring.profiles.active=e2, fixture)\n"
                            + "Configuration, highest priority first:\n  1. fixture\n");
        });
    }

    @Test
    void doesNothingWhenInfoIsOff() {
        // No environment is set, so resolving one would fail: with INFO off, the listener never gets that far.
        assertThat(publish(new StandardEnvironment(), Level.WARN)).isEmpty();
    }

    /**
     * The post-processor validated the environment at startup. Something that changes {@code spring.profiles.active}
     * later must not make the listener throw, which would close the started application.
     */
    @Test
    void skipsTheReportWhenTheEnvironmentNoLongerResolves() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("fixture", Map.of("spring.profiles.active", "e2,e3")));
        assertThat(publish(environment, Level.INFO)).isEmpty();
    }

    /** Publishes a started event for a context with the given environment, with the report logger at a level. */
    private static List<ILoggingEvent> publish(StandardEnvironment environment, Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(LOGGER);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(level);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.setEnvironment(environment);
            new ConfigurationReportListener()
                    .onApplicationEvent(
                            new ApplicationStartedEvent(new SpringApplication(), new String[0], context,
                                    Duration.ZERO));
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
        }
        return appender.list;
    }
}
