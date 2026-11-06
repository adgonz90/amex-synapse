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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.internal.environment.ConfigurationReport;
import io.americanexpress.synapse.core.internal.environment.EnvironmentName;

/**
 * Logs the startup configuration report ({@link ConfigurationReport}) once the application context has been
 * refreshed, so sources added during refresh are listed too. The report is one INFO event on the logger
 * {@value #LOGGER_NAME}; it names every source and never prints a value.
 * <p>
 * Each {@code SpringApplication} has its own instance of this listener, and Spring delivers a child context's events
 * to its parent context's listeners as well. In a context hierarchy (for example
 * {@code SpringApplicationBuilder.child(...)}), the child's report is therefore logged twice; this is accepted.
 */
final class ConfigurationReportListener implements ApplicationListener<ApplicationStartedEvent> {

    /** The logger of the report. */
    static final String LOGGER_NAME = "io.americanexpress.synapse.environment";

    private static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        if (!LOGGER.isInfoEnabled()) {
            return;
        }
        ConfigurableEnvironment environment = event.getApplicationContext().getEnvironment();
        EnvironmentName name;
        try {
            name = EnvironmentName.resolve(environment, event.getSpringApplication().getAdditionalProfiles());
        } catch (SynapseConfigurationException ex) {
            // The post-processor validated the environment at startup, so only something that changed
            // spring.profiles.active since then gets here. Throwing would close the started application.
            return;
        }
        LOGGER.info("{}", ConfigurationReport.render(environment, name));
    }
}
