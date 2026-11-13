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
package io.americanexpress.synapse.samples.multistore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.transaction.annotation.Transactional;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The service has two stores and neither is primary, so a {@code @Transactional} that names no store can't choose a
 * transaction manager. Startup fails before any store connects, naming the method; without the check, its first call
 * would. No database is needed: the stores point at a closed port.
 */
class UnqualifiedTransactionalIT {

    @Test
    void unqualifiedTransactionalFailsStartup() {
        Throwable failure = catchThrowable(() -> new SpringApplicationBuilder(OrdersApplication.class,
                UnqualifiedService.class)
                .run("--spring.profiles.active=test", "--server.port=0",
                        "--synapse.data.stores.orders.url=jdbc:postgresql://127.0.0.1:1/orders",
                        "--synapse.data.stores.orders.username=orders", "--synapse.data.stores.orders.password=",
                        "--synapse.data.stores.ledger.url=jdbc:postgresql://127.0.0.1:1/ledger",
                        "--synapse.data.stores.ledger.username=ledger", "--synapse.data.stores.ledger.password=")
                .close());

        assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                .isInstanceOf(SynapseConfigurationException.class)
                .hasMessage("There are 2 stores and none is primary, so an unqualified @Transactional can't choose "
                        + "a transaction manager: " + UnqualifiedService.class.getName() + "#save.");
    }

    /** A bean whose {@code @Transactional} names no store. */
    static class UnqualifiedService {

        /** Saves nothing. */
        @Transactional
        public void save() {
        }
    }
}
