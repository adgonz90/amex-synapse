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
package ${package}.operation;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import ${package}.contract.Item;
import ${package}.data.${serviceName}.StoredItem;

/** Maps the ${serviceName} store's entity to the contract's record. */
final class ItemMapping {

    private ItemMapping() {
    }

    /**
     * Maps a saved item. Its id and version are set once it is saved, and its name is not null; the getters are
     * nullable only because a new entity has none of them yet.
     *
     * @param item a saved item
     * @return the record
     */
    static Item toItem(StoredItem item) {
        return new Item(saved(item.getId(), "id"), saved(item.getName(), "name"), item.getDescription(),
                saved(item.getVersion(), "version"));
    }

    private static <T> T saved(@Nullable T value, String name) {
        return Objects.requireNonNull(value, () -> "A saved item has its " + name);
    }
}
