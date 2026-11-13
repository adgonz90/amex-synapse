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
package io.americanexpress.synapse.test.rest;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.test.slice.BoundOperationMocksCheck;

/**
 * Auto-configuration of {@link SynapseRestTest}: the check that each operation the bindings use has a test double.
 * Active in servlet web applications only, like the REST plugin. It is imported by the slice only, never by an
 * application.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class SynapseRestTestAutoConfiguration {

    /**
     * The check that each operation the bindings use has a bean. It is {@code static} because it is a bean factory
     * post-processor. Define a bean named {@code synapseRestBoundOperationMocksCheck} to replace it; the name, not the type, backs
     * it off, so that the gRPC slice's check does not hide this one.
     *
     * @return the check
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseRestBoundOperationMocksCheck")
    static BoundOperationMocksCheck synapseRestBoundOperationMocksCheck() {
        return new BoundOperationMocksCheck(RestBindings.class, "@SynapseRestTest");
    }
}
