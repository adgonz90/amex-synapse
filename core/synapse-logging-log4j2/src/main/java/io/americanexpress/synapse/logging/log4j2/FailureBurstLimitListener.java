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
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * Limits operation failure logs when {@code synapse.logging.failure-burst.rate} and {@code max-burst} are set. It
 * installs a Log4j2 {@link BurstFilter} on the {@code io.americanexpress.synapse.operation} logger configuration.
 *
 * <p>
 * The filter's level is {@code ERROR}: Log4j2 limits events at that level or less severe, so the WARN lines of
 * client-caused failures and the ERROR lines of internal failures are limited, while FATAL lines are never dropped.
 * The listener runs on {@link ApplicationPreparedEvent}, after {@link LoggingApplicationListener} has initialized
 * Log4j2 from the final environment, so Boot's reconfiguration does not discard the filter.
 */
final class FailureBurstLimitListener implements ApplicationListener<ApplicationPreparedEvent>, Ordered {

    static final String FAILURE_LOGGER = "io.americanexpress.synapse.operation";

    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        SynapseLoggingProperties properties = Binder
                .get(event.getApplicationContext().getEnvironment())
                .bind(SynapseLoggingProperties.PREFIX, SynapseLoggingProperties.class)
                .orElse(new SynapseLoggingProperties(null, null));
        Float rate = properties.rate();
        Integer maxBurst = properties.maxBurst();
        if (rate == null && maxBurst == null) {
            return;
        }
        if (rate == null || maxBurst == null) {
            throw new SynapseConfigurationException(
                    "Only one of " + SynapseLoggingProperties.PREFIX + ".rate and " + SynapseLoggingProperties.PREFIX
                            + ".max-burst is set.",
                    "Set both to limit operation failure logs, or neither for no limit.");
        }
        if (rate <= 0 || maxBurst <= 0) {
            throw new SynapseConfigurationException(
                    SynapseLoggingProperties.PREFIX + ".rate and " + SynapseLoggingProperties.PREFIX
                            + ".max-burst must be greater than zero.",
                    "Set positive values, or remove both for no limit.");
        }
        install(rate, maxBurst);
    }

    private static void install(float rate, int maxBurst) {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        LoggerConfig loggerConfig = configuration.getLoggerConfig(FAILURE_LOGGER);
        if (!FAILURE_LOGGER.equals(loggerConfig.getName())) {
            // Only an ancestor is configured: add one for the failure logger, additive, level inherited.
            loggerConfig = new LoggerConfig(FAILURE_LOGGER, null, true);
            configuration.addLogger(FAILURE_LOGGER, loggerConfig);
        }
        removeBurstFilters(loggerConfig);
        loggerConfig
                .addFilter(BurstFilter.newBuilder().setLevel(Level.ERROR).setRate(rate).setMaxBurst(maxBurst).build());
        context.updateLoggers();
    }

    /**
     * The event fires once per application (child contexts, restarts) and the Log4j2 context is shared, so a filter
     * from an earlier run is replaced rather than stacked.
     */
    private static void removeBurstFilters(LoggerConfig loggerConfig) {
        Filter current = loggerConfig.getFilter();
        if (current instanceof BurstFilter) {
            loggerConfig.removeFilter(current);
        } else if (current instanceof CompositeFilter composite) {
            for (Filter filter : composite.getFiltersArray()) {
                if (filter instanceof BurstFilter) {
                    loggerConfig.removeFilter(filter);
                }
            }
        }
    }

    /**
     * Runs right after Boot's {@link LoggingApplicationListener}.
     *
     * @return the logging listener's order plus one
     */
    @Override
    public int getOrder() {
        return LoggingApplicationListener.DEFAULT_ORDER + 1;
    }
}
