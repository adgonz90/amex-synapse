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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

class BoundOperationMocksCheckTest {

    private static final String PREFIX = "@SynapseRestTest: declare a @MockitoBean for each operation the bindings "
            + "use; missing: ";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CheckConfiguration.class);

    @Test
    void failsListingEachMissingOperationAndTheBindingsThatUseIt() {
        runner
                .withUserConfiguration(ThingsBindings.class)
                .run(context -> assertThat(context)
                        .getFailure()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(
                                PREFIX + "GetThingOperation, ListThingsOperation (bound by ThingsBindings.things)"));
    }

    @Test
    void startsWhenEveryBoundOperationHasABean() {
        runner
                .withUserConfiguration(ThingsBindings.class)
                .withBean(GetThingOperation.class)
                .withBean(ListThingsOperation.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(RestBindings.class));
    }

    @Test
    void listsOnlyTheOperationsWithoutABean() {
        runner
                .withUserConfiguration(ThingsBindings.class)
                .withBean(GetThingOperation.class)
                .run(context -> assertThat(
                        context)
                        .getFailure()
                        .hasMessage(PREFIX + "ListThingsOperation (bound by ThingsBindings.things)"));
    }

    @Test
    void groupsTheMissingOperationsByBindings() {
        runner
                .withUserConfiguration(ThingsBindings.class, OrderBindings.class)
                .run(context -> assertThat(context)
                        .getFailure()
                        .hasMessage(PREFIX + "GetThingOperation, ListThingsOperation (bound by ThingsBindings.things); "
                                + "PlaceOrderOperation (bound by OrderBindings.orders)"));
    }

    @Test
    void namesAnOperationBoundTwiceOnce() {
        runner
                .withUserConfiguration(TwiceBindings.class)
                .run(context -> assertThat(context)
                        .getFailure()
                        .hasMessage(PREFIX + "GetThingOperation (bound by TwiceBindings.things)"));
    }

    @Test
    void checksStaticFactoryMethods() {
        runner
                .withUserConfiguration(StaticBindings.class)
                .run(context -> assertThat(context)
                        .getFailure()
                        .hasMessage(PREFIX + "PlaceOrderOperation (bound by StaticBindings.orders)"));
    }

    @Test
    void ignoresBindingsWithoutAFactoryMethod() {
        runner
                .withBean(RestBindings.class, () -> RestBindings.builder().build())
                .withInitializer(context -> context
                        .getBeanFactory()
                        .registerSingleton("registeredBindings", RestBindings.builder().build()))
                .run(context -> assertThat(context).hasNotFailed().getBeans(RestBindings.class).hasSize(2));
    }

    @Test
    void runsAfterSpringTestBeanOverrides() {
        // spring-test's BeanOverrideBeanFactoryPostProcessor, which registers @MockitoBean doubles, is ordered
        // Ordered.LOWEST_PRECEDENCE - 10.
        assertThat(new BoundOperationMocksCheck().getOrder())
                .isEqualTo(Ordered.LOWEST_PRECEDENCE)
                .isGreaterThan(Ordered.LOWEST_PRECEDENCE - 10);
    }

    @Configuration(proxyBeanMethods = false)
    static class CheckConfiguration {

        @Bean
        static BoundOperationMocksCheck boundOperationMocksCheck() {
            return new BoundOperationMocksCheck();
        }
    }

    /** Uses the default proxied configuration class, whose CGLIB name must not reach the message. */
    @Configuration
    static class ThingsBindings {

        @Bean
        RestBindings things(GetThingOperation getThing, Environment environment, ListThingsOperation listThings) {
            return RestBindings.builder().build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderBindings {

        @Bean
        RestBindings orders(PlaceOrderOperation placeOrder) {
            return RestBindings.builder().build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwiceBindings {

        @Bean
        RestBindings things(GetThingOperation first, GetThingOperation second) {
            return RestBindings.builder().build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StaticBindings {

        @Bean
        static RestBindings orders(PlaceOrderOperation placeOrder) {
            return RestBindings.builder().build();
        }
    }

    static class GetThingOperation implements QueryOperation<String, String> {
        @Override
        public String execute(String input) {
            return input;
        }
    }

    static class ListThingsOperation implements QueryOperation<String, List<String>> {
        @Override
        public List<String> execute(String input) {
            return List.of(input);
        }
    }

    static class PlaceOrderOperation implements CommandOperation<String, String> {
        @Override
        public String execute(String input) {
            return input;
        }
    }
}
