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
package com.example.arch.good.business;

import com.example.arch.good.business.model.BookDetails;
import com.example.arch.good.client.AuthorClient;
import com.example.arch.good.contract.model.Book;
import com.example.arch.good.data.BookEntity;
import com.example.arch.good.data.BookRepository;
import com.example.arch.good.model.event.BookViewed;
import com.example.arch.good.publisher.BookEventPublisher;
import org.springframework.stereotype.Service;
import org.thirdparty.api.Tracing;
import org.thirdparty.services.model.StoredObject;

import io.americanexpress.synapse.core.operation.BusinessOperation;

/**
 * A business bean: implements Synapse's {@link BusinessOperation} (which lives in Synapse's own {@code ..operation..}
 * package) and uses a contract model, the service's events, its own business model, a client, a data module, a
 * publisher, and third-party packages named like layers (org.thirdparty.api, org.thirdparty.services.model).
 */
@Service
public class BookLookup implements BusinessOperation<String, Book> {

    private final BookRepository repository;

    private final AuthorClient authors;

    private final BookEventPublisher publisher;

    public BookLookup(BookRepository repository, AuthorClient authors, BookEventPublisher publisher) {
        this.repository = repository;
        this.authors = authors;
        this.publisher = publisher;
    }

    @Override
    public Book execute(String isbn) {
        BookEntity entity = repository.findByIsbn(isbn).orElseThrow();
        BookDetails details = new BookDetails(entity.getIsbn(), BookTitles.normalize(entity.getTitle()),
                new StoredObject("covers/" + isbn).key() + "@" + Tracing.currentSpan());
        Book book = new Book(details.isbn(), details.title());
        publisher.publish(new BookViewed(isbn));
        return new Book(book.isbn(), book.title() + " by " + authors.authorOf(book));
    }
}
