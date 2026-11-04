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

import org.jspecify.annotations.Nullable;

/**
 * A book of the catalog.
 *
 * @param id the id
 * @param isbn the ISBN, unique in the catalog
 * @param title the title
 * @param author the author
 * @param year the year of publication, if known
 * @param addedVia the channel the book was added through: the {@code X-Channel} header of the {@code POST}, taken from
 *        the request context (a different body value is rejected), or {@code null} when the caller sent none
 */
public record Book(UUID id, String isbn, String title, String author, @Nullable Integer year,
        @Nullable String addedVia) {
}
