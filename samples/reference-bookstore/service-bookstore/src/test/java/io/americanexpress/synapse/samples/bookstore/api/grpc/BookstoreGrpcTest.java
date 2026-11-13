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
package io.americanexpress.synapse.samples.bookstore.api.grpc;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Any;
import com.google.protobuf.Empty;
import com.google.protobuf.Int32Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.samples.bookstore.contract.proto.Book;
import io.americanexpress.synapse.samples.bookstore.contract.proto.BookServiceGrpc;
import io.americanexpress.synapse.samples.bookstore.contract.proto.CreateBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.proto.DeleteBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.proto.GetBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.proto.SearchBooksRequest;
import io.americanexpress.synapse.samples.bookstore.contract.proto.SearchBooksResponse;
import io.americanexpress.synapse.samples.bookstore.contract.proto.UpdateBookRequest;
import io.americanexpress.synapse.samples.bookstore.operation.CreateBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.DeleteBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.GetBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.SearchBooksOperation;
import io.americanexpress.synapse.samples.bookstore.operation.UpdateBookOperation;
import io.americanexpress.synapse.test.grpc.SynapseGrpcTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

/**
 * The gRPC slice: the bindings and the gRPC edge on Boot's in-process transport, with every operation mocked. It
 * checks how the messages map to the operations' records (the version and etag included) and how errors map to
 * status codes and error details; the answers both protocols must give alike are the scenarios of
 * {@code BookstoreScenarios}. The proto messages and the contract's records share names, so the records are written
 * with their package ({@code contract.Book}).
 */
@SynapseGrpcTest
@ImportGrpcClients(types = BookServiceGrpc.BookServiceBlockingStub.class)
class BookstoreGrpcTest {

    private static final UUID ID = UUID.fromString("019a5e3c-4f00-7000-8000-000000000001");

    private static final String ISBN = "978-0-441-17271-9";

    private static final io.americanexpress.synapse.samples.bookstore.contract.Book DUNE = new io.americanexpress.synapse.samples.bookstore.contract.Book(
            ID, ISBN, "Dune", "Frank Herbert", 1965, 1);

    @Autowired
    BookServiceGrpc.BookServiceBlockingStub stub;

    @MockitoBean
    CreateBookOperation createBook;

    @MockitoBean
    GetBookOperation getBook;

    @MockitoBean
    SearchBooksOperation searchBooks;

    @MockitoBean
    UpdateBookOperation updateBook;

    @MockitoBean
    DeleteBookOperation deleteBook;

    @Test
    void getBookMapsTheRecord() {
        given(getBook.execute(new io.americanexpress.synapse.samples.bookstore.contract.GetBookRequest(ID)))
                .willReturn(Optional.of(DUNE),
                        Optional
                                .of(new io.americanexpress.synapse.samples.bookstore.contract.Book(ID, ISBN, "Dune",
                                        "Frank Herbert", null, 2)));

        Book book = books().getBook(get(ID));
        Book withoutYear = books().getBook(get(ID));

        assertThat(book.getId()).isEqualTo(ID.toString());
        assertThat(book.getIsbn()).isEqualTo(ISBN);
        assertThat(book.getTitle()).isEqualTo("Dune");
        assertThat(book.getAuthor()).isEqualTo("Frank Herbert");
        // A non-null Integer sets the Int32Value wrapper; null leaves it unset, as REST's null.
        assertThat(book.hasPublishedYear()).isTrue();
        assertThat(book.getPublishedYear().getValue()).isEqualTo(1965);
        assertThat(withoutYear.hasPublishedYear()).isFalse();
        assertThat(book.getVersion()).isEqualTo(1);
        // .etag(Book::version): the token REST sends as the ETag header, with its double quotes.
        assertThat(book.getEtag()).isEqualTo("\"1\"");
        assertThat(withoutYear.getEtag()).isEqualTo("\"2\"");
    }

    @Test
    void unknownBookIsNotFoundWithTheBookCode() {
        given(getBook.execute(any())).willReturn(Optional.empty());

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> books().getBook(get(ID)));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        // application-test.yaml turns detail on. BOOK_NOT_FOUND's template needs the id, which the edge doesn't have,
        // so the message is NOT_FOUND's; the reason stays BOOK_NOT_FOUND.
        assertThat(e.getStatus().getDescription()).isEqualTo("The resource was not found");
        ErrorInfo info = detail(e, ErrorInfo.class);
        assertThat(info.getReason()).isEqualTo("BOOK_NOT_FOUND");
        // The domain is spring.application.name.
        assertThat(info.getDomain()).isEqualTo("bookstore");
        assertThat(info.getMetadataMap()).containsKey("correlationId");
    }

    @Test
    void createMapsTheRequestAndCarriesTheEtag() {
        given(createBook.execute(any())).willReturn(DUNE);

        Book created = books()
                .createBook(CreateBookRequest
                        .newBuilder()
                        .setIsbn(ISBN)
                        .setTitle("Dune")
                        .setAuthor("Frank Herbert")
                        .setPublishedYear(Int32Value.of(1965))
                        .build());
        books().createBook(CreateBookRequest.newBuilder().setIsbn(ISBN).setTitle("Dune").setAuthor("F").build());

        // A create carries the new book's etag, as REST's 201 carries its ETag.
        assertThat(created.getEtag()).isEqualTo("\"1\"");
        then(createBook)
                .should()
                .execute(new io.americanexpress.synapse.samples.bookstore.contract.CreateBookRequest(ISBN, "Dune",
                        "Frank Herbert", 1965));
        // An unset published_year is null.
        then(createBook)
                .should()
                .execute(new io.americanexpress.synapse.samples.bookstore.contract.CreateBookRequest(ISBN, "Dune",
                        "F", null));
    }

    @Test
    void updateEtagBindsTheVersionOrAny() {
        given(updateBook.execute(any())).willReturn(DUNE);

        Book updated = books().updateBook(update("\"3\"").setPublishedYear(Int32Value.of(1965)).build());
        books().updateBook(update("*").build());

        // No etag on an update's response, as REST sends no ETag on PUT.
        assertThat(updated.getEtag()).isEmpty();
        then(updateBook)
                .should()
                .execute(new io.americanexpress.synapse.samples.bookstore.contract.UpdateBookRequest(ID, ISBN, "Dune",
                        "Frank Herbert", 1965, VersionCondition.of(3L)));
        then(updateBook)
                .should()
                .execute(new io.americanexpress.synapse.samples.bookstore.contract.UpdateBookRequest(ID, ISBN, "Dune",
                        "Frank Herbert", null, VersionCondition.any()));
    }

    @Test
    void updateWithoutEtagIsPreconditionRequired() {
        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> books().updateBook(update("").build()));

        // UpdateBookRequest.version isn't @Nullable, so the etag is required: REST's 428 code. Its category,
        // PRECONDITION_FAILED, is FAILED_PRECONDITION over gRPC. The violation names the field, as REST names If-Match.
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("PRECONDITION_REQUIRED");
        assertThat(detail(e, BadRequest.class).getFieldViolationsList())
                .extracting(BadRequest.FieldViolation::getField)
                .containsExactly("etag");
        then(updateBook).shouldHaveNoInteractions();
    }

    @Test
    void deleteBookAnswersEmpty() {
        Empty answer = books().deleteBook(DeleteBookRequest.newBuilder().setId(ID.toString()).setEtag("\"2\"").build());

        assertThat(answer).isEqualTo(Empty.getDefaultInstance());
        then(deleteBook)
                .should()
                .execute(new io.americanexpress.synapse.samples.bookstore.contract.DeleteBookRequest(ID,
                        VersionCondition.of(2L)));
    }

    @Test
    void deleteWithoutEtagIsPreconditionRequired() {
        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> books().deleteBook(DeleteBookRequest.newBuilder().setId(ID.toString()).build()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("PRECONDITION_REQUIRED");
        then(deleteBook).shouldHaveNoInteractions();
    }

    @Test
    void staleEtagIsFailedPrecondition() {
        willThrow(new SynapseException(SynapseErrors.STALE_VERSION)).given(updateBook).execute(any());

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> books().updateBook(update("\"1\"").build()));

        // The request named a version and it was stale: PRECONDITION_FAILED, as REST answers a stale If-Match 412.
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("PRECONDITION_FAILED");
        then(updateBook)
                .should()
                .execute(new io.americanexpress.synapse.samples.bookstore.contract.UpdateBookRequest(ID, ISBN, "Dune",
                        "Frank Herbert", null, VersionCondition.of(1L)));
    }

    @Test
    void weakEtagIsFailedPreconditionBeforeTheOperation() {
        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> books().updateBook(update("W/\"1\"").build()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("PRECONDITION_FAILED");
        then(updateBook).shouldHaveNoInteractions();
    }

    @Test
    void duplicateIsbnIsAlreadyExists() {
        willThrow(new SynapseException(SynapseErrors.ALREADY_EXISTS)).given(createBook).execute(any());

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> books()
                        .createBook(CreateBookRequest
                                .newBuilder()
                                .setIsbn(ISBN)
                                .setTitle("Dune")
                                .setAuthor("Frank Herbert")
                                .build()));

        // Synapse's ALREADY_EXISTS (the table's unique constraint) has its own status, where another CONFLICT code is
        // ABORTED. application-test.yaml turns synapse.errors.include-detail on, so the description is its message.
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
        assertThat(e.getStatus().getDescription()).isEqualTo("The resource already exists");
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("ALREADY_EXISTS");
    }

    @Test
    void searchMapsThePageFields() {
        given(searchBooks.execute(any())).willReturn(PageResult.of(List.of(DUNE), true).withTotal(3));

        SearchBooksResponse page = books()
                .searchBooks(SearchBooksRequest
                        .newBuilder()
                        .setAuthor(StringValue.of("herbert"))
                        .setPageSize(1)
                        .setOrderBy("title, published_year desc")
                        .build());

        assertThat(page.getBooksList()).extracting(Book::getTitle).containsExactly("Dune");
        // A book in a page carries no etag, as REST's items don't.
        assertThat(page.getBooks(0).getEtag()).isEmpty();
        assertThat(page.getHasNext()).isTrue();
        assertThat(page.getTotalSize()).isEqualTo(3);
        // The operation returned no cursor: the token carries the next offset.
        assertThat(page.getNextPageToken()).isNotEmpty();
        // The lower-case keys arrive as the constant names, as over REST.
        then(searchBooks)
                .should()
                .execute(new io.americanexpress.synapse.samples.bookstore.contract.SearchBooksRequest("herbert",
                        new PageQuery(null, null, 1, List
                                .of(new SortOrder("TITLE", SortOrder.Direction.ASC),
                                        new SortOrder("PUBLISHED_YEAR", SortOrder.Direction.DESC)))));
    }

    @Test
    void anUnsetPageFieldTakesTheDefault() {
        given(searchBooks.execute(any())).willReturn(PageResult.of(List.of(), false));

        SearchBooksResponse page = books().searchBooks(SearchBooksRequest.getDefaultInstance());

        assertThat(page.getHasNext()).isFalse();
        assertThat(page.getNextPageToken()).isEmpty();
        assertThat(page.hasTotalSize()).isFalse();
        then(searchBooks)
                .should()
                .execute(new io.americanexpress.synapse.samples.bookstore.contract.SearchBooksRequest(null,
                        PageQuery.first(20)));
    }

    private BookServiceGrpc.BookServiceBlockingStub books() {
        return stub.withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private static GetBookRequest get(UUID id) {
        return GetBookRequest.newBuilder().setId(id.toString()).build();
    }

    private static UpdateBookRequest.Builder update(String etag) {
        return UpdateBookRequest
                .newBuilder()
                .setId(ID.toString())
                .setIsbn(ISBN)
                .setTitle("Dune")
                .setAuthor("Frank Herbert")
                .setEtag(etag);
    }

    private static <T extends Message> T detail(StatusRuntimeException e, Class<T> type) {
        com.google.rpc.Status status = Objects.requireNonNull(StatusProto.fromThrowable(e), "status details");
        for (Any any : status.getDetailsList()) {
            if (any.is(type)) {
                try {
                    return any.unpack(type);
                } catch (InvalidProtocolBufferException ex) {
                    throw new AssertionError(ex);
                }
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + status);
    }
}
