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
package io.americanexpress.synapse.data.jdbc.autoconfigure;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import io.americanexpress.synapse.data.internal.env.LowestPrecedenceDefaults;

/**
 * Turns off Boot's Spring Data JDBC repositories by default ({@code spring.data.jdbc.repositories.enabled=false}):
 * they would scan the application's packages for repositories, whatever store they belong to, and resolve a dialect
 * over a connection. The default is the lowest-precedence property source, so a value the application sets wins.
 */
final class JdbcDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String REPOSITORIES_ENABLED = "spring.data.jdbc.repositories.enabled";

    private static final Map<String, Object> DEFAULTS = Map.of(REPOSITORIES_ENABLED, "false");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        LowestPrecedenceDefaults.add(environment, DEFAULTS);
    }

    /**
     * Runs right after Boot's config data loader, which moves the default properties to the end.
     *
     * @return {@link LowestPrecedenceDefaults#ORDER}
     */
    @Override
    public int getOrder() {
        return LowestPrecedenceDefaults.ORDER;
    }
}
