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

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.paging.PageQuery;

/**
 * Searches the catalog, one page at a time.
 *
 * @param author part of the author's name, ignoring case, or {@code null} for every book
 * @param page which page to return: a cursor or an offset, the page size, and the sort keys (names of {@link SortKey}
 *        constants). Over REST it comes from the {@code cursor}, {@code offset}, {@code size} and {@code sort}
 *        query parameters
 */
public record SearchBooksRequest(@Nullable String author, PageQuery page) {

    /**
     * What a search can be sorted by. Callers send the names in lowerCamel ({@code sort=title}, {@code sort=-year} for
     * descending); the operation receives the constant names ({@code TITLE}, {@code YEAR}).
     */
    public enum SortKey {

        /** The title. */
        TITLE,

        /** The year of publication; books without one come last. */
        YEAR
    }
}
