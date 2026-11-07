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

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A book of the catalog in version 2 of the API: the authors are a list, and the channel the book was added through is
 * no longer part of it. Version 1 ({@link Book}) is unchanged.
 *
 * @param id the id
 * @param isbn the ISBN, unique in the catalog
 * @param title the title
 * @param authors the authors, in the order the catalog lists them
 * @param year the year of publication, if known
 * @param version the version: 1 when the book is added, plus 1 on every change. Over REST it is also the book's
 *        {@code ETag}
 */
public record BookV2(UUID id, String isbn, String title, List<String> authors, @Nullable Integer year, long version) {

    /**
     * Copies the authors, so that the record can't change.
     *
     * @param id the id
     * @param isbn the ISBN
     * @param title the title
     * @param authors the authors
     * @param year the year of publication, if known
     * @param version the version
     */
    public BookV2 {
        authors = List.copyOf(authors);
    }
}
