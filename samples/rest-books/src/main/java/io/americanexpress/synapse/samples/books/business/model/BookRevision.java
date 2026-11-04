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
package io.americanexpress.synapse.samples.books.business.model;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * New details for a book of the catalog.
 *
 * @param id the id of the book
 * @param isbn the ISBN
 * @param title the title
 * @param author the author
 * @param year the year of publication, if known
 * @param version the version the caller based the change on, or {@code null} to check against the version the
 *        business operation reads
 */
public record BookRevision(UUID id, String isbn, String title, String author, @Nullable Integer year,
        @Nullable Long version) {
}
