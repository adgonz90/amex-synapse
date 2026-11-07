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
package io.americanexpress.synapse.samples.books.api.grpc;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Any;
import com.google.protobuf.Empty;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.samples.books.api.grpc.proto.Book;
import io.americanexpress.synapse.samples.books.api.grpc.proto.BookServiceGrpc;
import io.americanexpress.synapse.samples.books.api.grpc.proto.CatalogStats;
import io.americanexpress.synapse.samples.books.api.grpc.proto.CreateBookRequest;
import io.americanexpress.synapse.samples.books.api.grpc.proto.DeleteBookRequest;
import io.americanexpress.synapse.samples.books.api.grpc.proto.GetBookRequest;
import io.americanexpress.synapse.samples.books.api.grpc.proto.UpdateBookRequest;
import io.americanexpress.synapse.samples.books.contract.BookErrors;
import io.americanexpress.synapse.samples.books.operation.CreateBookOperation;
import io.americanexpress.synapse.samples.books.operation.DeleteBookOperation;
import io.americanexpress.synapse.samples.books.operation.GetBookOperation;
import io.americanexpress.synapse.samples.books.operation.GetCatalogStatsOperation;
import io.americanexpress.synapse.samples.books.operation.UpdateBookOperation;
import io.americanexpress.synapse.test.grpc.SynapseGrpcTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

/**
 * The gRPC slice: the bindings and the gRPC edge on Boot's in-process transport, with every bound operation mocked. It
 * checks how messages map to the operations' records and how errors map to status codes and {@code ErrorInfo}; the
 * operations themselves are tested in {@code BookOperationsTest}. The proto messages and the service's records share
 * names, so the records are written with their package ({@code model.Book}).
 */
@SynapseGrpcTest
@ImportGrpcClients(types = BookServiceGrpc.BookServiceBlockingStub.class)
class BooksGrpcTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final io.americanexpress.synapse.samples.books.model.Book DUNE = new io.americanexpress.synapse.samples.books.model.Book(
            ID, "978-0441172719", "Dune", "Frank Herbert", 1969, null, 1);

    private static final Metadata.Key<String> CHANNEL = Metadata.Key.of("x-channel", Metadata.ASCII_STRING_MARSHALLER);

    @Autowired
    BookServiceGrpc.BookServiceBlockingStub stub;

    @MockitoBean
    GetBookOperation getBook;

    @MockitoBean
    GetCatalogStatsOperation getCatalogStats;

    @MockitoBean
    CreateBookOperation createBook;

    @MockitoBean
    UpdateBookOperation updateBook;

    @MockitoBean
    DeleteBookOperation deleteBook;

    private BookServiceGrpc.BookServiceBlockingStub books() {
        return stub.withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private static GetBookRequest getRequest(UUID id) {
        return GetBookRequest.newBuilder().setId(id.toString()).build();
    }

    private static ErrorInfo errorInfo(StatusRuntimeException e) {
        com.google.rpc.Status status = Objects.requireNonNull(StatusProto.fromThrowable(e), "status details");
        for (Any detail : status.getDetailsList()) {
            if (detail.is(ErrorInfo.class)) {
                try {
                    return detail.unpack(ErrorInfo.class);
                } catch (InvalidProtocolBufferException ex) {
                    throw new AssertionError(ex);
                }
            }
        }
        throw new AssertionError("no ErrorInfo in " + status);
    }

    @Test
    void getBookMapsTheRecord() {
        given(getBook.execute(new io.americanexpress.synapse.samples.books.model.GetBookRequest(ID)))
                .willReturn(Optional.of(DUNE));

        Book book = books().getBook(getRequest(ID));

        assertThat(book.getId()).isEqualTo(ID.toString());
        assertThat(book.getIsbn()).isEqualTo("978-0441172719");
        assertThat(book.getTitle()).isEqualTo("Dune");
        assertThat(book.getAuthor()).isEqualTo("Frank Herbert");
        assertThat(book.hasYear()).isTrue();
        assertThat(book.getYear().getValue()).isEqualTo(1969);
        assertThat(book.hasAddedVia()).isFalse();
        assertThat(book.getVersion()).isEqualTo(1);
    }

    @Test
    void missingBookIsNotFoundWithTheBookCode() {
        given(getBook.execute(any())).willReturn(Optional.empty());

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> books().getBook(getRequest(ID)));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        // application-test.yaml turns detail on. BOOK_NOT_FOUND's template needs the id, which the edge doesn't have,
        // so the message is NOT_FOUND's; the reason stays BOOK_NOT_FOUND.
        assertThat(e.getStatus().getDescription()).isEqualTo("The resource was not found");
        assertThat(errorInfo(e).getReason()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(errorInfo(e).getDomain()).isEqualTo("rest-books");
        assertThat(errorInfo(e).getMetadataMap()).containsKey("correlationId");
    }

    @Test
    void updateWithoutVersionBindsNull() {
        given(updateBook.execute(any())).willReturn(DUNE);

        Book book = books()
                .updateBook(UpdateBookRequest
                        .newBuilder()
                        .setId(ID.toString())
                        .setIsbn("978-0441172719")
                        .setTitle("Dune")
                        .setAuthor("Frank Herbert")
                        .setYear(Int32Value.of(1969))
                        .build());

        assertThat(book.getTitle()).isEqualTo("Dune");
        then(updateBook)
                .should()
                .execute(new io.americanexpress.synapse.samples.books.model.UpdateBookRequest(ID, "978-0441172719",
                        "Dune", "Frank Herbert", 1969, null));
    }

    @Test
    void deleteBookAnswersEmpty() {
        Empty answer = books()
                .deleteBook(DeleteBookRequest.newBuilder().setId(ID.toString()).setVersion(Int64Value.of(2)).build());

        assertThat(answer).isEqualTo(Empty.getDefaultInstance());
        then(deleteBook).should().execute(new io.americanexpress.synapse.samples.books.model.DeleteBookRequest(ID, 2L));
    }

    @Test
    void catalogStatsTakeEmpty() {
        given(getCatalogStats.execute(any()))
                .willReturn(new io.americanexpress.synapse.samples.books.model.CatalogStats(3, 2));

        CatalogStats stats = books().getCatalogStats(Empty.getDefaultInstance());

        assertThat(stats.getBooks()).isEqualTo(3);
        assertThat(stats.getAuthors()).isEqualTo(2);
    }

    @Test
    void channelComesFromMetadata() {
        given(createBook.execute(any())).willReturn(DUNE);
        Metadata metadata = new Metadata();
        metadata.put(CHANNEL, "grpc");

        books()
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .createBook(CreateBookRequest
                        .newBuilder()
                        .setIsbn("978-0441172719")
                        .setTitle("Dune")
                        .setAuthor("Frank Herbert")
                        .build());

        then(createBook)
                .should()
                .execute(new io.americanexpress.synapse.samples.books.model.CreateBookRequest("978-0441172719", "Dune",
                        "Frank Herbert", null, "grpc"));
    }

    @Test
    void staleVersionIsAborted() {
        willThrow(new SynapseException(SynapseErrors.STALE_VERSION)).given(updateBook).execute(any());

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> books()
                        .updateBook(UpdateBookRequest
                                .newBuilder()
                                .setId(ID.toString())
                                .setIsbn("978-0441172719")
                                .setTitle("Dune")
                                .setAuthor("Frank Herbert")
                                .setVersion(Int64Value.of(1))
                                .build()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(errorInfo(e).getReason()).isEqualTo("STALE_VERSION");
        then(updateBook)
                .should()
                .execute(new io.americanexpress.synapse.samples.books.model.UpdateBookRequest(ID, "978-0441172719",
                        "Dune", "Frank Herbert", null, 1L));
    }

    @Test
    void duplicateIsbnIsAborted() {
        willThrow(new SynapseException(BookErrors.DUPLICATE_ISBN, "978-0441172719"))
                .given(createBook)
                .execute(any());

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> books()
                        .createBook(CreateBookRequest
                                .newBuilder()
                                .setIsbn("978-0441172719")
                                .setTitle("Dune")
                                .setAuthor("Frank Herbert")
                                .build()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
        // application-test.yaml turns synapse.errors.include-detail on.
        assertThat(e.getStatus().getDescription()).isEqualTo("A book with ISBN 978-0441172719 already exists");
        assertThat(errorInfo(e).getReason()).isEqualTo("DUPLICATE_ISBN");
    }
}
