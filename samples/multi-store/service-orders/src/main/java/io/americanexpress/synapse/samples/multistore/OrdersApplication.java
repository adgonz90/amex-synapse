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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The orders service. Its component scan covers {@code io.americanexpress.synapse.samples.multistore}, which includes
 * the packages of the two data modules, {@code data.orders} and {@code data.ledger}: that is how their
 * {@code @SynapseDataStore} classes are found.
 */
@SpringBootApplication
public class OrdersApplication {

    /**
     * Starts the service.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }
}
