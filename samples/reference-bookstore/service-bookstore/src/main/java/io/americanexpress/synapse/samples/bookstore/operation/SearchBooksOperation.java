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
package io.americanexpress.synapse.samples.bookstore.operation;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.core.paging.SpringDataPages;
import io.americanexpress.synapse.samples.bookstore.contract.Book;
import io.americanexpress.synapse.samples.bookstore.contract.SearchBooksRequest;
import io.americanexpress.synapse.samples.bookstore.contract.SearchBooksRequest.SortKey;
import io.americanexpress.synapse.samples.bookstore.data.books.BookRepository;
import io.americanexpress.synapse.samples.bookstore.data.books.StoredBook;

/**
 * Searches the books one page at a time, optionally only those whose author's name contains a text. The books are in
 * the order of the caller's sort keys, then by title (the order without keys), then by id, so every book has one
 * place, and the pages neither skip nor repeat a book while the catalog doesn't change. Sorted by year, the books
 * without one come last ascending and first descending, PostgreSQL's order for nulls.
 *
 * <p>
 * The search pages by offset, with a Spring Data {@link Page}, Spring Data JPA's own paging, and returns no cursor:
 * {@link SpringDataPages#from(Page)} keeps the page's total and whether a next page follows. The edges then give the
 * caller a token that carries the next offset, bound to this operation, the author and the sort (REST's
 * {@code nextCursor}, gRPC's {@code next_page_token}, the same token on both), and a token arrives here as that
 * offset. So this operation never receives a cursor. A key-set cursor (the last title and id) would stay exact while
 * books are added or removed between pages, but Spring Data's key-set scrolling needs sort keys without nulls, which
 * the year isn't.
 */
@Component
public class SearchBooksOperation implements QueryOperation<SearchBooksRequest, PageResult<Book>> {

    private final BookRepository books;

    /**
     * Creates the operation.
     *
     * @param books the books store's repository
     */
    public SearchBooksOperation(BookRepository books) {
        this.books = books;
    }

    @Override
    public PageResult<Book> execute(SearchBooksRequest request) {
        PageQuery page = request.page();
        // The edge has checked the size and the offset, and the sort keys against SortKey. Spring Data sorts by the
        // entity's properties, so the keys are mapped to them.
        Pageable pageable = SpringDataPages
                .pageable(new PageQuery(page.cursor(), page.offset(), page.size(), properties(page.sort())));
        String author = request.author();
        Page<StoredBook> found = author == null
                ? books.findAll(pageable)
                : books.findByAuthorContainingIgnoreCase(author, pageable);
        return SpringDataPages.from(found.map(BookMapping::toBook));
    }

    /** The caller's keys as the entity's properties, then the title unless a key is the title, then the id. */
    private static List<SortOrder> properties(List<SortOrder> keys) {
        List<SortOrder> orders = new ArrayList<>();
        boolean byTitle = false;
        for (SortOrder key : keys) {
            // The edge lets through only the constants of SortKey, by their names.
            SortKey sortKey = SortKey.valueOf(key.property());
            byTitle |= sortKey == SortKey.TITLE;
            orders.add(new SortOrder(property(sortKey), key.direction()));
        }
        if (!byTitle) {
            orders.add(new SortOrder("title", SortOrder.Direction.ASC));
        }
        orders.add(new SortOrder("id", SortOrder.Direction.ASC));
        return orders;
    }

    private static String property(SortKey key) {
        return switch (key) {
            case TITLE -> "title";
            case PUBLISHED_YEAR -> "publishedYear";
        };
    }
}
