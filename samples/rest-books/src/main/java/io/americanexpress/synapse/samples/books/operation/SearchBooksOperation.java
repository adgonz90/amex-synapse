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
package io.americanexpress.synapse.samples.books.operation;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;
import io.americanexpress.synapse.samples.books.data.StoredBook;
import io.americanexpress.synapse.samples.books.model.Book;
import io.americanexpress.synapse.samples.books.model.SearchBooksRequest;
import io.americanexpress.synapse.samples.books.model.SearchBooksRequest.SortKey;

/**
 * Lists the books one page at a time, optionally only those whose author's name contains a text. The books are in the
 * order of the caller's sort keys, then by title (the default order), then by id, so every book has one place and the
 * pages neither skip nor repeat a book while the catalog doesn't change.
 *
 * <p>
 * A page starts at a cursor, at an offset, or at the first book. The cursor this operation returns is the index of
 * the next page's first book, as a string ({@code "2"}). That is enough for a stand-in store: the REST edge encrypts
 * it and binds it to this operation, the sort and the author filter, so a client can neither read nor change it. A
 * database would use a key-set cursor (the last title and id), which stays correct while books are added or removed
 * between pages.
 */
@Component
public class SearchBooksOperation implements QueryOperation<SearchBooksRequest, PageResult<Book>> {

    private final InMemoryBookStore store;

    /**
     * Creates the operation.
     *
     * @param store the catalog
     */
    public SearchBooksOperation(InMemoryBookStore store) {
        this.store = store;
    }

    @Override
    public PageResult<Book> execute(SearchBooksRequest request) {
        String author = request.author();
        String text = author == null ? "" : author.toLowerCase(Locale.ROOT);
        PageQuery page = request.page();
        List<StoredBook> matching = store
                .findAll()
                .stream()
                .filter(book -> book.author().toLowerCase(Locale.ROOT).contains(text))
                .sorted(order(page.sort()))
                .toList();
        int from = (int) Math.min(start(page), matching.size());
        int to = Math.min(from + page.size(), matching.size());
        List<Book> items = matching.subList(from, to).stream().map(BookMapping::toBook).toList();
        @Nullable
        String nextCursor = to < matching.size() ? Integer.toString(to) : null;
        // The total is cheap here (the whole list is in memory); a database would count only if callers need it.
        return PageResult.ofCursor(items, nextCursor).withTotal(matching.size());
    }

    /** The index of the page's first book: the cursor's, the offset, or 0. */
    private static long start(PageQuery page) {
        String cursor = page.cursor();
        if (cursor != null) {
            // Only this operation's own cursors get here: the edge rejects any token it didn't issue.
            return Long.parseLong(cursor);
        }
        Long offset = page.offset();
        return offset == null ? 0 : offset;
    }

    private static Comparator<StoredBook> order(List<SortOrder> sort) {
        // Built from the last criterion to the first: the caller's keys in their order, then title and id break ties.
        Comparator<StoredBook> order = Comparator.comparing(StoredBook::title).thenComparing(StoredBook::id);
        for (SortOrder key : sort.reversed()) {
            order = comparator(key).thenComparing(order);
        }
        return order;
    }

    private static Comparator<StoredBook> comparator(SortOrder key) {
        boolean descending = key.direction() == SortOrder.Direction.DESC;
        // The edge only lets through the keys that .sortable(SortKey.class) allows, as their constant names.
        return switch (SortKey.valueOf(key.property())) {
            case TITLE -> {
                Comparator<StoredBook> byTitle = Comparator.comparing(StoredBook::title);
                yield descending ? byTitle.reversed() : byTitle;
            }
            case YEAR -> {
                Comparator<Integer> years = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
                // Books without a year come last in both directions.
                yield Comparator.comparing(StoredBook::year, Comparator.nullsLast(years));
            }
        };
    }
}
