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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.google.protobuf.Any;
import com.google.protobuf.Empty;
import com.google.protobuf.Int32Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.StringValue;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.samples.bookstore.contract.proto.Book;
import io.americanexpress.synapse.samples.bookstore.contract.proto.BookServiceGrpc;
import io.americanexpress.synapse.samples.bookstore.contract.proto.CreateBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.proto.DeleteBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.proto.GetBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.proto.SearchBooksRequest;
import io.americanexpress.synapse.samples.bookstore.contract.proto.SearchBooksResponse;
import io.americanexpress.synapse.samples.bookstore.contract.proto.UpdateBookRequest;
import io.americanexpress.synapse.samples.bookstore.scenario.Outcome.BookView;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bookstore over gRPC, with the stubs generated in contract-bookstore on a plaintext channel. It checks the status
 * of every failure against its code ({@code INVALID_ARGUMENT}, {@code NOT_FOUND}, {@code ALREADY_EXISTS},
 * {@code ABORTED}, {@code FAILED_PRECONDITION}), and the {@code etag} of every answer: set on a create and a read,
 * empty on an update, as REST sends {@code ETag}. gRPC has no status for a created book or an empty answer: a
 * {@code CreateBook} response is {@link Outcome.Kind#CREATED}, and {@code google.protobuf.Empty} is
 * {@link Outcome.Kind#NO_CONTENT}.
 *
 * <p>
 * The status of {@code PRECONDITION_FAILED} depends on more than the code: a stale {@code etag} is {@code ABORTED}
 * (AIP-154), a weak one {@code FAILED_PRECONDITION}. So the client doesn't check it; it keeps the status of every call
 * in {@link #lastStatus()}, and the scenario that sends the {@code etag} asserts it. The {@link Outcome} stays the
 * same on both protocols.
 */
public final class GrpcBookstoreClient implements BookstoreClient {

    /**
     * The contract's names of the fields whose gRPC names differ: the proto's snake_case names, the AIP-158 page
     * fields and the {@code etag} field that stands for REST's {@code If-Match}. A violation names the field of its
     * protocol, so the outcome translates them to compare the two.
     */
    private static final Map<String, String> CONTRACT_NAMES = Map
            .of("published_year", "publishedYear", "page_size",
                    "size", "page_token", "cursor", "skip", "offset", "order_by", "sort", "etag", "If-Match");

    private static final Metadata.Key<String> CORRELATION = Metadata.Key
            .of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    private final ManagedChannel channel;

    private Status.@Nullable Code lastStatus;

    /**
     * Creates the client.
     *
     * @param host the host of the gRPC server
     * @param port its port
     */
    public GrpcBookstoreClient(String host, int port) {
        this.channel = Grpc.newChannelBuilderForAddress(host, port, InsecureChannelCredentials.create()).build();
    }

    /**
     * Returns the status of this client's last call.
     *
     * @return the status the last call failed with, or {@code null} when it succeeded or no call was made
     */
    public Status.@Nullable Code lastStatus() {
        return lastStatus;
    }

    @Override
    public Outcome create(String isbn, String title, String author, @Nullable Integer year) {
        CreateBookRequest.Builder request = CreateBookRequest
                .newBuilder()
                .setIsbn(isbn)
                .setTitle(title)
                .setAuthor(author);
        if (year != null) {
            request.setPublishedYear(Int32Value.of(year));
        }
        return call(() -> {
            Book book = books().createBook(request.build());
            assertThat(book.getEtag()).as("etag of CreateBook").isNotEmpty();
            return Outcome.created(view(book), book.getEtag());
        });
    }

    @Override
    public Outcome get(UUID id) {
        return read(books(), id);
    }

    /** A GetBook on the stub. */
    private Outcome read(BookServiceGrpc.BookServiceBlockingStub stub, UUID id) {
        return call(() -> {
            Book book = stub.getBook(GetBookRequest.newBuilder().setId(id.toString()).build());
            assertThat(book.getEtag()).as("etag of GetBook").isNotEmpty();
            return Outcome.ok(view(book), book.getEtag());
        });
    }

    @Override
    public Outcome update(UUID id, @Nullable String etag, String isbn, String title, String author,
            @Nullable Integer year) {
        UpdateBookRequest.Builder request = UpdateBookRequest
                .newBuilder()
                .setId(id.toString())
                .setIsbn(isbn)
                .setTitle(title)
                .setAuthor(author);
        if (etag != null) {
            request.setEtag(etag);
        }
        if (year != null) {
            request.setPublishedYear(Int32Value.of(year));
        }
        return call(() -> {
            Book book = books().updateBook(request.build());
            // As REST sends no ETag on a PUT: read the book again for its new etag.
            assertThat(book.getEtag()).as("etag of UpdateBook").isEmpty();
            return Outcome.ok(view(book), null);
        });
    }

    @Override
    public Outcome delete(UUID id, @Nullable String etag) {
        DeleteBookRequest.Builder request = DeleteBookRequest.newBuilder().setId(id.toString());
        if (etag != null) {
            request.setEtag(etag);
        }
        return call(() -> {
            Empty answer = books().deleteBook(request.build());
            assertThat(answer).isEqualTo(Empty.getDefaultInstance());
            return Outcome.noContent();
        });
    }

    @Override
    public SearchOutcome search(@Nullable String author, int size, @Nullable String token, @Nullable Long offset,
            @Nullable String sort) {
        SearchBooksRequest.Builder request = SearchBooksRequest.newBuilder().setPageSize(size);
        if (author != null) {
            request.setAuthor(StringValue.of(author));
        }
        // The page fields are proto3 optional, so a field that is set is sent, even "" or 0, as a REST parameter is.
        if (token != null) {
            request.setPageToken(token);
        }
        if (offset != null) {
            request.setSkip(Math.toIntExact(offset));
        }
        if (sort != null) {
            request.setOrderBy(orderBy(sort));
        }
        lastStatus = null;
        try {
            SearchBooksResponse page = books().searchBooks(request.build());
            List<BookView> books = page.getBooksList().stream().map(GrpcBookstoreClient::view).toList();
            if (!page.getHasNext()) {
                assertThat(page.getNextPageToken()).as("next_page_token without a next page").isEmpty();
            }
            return SearchOutcome
                    .page(books, page.getNextPageToken().isEmpty() ? null : page.getNextPageToken(),
                            page.getHasNext(), page.hasTotalSize() ? page.getTotalSize() : null);
        } catch (StatusRuntimeException e) {
            Outcome failure = failure(e);
            return SearchOutcome.failed(Objects.requireNonNull(failure.code()), failure.violations());
        }
    }

    @Override
    public Echo echoedCorrelationId(UUID id, String correlationId) {
        Metadata sent = new Metadata();
        sent.put(CORRELATION, correlationId);
        AtomicReference<@Nullable Metadata> headers = new AtomicReference<>();
        AtomicReference<@Nullable Metadata> trailers = new AtomicReference<>();
        Outcome outcome = read(books()
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(sent),
                        MetadataUtils.newCaptureMetadataInterceptor(headers, trailers)),
                id);
        // A failure answers with trailers only; they carry the correlation id too.
        Metadata received = headers.get();
        if (received != null && received.containsKey(CORRELATION)) {
            return new Echo(outcome, received.get(CORRELATION));
        }
        Metadata last = trailers.get();
        return new Echo(outcome, last == null ? null : last.get(CORRELATION));
    }

    @Override
    public void close() {
        channel.shutdownNow();
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private BookServiceGrpc.BookServiceBlockingStub books() {
        return BookServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private Outcome call(Supplier<Outcome> call) {
        lastStatus = null;
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            return failure(e);
        }
    }

    /**
     * A failed call: its {@code ErrorInfo.reason}, its {@code BadRequest} fields and the status its code must have
     * (kept in {@link #lastStatus()}, and not checked for {@code PRECONDITION_FAILED}).
     */
    private Outcome failure(StatusRuntimeException e) {
        lastStatus = e.getStatus().getCode();
        com.google.rpc.Status status = StatusProto.fromThrowable(e);
        assertThat(status).as("status details of %s", e.getStatus()).isNotNull();
        String code = null;
        List<String> violations = new ArrayList<>();
        for (Any detail : Objects.requireNonNull(status).getDetailsList()) {
            try {
                if (detail.is(ErrorInfo.class)) {
                    code = detail.unpack(ErrorInfo.class).getReason();
                } else if (detail.is(BadRequest.class)) {
                    detail
                            .unpack(BadRequest.class)
                            .getFieldViolationsList()
                            .forEach(violation -> violations
                                    .add(CONTRACT_NAMES.getOrDefault(violation.getField(), violation.getField())));
                }
            } catch (InvalidProtocolBufferException ex) {
                throw new AssertionError(ex);
            }
        }
        assertThat(code).as("ErrorInfo of %s", e.getStatus()).isNotNull();
        Outcome failure = Outcome.failed(Objects.requireNonNull(code), violations);
        ErrorDetails details = Outcome.details(code);
        if (details != SynapseErrors.PRECONDITION_FAILED) {
            assertThat(e.getStatus().getCode()).as("status of %s", code).isEqualTo(status(details));
        }
        return failure;
    }

    /** The status gRPC answers an error with. */
    private static Status.Code status(ErrorDetails code) {
        if (code == SynapseErrors.ALREADY_EXISTS) {
            return Status.Code.ALREADY_EXISTS;
        }
        ErrorCategory category = code.category();
        return switch (category) {
            case INVALID_INPUT -> Status.Code.INVALID_ARGUMENT;
            case NOT_FOUND -> Status.Code.NOT_FOUND;
            case CONFLICT -> Status.Code.ABORTED;
            case PRECONDITION_FAILED -> Status.Code.FAILED_PRECONDITION;
            default -> throw new AssertionError("No scenario expects the category " + category);
        };
    }

    /** REST's sort ({@code title,-publishedYear}) in gRPC's form ({@code title, published_year desc}). */
    static String orderBy(String sort) {
        if (sort.isEmpty()) {
            return "";
        }
        StringJoiner orderBy = new StringJoiner(", ");
        for (String key : sort.split(",", -1)) {
            boolean descending = key.startsWith("-");
            String name = descending ? key.substring(1) : key;
            String snake = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            orderBy.add(descending ? snake + " desc" : snake);
        }
        return orderBy.toString();
    }

    private static BookView view(Book book) {
        return new BookView(UUID.fromString(book.getId()), book.getIsbn(), book.getTitle(), book.getAuthor(),
                book.hasPublishedYear() ? book.getPublishedYear().getValue() : null, book.getVersion());
    }
}
