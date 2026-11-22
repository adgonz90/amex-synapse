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
package ${package}.contract;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An item, as the operations return it: the REST body, and the gRPC {@code Item} message.
 *
 * @param id the id, a UUIDv7 the store assigns when the item is added
 * @param name the name, unique among the items
 * @param description the description, or {@code null} when it has none
 * @param version 0 when the item is added, plus 1 on every change. It is also the item's entity tag, {@code "0"} with
 *        its double quotes: REST's {@code ETag} header and the gRPC {@code etag} field of a read or a create
 */
public record Item(UUID id, String name, @Nullable String description, long version) {
}
