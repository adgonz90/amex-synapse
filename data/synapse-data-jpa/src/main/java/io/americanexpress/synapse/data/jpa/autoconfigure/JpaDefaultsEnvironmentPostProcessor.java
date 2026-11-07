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
package io.americanexpress.synapse.data.jpa.autoconfigure;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import io.americanexpress.synapse.data.internal.env.LowestPrecedenceDefaults;

/**
 * Turns off by default the Boot JPA features that don't fit Synapse stores:
 * <ul>
 * <li>Open-in-view ({@code spring.jpa.open-in-view=false}): it keeps an EntityManager open for a whole web request,
 * so lazy loading runs queries outside the operation.</li>
 * <li>Boot's JPA repositories ({@code spring.data.jpa.repositories.enabled=false}): they would scan the
 * application's packages for repositories, whatever store they belong to.</li>
 * </ul>
 * The defaults are the lowest-precedence property source, so any value the application sets wins.
 */
final class JpaDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String OPEN_IN_VIEW = "spring.jpa.open-in-view";

    static final String REPOSITORIES_ENABLED = "spring.data.jpa.repositories.enabled";

    private static final Map<String, Object> DEFAULTS = Map.of(OPEN_IN_VIEW, "false", REPOSITORIES_ENABLED, "false");

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
