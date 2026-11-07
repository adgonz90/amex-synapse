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
package io.americanexpress.synapse.data.jpa.fixture.ownrepository;

import org.springframework.data.repository.Repository;

/**
 * A repository the store's class defines itself. Spring Data couldn't derive a query for {@link #owner()}, so a
 * context only starts when Synapse leaves the user's bean in place.
 */
public interface ShelfRepository extends Repository<Shelf, Long> {

    /**
     * Returns who made the bean.
     *
     * @return the owner
     */
    String owner();
}
