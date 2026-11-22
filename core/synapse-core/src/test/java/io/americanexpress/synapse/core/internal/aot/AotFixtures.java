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
package io.americanexpress.synapse.core.internal.aot;

import java.util.List;
import java.util.Optional;
import jakarta.validation.constraints.NotBlank;

import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

/** Operations and models of the AOT tests. */
final class AotFixtures {

    private AotFixtures() {
    }

    record GetBookRequest(@NotBlank String id) {
    }

    record Book(String id, Author author) {

        record Author(String name) {
        }
    }

    record SearchBooksRequest(String title, PageQuery page) {
    }

    record Shelf(String name) {
    }

    /** A query named {@code get-book} whose output is optional. */
    static class GetBookOperation implements QueryOperation<GetBookRequest, Optional<Book>> {

        @Override
        public Optional<Book> execute(GetBookRequest input) {
            return Optional.of(new Book(input.id(), new Book.Author("Ursula")));
        }
    }

    /** A query named {@code search-books} that returns a page. */
    static class SearchBooksOperation implements QueryOperation<SearchBooksRequest, PageResult<Book>> {

        @Override
        public PageResult<Book> execute(SearchBooksRequest input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** A business operation that returns a list. */
    static class ShelveBooksBusiness implements BusinessOperation<Shelf, List<Book>> {

        @Override
        public List<Book> execute(Shelf input) {
            return List.of();
        }
    }

    /** A business operation over arrays. */
    static class CountBooksBusiness implements BusinessOperation<Book[], int[]> {

        @Override
        public int[] execute(Book[] input) {
            return new int[]{input.length};
        }
    }

    /** A business operation over primitive arrays only. */
    static class ChecksumBusiness implements BusinessOperation<byte[], long[]> {

        @Override
        public long[] execute(byte[] input) {
            return new long[]{input.length};
        }
    }

    /** An operation whose type variable refers to itself. */
    static class MaxOperation<T extends Comparable<T>> implements QueryOperation<T, T> {

        @Override
        public T execute(T input) {
            return input;
        }
    }

    /** An operation whose types are known only to its subclasses. */
    static class GenericOperation<T> implements QueryOperation<T, T> {

        @Override
        public T execute(T input) {
            return input;
        }
    }
}
