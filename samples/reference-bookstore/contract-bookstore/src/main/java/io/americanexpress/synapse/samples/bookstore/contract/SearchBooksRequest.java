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
package io.americanexpress.synapse.samples.bookstore.contract;

import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.paging.PageQuery;

/**
 * Searches the catalog, one page at a time.
 *
 * @param author part of the author's name, ignoring case, at most 255 characters (the column's size), or {@code null}
 *        for every book: REST's {@code author} query parameter, the gRPC {@code author} field
 * @param page which page to return: a cursor or an offset, the page size, and the sort keys (names of {@link SortKey}
 *        constants). Over REST it comes from the {@code cursor}, {@code offset}, {@code size} and {@code sort} query
 *        parameters; over gRPC from the AIP-158 fields {@code page_token}, {@code skip}, {@code page_size} and
 *        {@code order_by}. Both protocols issue the same tokens: a REST {@code nextCursor} pages over gRPC, and a
 *        gRPC {@code next_page_token} over REST
 */
public record SearchBooksRequest(@Nullable @Size(max = 255) String author, PageQuery page) {

    /**
     * What a search can be sorted by. Over REST the keys are in lowerCamel ({@code sort=title},
     * {@code sort=-publishedYear} for descending); over gRPC in lower case ({@code order_by: "published_year desc"}).
     * The operation receives the constant names ({@code TITLE}, {@code PUBLISHED_YEAR}). A search without sort keys
     * is by title, then id.
     */
    public enum SortKey {

        /** The title. */
        TITLE,

        /** The year of publication. */
        PUBLISHED_YEAR
    }
}
