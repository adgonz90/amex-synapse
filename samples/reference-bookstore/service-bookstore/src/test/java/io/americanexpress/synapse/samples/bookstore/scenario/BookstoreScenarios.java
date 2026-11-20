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
package io.americanexpress.synapse.samples.bookstore.scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.samples.bookstore.contract.BookErrors;
import io.americanexpress.synapse.samples.bookstore.scenario.Outcome.BookView;
import io.americanexpress.synapse.samples.bookstore.scenario.Outcome.Kind;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bookstore's contract, the same over both protocols: every scenario runs over REST and over gRPC and expects the
 * same {@link Outcome}, and the cross-protocol scenarios take an entity tag or a page token from one protocol to the
 * other. A subclass says where the bookstore runs ({@link #target()}): a test context, a packaged jar, an image.
 *
 * <p>
 * The scenarios share the bookstore's database, so each one works on the books it adds: an ISBN of its own, and an
 * author of its own for every search, which the search filters by.
 */
public abstract class BookstoreScenarios {

    private static final String AUTHOR = "Ursula K. Le Guin";

    private RestBookstoreClient rest;

    private GrpcBookstoreClient grpc;

    /**
     * Returns where the bookstore under test answers.
     *
     * @return the target
     */
    protected abstract Target target();

    @BeforeEach
    void connect() {
        Target target = target();
        rest = new RestBookstoreClient(target.rest());
        grpc = new GrpcBookstoreClient(target.grpcHost(), target.grpcPort());
    }

    @AfterEach
    void disconnect() {
        rest.close();
        grpc.close();
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void createReturnsTheBookAndItsEtag(Protocol protocol) {
        String isbn = isbn();

        Outcome created = client(protocol).create(isbn, "The Dispossessed", AUTHOR, 1974);

        // REST answers 201 with a Location; gRPC a Book. Both carry the new book's entity tag.
        assertThat(created)
                .isEqualTo(Outcome
                        .created(new BookView(idOf(created), isbn, "The Dispossessed", AUTHOR, 1974, 0), "\"0\""));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void duplicateIsbnIsConflictAlreadyExists(Protocol protocol) {
        String isbn = isbn();
        client(protocol).create(isbn, "The Lathe of Heaven", AUTHOR, 1971);

        Outcome duplicate = client(protocol).create(isbn, "Another title", AUTHOR, null);

        // The table's unique constraint, translated by Synapse: REST 409, gRPC ALREADY_EXISTS.
        assertThat(duplicate).isEqualTo(Outcome.failed(SynapseErrors.ALREADY_EXISTS));
        assertThat(duplicate.category()).isEqualTo(ErrorCategory.CONFLICT);
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void updateToAnotherBooksIsbnIsAlreadyExists(Protocol protocol) {
        String taken = isbn();
        client(protocol).create(taken, "The Eye of the Heron", AUTHOR, 1978);
        String isbn = isbn();
        Outcome other = client(protocol).create(isbn, "The Beginning Place", AUTHOR, 1980);

        // The update's flush breaks the unique constraint, as a create's INSERT does: the same code, and the book is
        // unchanged.
        assertThat(client(protocol).update(idOf(other), other.etag(), taken, "The Beginning Place", AUTHOR, 1980))
                .isEqualTo(Outcome.failed(SynapseErrors.ALREADY_EXISTS));
        assertThat(client(protocol).get(idOf(other))).isEqualTo(Outcome.ok(other.book(), "\"0\""));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void blankTitleIsInvalidInputOnTitle(Protocol protocol) {
        Outcome invalid = client(protocol).create(isbn(), " ", AUTHOR, null);

        assertThat(invalid).isEqualTo(Outcome.failed(SynapseErrors.VALIDATION_FAILED, "title"));
        assertThat(invalid.category()).isEqualTo(ErrorCategory.INVALID_INPUT);
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void unknownBookIsNotFound(Protocol protocol) {
        UUID unknown = UUID.randomUUID();

        assertThat(client(protocol).get(unknown)).isEqualTo(Outcome.failed(BookErrors.BOOK_NOT_FOUND));
        assertThat(client(protocol).update(unknown, "*", isbn(), "Title", AUTHOR, null))
                .isEqualTo(Outcome.failed(BookErrors.BOOK_NOT_FOUND));
        assertThat(client(protocol).delete(unknown, "*")).isEqualTo(Outcome.failed(BookErrors.BOOK_NOT_FOUND));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void getReturnsTheSameBookAndEtag(Protocol protocol) {
        Outcome created = client(protocol).create(isbn(), "The Word for World Is Forest", AUTHOR, 1972);

        assertThat(client(protocol).get(idOf(created))).isEqualTo(Outcome.ok(created.book(), "\"0\""));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void updateWithTheCurrentEtagBumpsTheVersion(Protocol protocol) {
        String isbn = isbn();
        Outcome created = client(protocol).create(isbn, "Rocannon's World", AUTHOR, null);
        UUID id = idOf(created);

        Outcome updated = client(protocol).update(id, created.etag(), isbn, "Rocannon's World", AUTHOR, 1966);

        // An update answers the book without an entity tag; a read gives the new one.
        BookView revised = new BookView(id, isbn, "Rocannon's World", AUTHOR, 1966, 1);
        assertThat(updated).isEqualTo(Outcome.ok(revised, null));
        assertThat(client(protocol).get(id)).isEqualTo(Outcome.ok(revised, "\"1\""));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void staleEtagIsPreconditionFailed(Protocol protocol) {
        String isbn = isbn();
        Outcome created = client(protocol).create(isbn, "Planet of Exile", AUTHOR, 1966);
        UUID id = idOf(created);

        // The book is at version 0; the request names version 7. REST 412, gRPC ABORTED (AIP-154), with the same code.
        assertThat(client(protocol).update(id, "\"7\"", isbn, "Changed", AUTHOR, null))
                .isEqualTo(Outcome.failed(SynapseErrors.PRECONDITION_FAILED));
        assertGrpcStatus(protocol, Status.Code.ABORTED);
        assertThat(client(protocol).delete(id, "\"7\"")).isEqualTo(Outcome.failed(SynapseErrors.PRECONDITION_FAILED));
        assertGrpcStatus(protocol, Status.Code.ABORTED);
        assertThat(client(protocol).get(id)).isEqualTo(Outcome.ok(created.book(), "\"0\""));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void missingEtagIsPreconditionRequired(Protocol protocol) {
        String isbn = isbn();
        Outcome created = client(protocol).create(isbn, "City of Illusions", AUTHOR, 1967);
        UUID id = idOf(created);

        // A write must name a version: REST 428 naming If-Match, gRPC FAILED_PRECONDITION naming etag. The operation
        // never runs.
        assertThat(client(protocol).update(id, null, isbn, "Changed", AUTHOR, null))
                .isEqualTo(Outcome.failed(SynapseErrors.PRECONDITION_REQUIRED, "If-Match"));
        assertThat(client(protocol).delete(id, null))
                .isEqualTo(Outcome.failed(SynapseErrors.PRECONDITION_REQUIRED, "If-Match"));
        assertThat(client(protocol).get(id)).isEqualTo(Outcome.ok(created.book(), "\"0\""));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void starEtagUpdatesAnyVersion(Protocol protocol) {
        String isbn = isbn();
        Outcome created = client(protocol).create(isbn, "The Tombs of Atuan", AUTHOR, 1970);
        UUID id = idOf(created);
        client(protocol).update(id, created.etag(), isbn, "The Tombs of Atuan", AUTHOR, 1971);

        // "*" is AnyVersion: whatever version the book has now (1).
        assertThat(client(protocol).update(id, "*", isbn, "The Tombs of Atuan", AUTHOR, 1970))
                .isEqualTo(Outcome.ok(new BookView(id, isbn, "The Tombs of Atuan", AUTHOR, 1970, 2), null));
        assertThat(client(protocol).delete(id, "*")).isEqualTo(Outcome.noContent());
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void weakEtagIsPreconditionFailed(Protocol protocol) {
        String isbn = isbn();
        Outcome created = client(protocol).create(isbn, "The Farthest Shore", AUTHOR, 1972);
        UUID id = idOf(created);

        // A weak tag never matches for a write (RFC 9110 13.1.1), whatever its value. Re-reading won't fix it, so gRPC
        // answers FAILED_PRECONDITION, not a stale etag's ABORTED.
        assertThat(client(protocol).update(id, "W/\"0\"", isbn, "Changed", AUTHOR, null))
                .isEqualTo(Outcome.failed(SynapseErrors.PRECONDITION_FAILED));
        assertGrpcStatus(protocol, Status.Code.FAILED_PRECONDITION);
        assertThat(client(protocol).get(id)).isEqualTo(Outcome.ok(created.book(), "\"0\""));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void deleteWithTheEtagRemovesTheBook(Protocol protocol) {
        Outcome created = client(protocol).create(isbn(), "Tehanu", AUTHOR, 1990);
        UUID id = idOf(created);

        assertThat(client(protocol).delete(id, created.etag())).isEqualTo(Outcome.noContent());
        assertThat(client(protocol).get(id)).isEqualTo(Outcome.failed(BookErrors.BOOK_NOT_FOUND));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void searchPagesInTheSameOrder(Protocol protocol) {
        String author = author();
        List<UUID> byTitle = addBooks(protocol, author, "Eta", "Gamma", "Alpha", "Delta", "Beta");

        SearchOutcome first = client(protocol).search(author, 2, null, "title");
        SearchOutcome second = client(protocol).search(author, 2, first.nextToken(), "title");
        SearchOutcome third = client(protocol).search(author, 2, second.nextToken(), "title");

        assertThat(List.of(first.ids(), second.ids(), third.ids()))
                .containsExactly(byTitle.subList(0, 2), byTitle.subList(2, 4), byTitle.subList(4, 5));
        assertThat(List.of(first.hasNext(), second.hasNext(), third.hasNext())).containsExactly(true, true, false);
        assertThat(third.nextToken()).isNull();
        assertThat(List.of(first.total(), second.total(), third.total())).containsOnly(5L);
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void sortKeysAreTheSameOnBothProtocols(Protocol protocol) {
        String author = author();
        UUID of1965 = idOf(client(protocol).create(isbn(), "B", author, 1965));
        UUID of1968 = idOf(client(protocol).create(isbn(), "A", author, 1968));
        UUID withoutYear = idOf(client(protocol).create(isbn(), "C", author, null));
        UUID of1974 = idOf(client(protocol).create(isbn(), "A", author, 1974));

        // REST sort=-publishedYear,title is gRPC order_by "published_year desc, title". PostgreSQL puts the book
        // without a year first when descending; the ties (none here) go by title, then id.
        SearchOutcome page = client(protocol).search(author, 10, null, "-publishedYear,title");

        assertThat(page.ids()).containsExactly(withoutYear, of1974, of1968, of1965);
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void unsortedSearchIsByTitleThenId(Protocol protocol) {
        String author = author();
        UUID beta = idOf(client(protocol).create(isbn(), "Beta", author, null));
        UUID alphaOne = idOf(client(protocol).create(isbn(), "Alpha", author, null));
        UUID alphaTwo = idOf(client(protocol).create(isbn(), "Alpha", author, null));
        List<UUID> alphas = new ArrayList<>(List.of(alphaOne, alphaTwo));
        alphas.sort(Comparator.comparing(UUID::toString));

        // No sort sent: by title, and two books with the same title by id (as bookstore.proto documents).
        SearchOutcome page = client(protocol).search(author, 10, null, null);

        assertThat(page.ids()).containsExactly(alphas.get(0), alphas.get(1), beta);
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void offsetPagedSearchSignalsTheNextPage(Protocol protocol) {
        String author = author();
        List<UUID> byTitle = addBooks(protocol, author, "Alpha", "Beta", "Gamma", "Delta", "Eta");

        // The operation pages a Spring Data Page by offset and returns no cursor. Both protocols still signal the next
        // page: REST hasNext and a Link: rel="next" (RestBookstoreClient checks that it is there exactly when hasNext
        // is), gRPC has_next, and both a token that carries the next offset, 3.
        SearchOutcome page = client(protocol).search(author, 2, null, 1L, "title");

        assertThat(page.ids()).isEqualTo(byTitle.subList(1, 3));
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextToken()).isNotNull();
        assertThat(page.total()).isEqualTo(5L);

        SearchOutcome next = client(protocol).search(author, 2, page.nextToken(), "title");

        assertThat(next.ids()).isEqualTo(byTitle.subList(3, 5));
        assertThat(next.hasNext()).isFalse();
        assertThat(next.nextToken()).isNull();
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void emptyAndZeroPageFieldsAnswerAsRest(Protocol protocol) {
        String author = author();
        List<UUID> byTitle = addBooks(protocol, author, "Alpha", "Beta", "Gamma");

        // gRPC's page fields are proto3 optional: a field that is set is sent, and follows REST's rules for the
        // parameter, so size 0, an empty token and an empty sort are violations, and offset 0 is the first page.
        assertThat(client(protocol).search(author, 0, null, null).asOutcome())
                .isEqualTo(Outcome.failed(SynapseErrors.VALIDATION_FAILED, "size"));
        assertThat(client(protocol).search(author, 2, "", null).asOutcome())
                .isEqualTo(Outcome.failed(SynapseErrors.VALIDATION_FAILED, "cursor"));
        assertThat(client(protocol).search(author, 2, null, "").asOutcome())
                .isEqualTo(Outcome.failed(SynapseErrors.VALIDATION_FAILED, "sort"));
        SearchOutcome fromZero = client(protocol).search(author, 2, null, 0L, null);
        assertThat(fromZero.ids()).isEqualTo(byTitle.subList(0, 2));
        assertThat(fromZero.hasNext()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void correlationIdIsEchoed(Protocol protocol) {
        Outcome created = client(protocol).create(isbn(), "Lavinia", AUTHOR, 2008);

        BookstoreClient.Echo read = client(protocol).echoedCorrelationId(idOf(created), "scenario-" + protocol);
        BookstoreClient.Echo missing = client(protocol)
                .echoedCorrelationId(UUID.randomUUID(), "scenario-missing-" + protocol);

        assertThat(read.outcome()).isEqualTo(Outcome.ok(created.book(), "\"0\""));
        assertThat(read.correlationId()).isEqualTo("scenario-" + protocol);
        // A failure carries it too.
        assertThat(missing.outcome()).isEqualTo(Outcome.failed(BookErrors.BOOK_NOT_FOUND));
        assertThat(missing.correlationId()).isEqualTo("scenario-missing-" + protocol);
    }

    /**
     * Acceptance 2: one operation behind both protocols. A book added over one is the same book over the other. It is
     * the smoke test: one fast check that both APIs serve the store, which every target runs.
     */
    @Test
    @Tag("smoke")
    void sameOperationOverRestAndGrpc() {
        Outcome overRest = rest.create(isbn(), "The Telling", AUTHOR, 2000);
        Outcome overGrpc = grpc.create(isbn(), "Gifts", AUTHOR, 2004);

        assertThat(grpc.get(idOf(overRest))).isEqualTo(Outcome.ok(overRest.book(), overRest.etag()));
        assertThat(rest.get(idOf(overGrpc))).isEqualTo(Outcome.ok(overGrpc.book(), overGrpc.etag()));
    }

    /**
     * Review Focus 1: the {@code ETag} of a REST read, sent verbatim as the gRPC {@code etag}, updates the book; the
     * same value again is stale, which gRPC answers {@code ABORTED} with REST's code.
     */
    @Test
    void etagFromRestUpdatesOverGrpc() {
        String isbn = isbn();
        UUID id = idOf(rest.create(isbn, "Voices", AUTHOR, 2006));
        String restEtag = Objects.requireNonNull(rest.get(id).etag());

        assertThat(grpc.update(id, restEtag, isbn, "Voices", AUTHOR, 2007))
                .isEqualTo(Outcome.ok(new BookView(id, isbn, "Voices", AUTHOR, 2007, 1), null));
        assertThat(grpc.update(id, restEtag, isbn, "Voices, again", AUTHOR, 2007))
                .isEqualTo(Outcome.failed(SynapseErrors.PRECONDITION_FAILED));
        assertThat(grpc.lastStatus()).isEqualTo(Status.Code.ABORTED);
        assertThat(grpc.get(id).etag()).isEqualTo("\"1\"");
    }

    /** The reverse: a gRPC etag that a later update made stale is 412 over REST; the current one deletes the book. */
    @Test
    void staleEtagFromGrpcIsRejectedOverRest() {
        String isbn = isbn();
        UUID id = idOf(grpc.create(isbn, "Powers", AUTHOR, 2007));
        String grpcEtag = Objects.requireNonNull(grpc.get(id).etag());
        rest.update(id, grpcEtag, isbn, "Powers", AUTHOR, 2008);

        assertThat(rest.update(id, grpcEtag, isbn, "Changed", AUTHOR, null))
                .isEqualTo(Outcome.failed(SynapseErrors.PRECONDITION_FAILED));
        assertThat(rest.delete(id, grpcEtag)).isEqualTo(Outcome.failed(SynapseErrors.PRECONDITION_FAILED));
        assertThat(rest.delete(id, grpc.get(id).etag())).isEqualTo(Outcome.noContent());
    }

    /**
     * Review Focus 2: a REST {@code nextCursor}, sent as the gRPC {@code page_token}, gives page 2. The token is bound
     * to the operation, the sort and the filter: with another sort or another author it is a violation on the token,
     * never a page of other data.
     */
    @Test
    void restCursorPagesOverGrpc() {
        String author = author();
        List<UUID> byTitle = addBooks(Protocol.REST, author, "Alpha", "Beta", "Gamma", "Delta", "Eta");
        String token = Objects.requireNonNull(rest.search(author, 2, null, "title").nextToken());

        SearchOutcome second = grpc.search(author, 2, token, "title");

        assertThat(second.ids()).isEqualTo(byTitle.subList(2, 4));
        assertThat(second.ids()).isEqualTo(rest.search(author, 2, token, "title").ids());
        assertThat(grpc.search(author, 2, token, "-title").asOutcome())
                .isEqualTo(Outcome.failed(SynapseErrors.VALIDATION_FAILED, "cursor"));
        assertThat(grpc.search(author(), 2, token, "title").asOutcome())
                .isEqualTo(Outcome.failed(SynapseErrors.VALIDATION_FAILED, "cursor"));
    }

    /**
     * The reverse: a gRPC {@code next_page_token}, sent as REST's {@code cursor}, gives the same next page, whether the
     * gRPC page was the first one, one reached by a token, or one asked for with {@code skip}. The search pages by
     * offset, so every token here carries the next offset. Both plugins wrap an operation's own cursor with the same
     * core codec and scope, which their paging tests cover ({@code RestPagingIT}, {@code GrpcPagingIT}).
     */
    @Test
    void grpcPageTokenPagesOverRest() {
        String author = author();
        List<UUID> byTitle = addBooks(Protocol.GRPC, author, "Alpha", "Beta", "Gamma", "Delta", "Eta");
        SearchOutcome first = grpc.search(author, 2, null, "title");
        SearchOutcome second = grpc.search(author, 2, first.nextToken(), "title");
        SearchOutcome skipped = grpc.search(author, 2, null, 1L, "title");

        assertThat(rest.search(author, 2, first.nextToken(), "title").ids()).isEqualTo(byTitle.subList(2, 4));
        assertThat(rest.search(author, 2, second.nextToken(), "title").ids()).isEqualTo(byTitle.subList(4, 5));
        // skip 1 of size 2: the token carries offset 3.
        assertThat(rest.search(author, 2, skipped.nextToken(), "title").ids()).isEqualTo(byTitle.subList(3, 5));
    }

    /**
     * Returns the client of a protocol.
     *
     * @param protocol the protocol
     * @return its client
     */
    protected BookstoreClient client(Protocol protocol) {
        return switch (protocol) {
            case REST -> rest;
            case GRPC -> grpc;
        };
    }

    /**
     * Over gRPC, asserts the status the last call failed with; over REST, does nothing. The {@link Outcome} is the
     * same on both protocols, but the gRPC status of {@code PRECONDITION_FAILED} depends on why the precondition
     * failed, so the scenario that sends the {@code etag} says which ({@link GrpcBookstoreClient#lastStatus()}).
     *
     * @param protocol the protocol of the last call
     * @param expected the gRPC status it must have failed with
     */
    protected void assertGrpcStatus(Protocol protocol, Status.Code expected) {
        if (protocol == Protocol.GRPC) {
            assertThat(grpc.lastStatus()).as("gRPC status").isEqualTo(expected);
        }
    }

    /** Adds one book per title for the author, over the protocol, and returns their ids ordered by title, then id. */
    private List<UUID> addBooks(Protocol protocol, String author, String... titles) {
        List<BookView> books = new ArrayList<>();
        for (String title : titles) {
            books.add(Objects.requireNonNull(client(protocol).create(isbn(), title, author, null).book()));
        }
        books.sort(Comparator.comparing(BookView::title).thenComparing(book -> book.id().toString()));
        return books.stream().map(BookView::id).toList();
    }

    private static UUID idOf(Outcome outcome) {
        assertThat(outcome.kind()).as("outcome of %s", outcome).isIn(Kind.OK, Kind.CREATED);
        return Objects.requireNonNull(outcome.book()).id();
    }

    /** A unique ISBN-shaped value ("978-" and 13 digits): 17 characters, the column's size, though not a real ISBN. */
    protected static String isbn() {
        return "978-" + ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 10_000_000_000_000L);
    }

    /** An author no other scenario uses, to search this scenario's books only. */
    private static String author() {
        return "Author " + UUID.randomUUID();
    }
}
