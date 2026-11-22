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
package io.americanexpress.synapse.core.internal.binding;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class BindingParityCheckTest {

    private static final String GET_BOOK = "get-book";

    private static final String REST_GET = "GET /books/{id}";

    private static final String GRPC_GET = "bookstore.v1.Bookstore/GetBook";

    private static final Set<String> SORT_KEYS = Set.of("TITLE", "PUBLISHED_YEAR");

    private final BindingDeclarations declarations = new BindingDeclarations();

    @Test
    void aNotFoundMismatchFailsNamingBothBindings() {
        declarations.add(rest(GET_BOOK, REST_GET, "BOOK_NOT_FOUND", "version", null));
        declarations.add(grpc(GET_BOOK, GRPC_GET, "NOT_FOUND", "version", null));

        SynapseConfigurationException failure = failure();

        assertThat(failure.description())
                .isEqualTo("Operation 'get-book' is bound differently over REST (GET /books/{id}) and gRPC "
                        + "(bookstore.v1.Bookstore/GetBook): notFound is BOOK_NOT_FOUND over REST and NOT_FOUND over "
                        + "gRPC.");
        assertThat(failure.action())
                .isEqualTo("Declare .notFound(...) the same way on both bindings: protocols must not differ in "
                        + "contract.");
    }

    @Test
    void anEtagMismatchFails() {
        // .etag(Book::version) over REST only.
        declarations.add(rest(GET_BOOK, REST_GET, "BOOK_NOT_FOUND", "version", null));
        declarations.add(grpc(GET_BOOK, GRPC_GET, "BOOK_NOT_FOUND", null, null));

        SynapseConfigurationException failure = failure();

        assertThat(failure.description())
                .isEqualTo("Operation 'get-book' is bound differently over REST (GET /books/{id}) and gRPC "
                        + "(bookstore.v1.Bookstore/GetBook): etag is version over REST and none over gRPC.");
        assertThat(failure.action())
                .isEqualTo("Declare .etag(...) the same way on both bindings: protocols must not differ in contract.");
    }

    @Test
    void aSortableMismatchFails() {
        // REST .sortable(SortKey.class), gRPC none.
        declarations.add(rest("search-books", "GET /books", "NOT_FOUND", null, SORT_KEYS));
        declarations.add(grpc("search-books", "bookstore.v1.Bookstore/SearchBooks", "NOT_FOUND", null, null));

        SynapseConfigurationException failure = failure();

        assertThat(failure.description())
                .isEqualTo("Operation 'search-books' is bound differently over REST (GET /books) and gRPC "
                        + "(bookstore.v1.Bookstore/SearchBooks): sortable is [PUBLISHED_YEAR, TITLE] over REST and "
                        + "none over gRPC.");
        assertThat(failure.action())
                .isEqualTo("Declare .sortable(...) the same way on both bindings: protocols must not differ in "
                        + "contract.");
    }

    @Test
    void matchingDeclarationsStart() {
        // The same keys, in another order.
        Set<String> reordered = new LinkedHashSet<>(List.of("PUBLISHED_YEAR", "TITLE"));
        declarations.add(rest(GET_BOOK, REST_GET, "BOOK_NOT_FOUND", "version", null));
        declarations.add(grpc(GET_BOOK, GRPC_GET, "BOOK_NOT_FOUND", "version", null));
        declarations.add(rest("search-books", "GET /books", "NOT_FOUND", null, SORT_KEYS));
        declarations.add(grpc("search-books", "bookstore.v1.Bookstore/SearchBooks", "NOT_FOUND", null, reordered));

        assertThatCode(this::check).doesNotThrowAnyException();
    }

    @Test
    void aSingleProtocolOperationStarts() {
        declarations.add(rest(GET_BOOK, REST_GET, "BOOK_NOT_FOUND", "version", null));
        declarations.add(grpc("search-books", "bookstore.v1.Bookstore/SearchBooks", "NOT_FOUND", null, null));

        assertThatCode(this::check).doesNotThrowAnyException();
    }

    @Test
    void eachRestRouteIsComparedWithTheGrpcMethod() {
        declarations.add(rest(GET_BOOK, "GET /v1/books/{id}", "BOOK_NOT_FOUND", "version", null));
        declarations.add(rest(GET_BOOK, "GET /v2/books/{id}", "NOT_FOUND", "version", null));
        declarations.add(grpc(GET_BOOK, GRPC_GET, "BOOK_NOT_FOUND", "version", null));

        SynapseConfigurationException failure = failure();

        assertThat(failure.description())
                .isEqualTo("Operation 'get-book' is bound differently over REST (GET /v2/books/{id}) and gRPC "
                        + "(bookstore.v1.Bookstore/GetBook): notFound is NOT_FOUND over REST and BOOK_NOT_FOUND over "
                        + "gRPC.")
                .doesNotContain("/v1/");
    }

    @Test
    void twoRestVersionsMayDifferFromEachOther() {
        declarations.add(rest(GET_BOOK, "GET /v1/books/{id}", "BOOK_NOT_FOUND", null, null));
        declarations.add(rest(GET_BOOK, "GET /v2/books/{id}", "NOT_FOUND", "version", SORT_KEYS));

        assertThatCode(this::check).doesNotThrowAnyException();
    }

    @Test
    void everyMismatchIsInOneFailure() {
        // Registered out of order: the failure is ordered by operation name, then declaration.
        declarations.add(grpc("search-books", "bookstore.v1.Bookstore/SearchBooks", "NOT_FOUND", null, null));
        declarations.add(grpc(GET_BOOK, GRPC_GET, "NOT_FOUND", null, null));
        declarations.add(rest("search-books", "GET /books", "NOT_FOUND", null, SORT_KEYS));
        declarations.add(rest(GET_BOOK, REST_GET, "BOOK_NOT_FOUND", "version", null));

        SynapseConfigurationException failure = failure();

        assertThat(failure.description())
                .isEqualTo("Operation 'get-book' is bound differently over REST (GET /books/{id}) and gRPC "
                        + "(bookstore.v1.Bookstore/GetBook): etag is version over REST and none over gRPC. "
                        + "Operation 'get-book' is bound differently over REST (GET /books/{id}) and gRPC "
                        + "(bookstore.v1.Bookstore/GetBook): notFound is BOOK_NOT_FOUND over REST and NOT_FOUND over "
                        + "gRPC. "
                        + "Operation 'search-books' is bound differently over REST (GET /books) and gRPC "
                        + "(bookstore.v1.Bookstore/SearchBooks): sortable is [PUBLISHED_YEAR, TITLE] over REST and "
                        + "none over gRPC.");
        assertThat(failure.action())
                .isEqualTo("Declare .etag(...), .notFound(...) and .sortable(...) the same way on both bindings: "
                        + "protocols must not differ in contract.");
    }

    @Test
    void twoMismatchedDeclarationsAreBothNamedInTheAction() {
        declarations.add(rest(GET_BOOK, REST_GET, "BOOK_NOT_FOUND", "version", null));
        declarations.add(grpc(GET_BOOK, GRPC_GET, "NOT_FOUND", null, null));

        assertThat(failure().action())
                .isEqualTo("Declare .etag(...) and .notFound(...) the same way on both bindings: protocols must not "
                        + "differ in contract.");
    }

    @Test
    void forOperationReturnsTheOperationsBindingsInRegistrationOrderWithoutDuplicates() {
        BindingDeclaration first = rest(GET_BOOK, REST_GET, "NOT_FOUND", null, null);
        BindingDeclaration second = grpc(GET_BOOK, GRPC_GET, "NOT_FOUND", null, null);
        declarations.add(first);
        declarations.add(grpc("search-books", "bookstore.v1.Bookstore/SearchBooks", "NOT_FOUND", null, null));
        declarations.add(second);
        declarations.add(rest(GET_BOOK, REST_GET, "NOT_FOUND", null, null));

        assertThat(declarations.forOperation(GET_BOOK)).containsExactly(first, second);
        assertThat(declarations.forOperation("list-books")).isEmpty();
    }

    private void check() {
        new BindingParityCheck(declarations).afterSingletonsInstantiated();
    }

    private SynapseConfigurationException failure() {
        SynapseConfigurationException failure = catchThrowableOfType(SynapseConfigurationException.class,
                this::check);
        assertThat(failure).isNotNull();
        return failure;
    }

    private static BindingDeclaration rest(String operation, String binding, String notFound,
            @Nullable String etag, @Nullable Set<String> sortKeys) {
        return new BindingDeclaration("REST", operation, binding, notFound, etag, sortKeys);
    }

    private static BindingDeclaration grpc(String operation, String binding, String notFound,
            @Nullable String etag, @Nullable Set<String> sortKeys) {
        return new BindingDeclaration("gRPC", operation, binding, notFound, etag, sortKeys);
    }
}
