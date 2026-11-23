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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class BindingParityCheckTest {

    private static final String GET_BOOK = "get-book";

    private static final String REST_GET = "GET /books/{id}";

    private static final String GRPC_GET = "bookstore.v1.Bookstore/GetBook";

    private static final String GRPC_V2_GET = "bookstore.v2.Bookstore/GetBook";

    private static final String REST_V1_GET = "GET /v1/books/{id}";

    private static final String REST_V2_GET = "GET /v2/books/{id}";

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
    void restV2MayDifferFromGrpcV1() {
        declarations.add(versionedRest(GET_BOOK, REST_V2_GET, "BOOK_NOT_FOUND", 2));
        declarations.add(versionedGrpc(GET_BOOK, GRPC_GET, "NOT_FOUND", 1));

        assertThatCode(this::check).doesNotThrowAnyException();
    }

    @Test
    void restV1IsComparedWithGrpcV1() {
        // REST v2 differs from gRPC v1 too, but isn't compared with it.
        declarations.add(versionedRest(GET_BOOK, REST_V1_GET, "BOOK_NOT_FOUND", 1));
        declarations.add(versionedRest(GET_BOOK, REST_V2_GET, "BOOK_NOT_FOUND", 2));
        declarations.add(versionedGrpc(GET_BOOK, GRPC_GET, "NOT_FOUND", 1));

        assertThat(failure().description())
                .isEqualTo("Operation 'get-book' is bound differently over REST (GET /v1/books/{id}) and gRPC "
                        + "(bookstore.v1.Bookstore/GetBook): notFound is BOOK_NOT_FOUND over REST and NOT_FOUND over "
                        + "gRPC.");
    }

    @Test
    void unversionedRestIsComparedWithEveryGrpcMethod() {
        declarations.add(versionedRest(GET_BOOK, REST_GET, "BOOK_NOT_FOUND", null));
        declarations.add(versionedGrpc(GET_BOOK, GRPC_V2_GET, "NOT_FOUND", 2));
        declarations.add(versionedGrpc(GET_BOOK, GRPC_GET, "NOT_FOUND", 1));

        assertThat(failure().description())
                .isEqualTo("Operation 'get-book' is bound differently over REST (GET /books/{id}) and gRPC "
                        + "(bookstore.v1.Bookstore/GetBook): notFound is BOOK_NOT_FOUND over REST and NOT_FOUND over "
                        + "gRPC. "
                        + "Operation 'get-book' is bound differently over REST (GET /books/{id}) and gRPC "
                        + "(bookstore.v2.Bookstore/GetBook): notFound is BOOK_NOT_FOUND over REST and NOT_FOUND over "
                        + "gRPC.");
    }

    @Test
    void unversionedGrpcIsComparedWithEveryRestVersion() {
        declarations.add(versionedRest(GET_BOOK, REST_V2_GET, "BOOK_NOT_FOUND", 2));
        declarations.add(versionedRest(GET_BOOK, REST_V1_GET, "BOOK_NOT_FOUND", 1));
        declarations.add(versionedGrpc(GET_BOOK, "bookstore.Bookstore/GetBook", "NOT_FOUND", null));

        assertThat(failure().description())
                .isEqualTo("Operation 'get-book' is bound differently over REST (GET /v1/books/{id}) and gRPC "
                        + "(bookstore.Bookstore/GetBook): notFound is BOOK_NOT_FOUND over REST and NOT_FOUND over "
                        + "gRPC. "
                        + "Operation 'get-book' is bound differently over REST (GET /v2/books/{id}) and gRPC "
                        + "(bookstore.Bookstore/GetBook): notFound is BOOK_NOT_FOUND over REST and NOT_FOUND over "
                        + "gRPC.");
    }

    @Test
    void minorRestVersionsPairWithTheMajor() {
        // .version("1.2") has major version 1.
        declarations.add(versionedRest(GET_BOOK, "GET /v1.2/books/{id}", "BOOK_NOT_FOUND", 1));
        declarations.add(versionedGrpc(GET_BOOK, GRPC_GET, "NOT_FOUND", 1));

        assertThat(failure().description())
                .isEqualTo("Operation 'get-book' is bound differently over REST (GET /v1.2/books/{id}) and gRPC "
                        + "(bookstore.v1.Bookstore/GetBook): notFound is BOOK_NOT_FOUND over REST and NOT_FOUND over "
                        + "gRPC.");
    }

    @Test
    void unpairedBindingsAreOneWarn() {
        // gRPC v1 with only REST v2 routes, for two operations; their declarations differ, but aren't compared.
        declarations.add(versionedGrpc("search-books", "bookstore.v1.Bookstore/SearchBooks", "NOT_FOUND", 1));
        declarations.add(versionedRest("search-books", "GET /v2/books", "NOT_FOUND", 2));
        declarations.add(versionedRest(GET_BOOK, REST_V2_GET, "BOOK_NOT_FOUND", 2));
        declarations.add(versionedGrpc(GET_BOOK, GRPC_GET, "NOT_FOUND", 1));
        // REST v1 pairs with gRPC v1, so only REST v2 is listed.
        declarations.add(versionedRest("delete-book", "DELETE /v1/books/{id}", "NOT_FOUND", 1));
        declarations.add(versionedRest("delete-book", "DELETE /v2/books/{id}", "NOT_FOUND", 2));
        declarations.add(versionedGrpc("delete-book", "bookstore.v1.Bookstore/DeleteBook", "NOT_FOUND", 1));
        // Every binding pairs: not listed.
        declarations.add(versionedRest("list-books", "GET /books", "NOT_FOUND", null));
        declarations.add(versionedGrpc("list-books", "bookstore.v2.Bookstore/ListBooks", "NOT_FOUND", 2));

        List<ILoggingEvent> logged = captured(() -> assertThatCode(this::check).doesNotThrowAnyException());

        assertThat(logged)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .isEqualTo("These bindings have no binding of the same major version over the other "
                                    + "protocol, so their contract is not compared: [delete-book: DELETE "
                                    + "/v2/books/{id}, get-book: GET /v2/books/{id}, get-book: "
                                    + "bookstore.v1.Bookstore/GetBook, search-books: GET /v2/books, search-books: "
                                    + "bookstore.v1.Bookstore/SearchBooks]. Bind the same version over both "
                                    + "protocols, or ignore this if the version is meant for one protocol only.");
                });
    }

    @Test
    void singleProtocolOperationIsNotListed() {
        declarations.add(versionedRest(GET_BOOK, REST_V1_GET, "BOOK_NOT_FOUND", 1));
        declarations.add(versionedRest(GET_BOOK, REST_V2_GET, "NOT_FOUND", 2));

        assertThat(captured(() -> assertThatCode(this::check).doesNotThrowAnyException())).isEmpty();
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

    /** Runs the action and returns what it logged on the core logger, at DEBUG and above. */
    private static List<ILoggingEvent> captured(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.core");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level level = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            action.run();
        } finally {
            logger.setLevel(level);
            logger.detachAppender(appender);
        }
        return List.copyOf(appender.list);
    }

    /** An unversioned REST route. */
    private static BindingDeclaration rest(String operation, String binding, String notFound,
            @Nullable String etag, @Nullable Set<String> sortKeys) {
        return new BindingDeclaration("REST", operation, binding, notFound, etag, sortKeys, null);
    }

    /** An unversioned gRPC method. */
    private static BindingDeclaration grpc(String operation, String binding, String notFound,
            @Nullable String etag, @Nullable Set<String> sortKeys) {
        return new BindingDeclaration("gRPC", operation, binding, notFound, etag, sortKeys, null);
    }

    private static BindingDeclaration versionedRest(String operation, String binding, String notFound,
            @Nullable Integer majorVersion) {
        return new BindingDeclaration("REST", operation, binding, notFound, null, null, majorVersion);
    }

    private static BindingDeclaration versionedGrpc(String operation, String binding, String notFound,
            @Nullable Integer majorVersion) {
        return new BindingDeclaration("gRPC", operation, binding, notFound, null, null, majorVersion);
    }
}
