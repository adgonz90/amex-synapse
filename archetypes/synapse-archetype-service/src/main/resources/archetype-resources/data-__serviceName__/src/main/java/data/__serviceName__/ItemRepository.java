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
package ${package}.data.${serviceName};

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The ${serviceName} store's repository. It is in the store's package, so Synapse registers it on the ${serviceName}
 * store (no {@code @EnableJpaRepositories}), and its own methods run in transactions of the ${serviceName} store.
 */
public interface ItemRepository extends JpaRepository<StoredItem, UUID> {

    /**
     * Returns whether an item has the name. The table's unique constraint is what keeps names unique: an item saved
     * with a taken one fails with Synapse's {@code ALREADY_EXISTS}, whether or not this was asked first.
     *
     * @param name the name
     * @return {@code true} if an item has it
     */
    boolean existsByName(String name);
}
