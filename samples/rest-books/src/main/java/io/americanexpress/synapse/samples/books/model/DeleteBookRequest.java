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
package io.americanexpress.synapse.samples.books.model;

import java.util.UUID;

import io.americanexpress.synapse.core.conditional.VersionCondition;

/**
 * Removes a book from the catalog.
 *
 * @param id the id of the book
 * @param version the version the caller expects the book to have. It isn't {@code @Nullable}, so a delete must name
 *        one: that is part of this operation's contract, which every protocol enforces before the operation runs with
 *        {@code PRECONDITION_REQUIRED} (over REST, a {@code DELETE} without {@code If-Match} is 428). REST fills it
 *        from {@code If-Match}: {@code "1"} is {@code Version(1)} and {@code *} is {@code AnyVersion}, which checks
 *        against the version the business operation reads
 */
public record DeleteBookRequest(UUID id, VersionCondition<Long> version) {
}
