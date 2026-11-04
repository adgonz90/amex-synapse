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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.BurstFilter;
import org.apache.logging.log4j.core.filter.CompositeFilter;
import org.apache.logging.log4j.core.filter.MarkerFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.support.GenericApplicationContext;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailureBurstLimitListenerTest {

    private static final String LOGGER = FailureBurstLimitListener.FAILURE_LOGGER;

    private final LoggerContext context = (LoggerContext) LogManager.getContext(false);

    @AfterEach
    void removeTheFailureLoggerConfig() {
        context.getConfiguration().removeLogger(LOGGER);
        context.updateLoggers();
    }

    @Test
    void noBurstFilterByDefault() {
        publish();

        assertThat(context.getConfiguration().getLoggerConfig(LOGGER).getFilter()).isNull();
        assertThat(context.getConfiguration().getLoggerConfig(LOGGER).getName()).isNotEqualTo(LOGGER);
    }

    @Test
    void installsBurstFilterWhenConfigured() {
        publish("synapse.logging.failure-burst.rate=1", "synapse.logging.failure-burst.max-burst=2");

        LoggerConfig config = context.getConfiguration().getLoggerConfig(LOGGER);
        assertThat(config.getName()).isEqualTo(LOGGER);
        assertThat(config.isAdditive()).isTrue();
        assertThat(config.getFilter()).isInstanceOfSatisfying(BurstFilter.class, filter -> {
            assertThat(filter.getAvailable()).isEqualTo(2);
            assertThat(filter.toString()).contains("level=ERROR").contains("interval=2000000000");
        });
    }

    @Test
    void reusesAnExistingLoggerConfig() {
        Configuration configuration = context.getConfiguration();
        LoggerConfig existing = new LoggerConfig(LOGGER, Level.WARN, false);
        configuration.addLogger(LOGGER, existing);

        publish("synapse.logging.failure-burst.rate=5", "synapse.logging.failure-burst.max-burst=10");

        assertThat(configuration.getLoggerConfig(LOGGER)).isSameAs(existing);
        assertThat(existing.getFilter()).isInstanceOf(BurstFilter.class);
        assertThat(existing.isAdditive()).isFalse();
        assertThat(existing.getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void failsWhenOnlyOneBurstPropertyIsSet() {
        assertThatThrownBy(() -> publish("synapse.logging.failure-burst.rate=1"))
                .isInstanceOfSatisfying(SynapseConfigurationException.class,
                        failure -> assertThat(failure.description()).contains("rate").contains("max-burst"));
        assertThatThrownBy(() -> publish("synapse.logging.failure-burst.max-burst=2"))
                .isInstanceOf(SynapseConfigurationException.class);
        assertThat(context.getConfiguration().getLoggerConfig(LOGGER).getFilter()).isNull();
    }

    @Test
    void failsWhenAValueIsNotPositive() {
        assertThatThrownBy(
                () -> publish("synapse.logging.failure-burst.rate=0", "synapse.logging.failure-burst.max-burst=2"))
                .isInstanceOf(SynapseConfigurationException.class);
        assertThatThrownBy(
                () -> publish("synapse.logging.failure-burst.rate=1", "synapse.logging.failure-burst.max-burst=0"))
                .isInstanceOf(SynapseConfigurationException.class);
    }

    @Test
    void runsRightAfterBootsLoggingListener() {
        assertThat(new FailureBurstLimitListener().getOrder()).isEqualTo(LoggingApplicationListener.DEFAULT_ORDER + 1);
    }

    private static void publish(String... properties) {
        try (GenericApplicationContext application = new GenericApplicationContext()) {
            TestPropertyValues.of(properties).applyTo(application);
            new FailureBurstLimitListener()
                    .onApplicationEvent(new ApplicationPreparedEvent(new SpringApplication(), new String[0],
                            application));
        }
    }

    @Test
    void publishingTwiceLeavesOneBurstFilterWithTheLatestValues() {
        publish("synapse.logging.failure-burst.rate=1", "synapse.logging.failure-burst.max-burst=2");
        publish("synapse.logging.failure-burst.rate=4", "synapse.logging.failure-burst.max-burst=8");

        Filter filter = context.getConfiguration().getLoggerConfig(LOGGER).getFilter();
        assertThat(filter).isInstanceOfSatisfying(BurstFilter.class, burst -> {
            assertThat(burst.getAvailable()).isEqualTo(8);
            assertThat(burst.toString()).contains("interval=2000000000");
        });
    }

    @Test
    void replacesABurstFilterInsideACompositeAndKeepsOtherFilters() {
        LoggerConfig existing = new LoggerConfig(LOGGER, Level.WARN, true);
        existing.addFilter(BurstFilter.newBuilder().setRate(1).setMaxBurst(1).build());
        existing.addFilter(MarkerFilter.createFilter("keep", Filter.Result.NEUTRAL, Filter.Result.NEUTRAL));
        context.getConfiguration().addLogger(LOGGER, existing);

        publish("synapse.logging.failure-burst.rate=1", "synapse.logging.failure-burst.max-burst=2");

        Filter filter = existing.getFilter();
        assertThat(filter).isInstanceOf(CompositeFilter.class);
        assertThat(((CompositeFilter) filter).getFiltersArray())
                .filteredOn(BurstFilter.class::isInstance)
                .singleElement()
                .satisfies(burst -> assertThat(((BurstFilter) burst).getAvailable()).isEqualTo(2));
        assertThat(((CompositeFilter) filter).getFiltersArray()).hasSize(2).anyMatch(MarkerFilter.class::isInstance);
    }
}
