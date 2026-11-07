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
package io.americanexpress.synapse.data.autoconfigure;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import io.americanexpress.synapse.data.internal.env.LowestPrecedenceDefaults;

/**
 * Turns off by default the Boot features that would touch a store's database while its beans are created:
 * <ul>
 * <li>Liquibase ({@code spring.liquibase.enabled=false}): Synapse stores don't support it yet, and Boot's would
 * migrate the primary store.</li>
 * <li>SQL initialization ({@code spring.sql.init.mode=never}): Boot's would run {@code schema.sql} and
 * {@code data.sql} against a single or primary store, and check whether it is embedded over a connection.</li>
 * </ul>
 * The defaults are the lowest-precedence property source, so any value the application sets wins.
 */
final class DataDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String LIQUIBASE_ENABLED = "spring.liquibase.enabled";

    static final String SQL_INIT_MODE = "spring.sql.init.mode";

    private static final Map<String, Object> DEFAULTS = Map.of(LIQUIBASE_ENABLED, "false", SQL_INIT_MODE, "never");

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
