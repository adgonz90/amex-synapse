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

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;
import io.americanexpress.synapse.samples.books.data.StoredBook;
import io.americanexpress.synapse.samples.books.model.Book;
import io.americanexpress.synapse.samples.books.model.SearchBooksRequest;

/** Lists the books, optionally only those whose author's name contains a text, sorted by title. */
@Component
public class SearchBooksOperation implements QueryOperation<SearchBooksRequest, List<Book>> {

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
    public List<Book> execute(SearchBooksRequest request) {
        String author = request.author();
        String text = author == null ? "" : author.toLowerCase(Locale.ROOT);
        return store
                .findAll()
                .stream()
                .filter(book -> book.author().toLowerCase(Locale.ROOT).contains(text))
                .sorted(Comparator.comparing(StoredBook::title))
                .map(BookMapping::toBook)
                .toList();
    }
}
