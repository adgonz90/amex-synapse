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
package io.americanexpress.synapse.samples.inventory;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.test.architecture.SynapseArchitecture;

/**
 * The service follows Synapse's layering rules (api, operation, data, contract). The root package includes the
 * contract module's {@code contract} package, which must stay protocol-free; the classes generated from
 * {@code inventory.proto} (its {@code proto} package) are exempt.
 */
class ArchitectureTest {

    @Test
    void followsSynapseArchitecture() {
        SynapseArchitecture.forApplication(InventoryApplication.class).check();
    }
}
