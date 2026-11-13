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
package io.americanexpress.synapse.api.rest.openapi.internal.aot;

import org.jspecify.annotations.Nullable;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.env.Environment;

import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitch;

/**
 * The build-time switch for {@value #PROPERTY}. springdoc defines its web MVC configuration
 * ({@link SpringDocWebMvcConfiguration}), and with it the document endpoint, only when the property is not
 * {@code false}, and under AOT the bean definitions are fixed by the build, so an environment file that changes the
 * value would otherwise do nothing in a native image or AOT run. The value as resolved now is compared with whether the
 * configuration bean exists. The configuration carries the property's condition itself; which endpoint it defines
 * depends on other properties ({@code springdoc.use-management-port} gives the actuator endpoint, and
 * {@code springdoc.enable-default-api-docs=false} none of the default one), so no endpoint bean tells whether the
 * document was on in the build. The property is read as springdoc's
 * {@code @ConditionalOnProperty(matchIfMissing = true)} matches it: off for {@code false} (in any case), on when unset
 * or any other value. Outside AOT mode the property decides at startup, and core's {@code BuildTimeSwitchCheck} asks no
 * switch.
 */
public final class OpenApiAotCheck implements BuildTimeSwitch {

    /** springdoc's property that turns the OpenAPI document on. */
    static final String PROPERTY = "springdoc.api-docs.enabled";

    private final Environment environment;

    private final ListableBeanFactory beans;

    /**
     * Creates the switch.
     *
     * @param environment the environment, for the value of {@value #PROPERTY} now
     * @param beans the bean factory, for whether the build defined springdoc's web MVC configuration
     */
    public OpenApiAotCheck(Environment environment, ListableBeanFactory beans) {
        this.environment = environment;
        this.beans = beans;
    }

    /**
     * Compares the property with the build.
     *
     * @return the mismatch when the property's value differs from the one the build used, or {@code null} when they
     *     agree
     */
    @Override
    public @Nullable Mismatch mismatch() {
        boolean now = !"false".equalsIgnoreCase(environment.getProperty(PROPERTY, "true"));
        boolean build = beans.getBeanNamesForType(SpringDocWebMvcConfiguration.class, true, false).length > 0;
        return now == build ? null : new Mismatch(PROPERTY, String.valueOf(now), String.valueOf(build));
    }
}
