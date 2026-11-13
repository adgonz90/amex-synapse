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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.test.slice.BoundOperationMocksCheck;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseRestTestAutoConfigurationTest {

    private static final BoundOperationMocksCheck USER_CHECK = new BoundOperationMocksCheck(Object.class, "user");

    private static final AutoConfigurations CONFIGURATION = AutoConfigurations
            .of(SynapseRestTestAutoConfiguration.class);

    @Test
    void restSliceRegistersItsCheck() {
        new WebApplicationContextRunner()
                .withConfiguration(CONFIGURATION)
                .run(context -> assertThat(context)
                        .hasSingleBean(BoundOperationMocksCheck.class)
                        .hasBean("synapseRestBoundOperationMocksCheck"));
    }

    @Test
    void aUserBeanWithTheSameNameReplacesTheCheck() {
        new WebApplicationContextRunner()
                .withConfiguration(CONFIGURATION)
                .withUserConfiguration(UserCheckConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(BoundOperationMocksCheck.class)
                        .hasBean("synapseRestBoundOperationMocksCheck")
                        .getBean("synapseRestBoundOperationMocksCheck")
                        .isSameAs(USER_CHECK));
    }

    @Test
    void isAbsentOutsideServletWebApplications() {
        new ApplicationContextRunner()
                .withConfiguration(CONFIGURATION)
                .run(context -> assertThat(context).doesNotHaveBean(BoundOperationMocksCheck.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class UserCheckConfiguration {

        @Bean
        static BoundOperationMocksCheck synapseRestBoundOperationMocksCheck() {
            return USER_CHECK;
        }
    }
}
