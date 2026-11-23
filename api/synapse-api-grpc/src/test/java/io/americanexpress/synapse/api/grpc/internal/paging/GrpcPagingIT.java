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
package io.americanexpress.synapse.api.grpc.internal.paging;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.ListPagedBooksRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.ListPagedBooksResponse;
import io.americanexpress.synapse.api.grpc.fixture.proto.OffsetBookServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.PageOnlyRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.PageWithoutTotalResponse;
import io.americanexpress.synapse.api.grpc.fixture.proto.PagedBook;
import io.americanexpress.synapse.api.grpc.fixture.proto.PagedBookServiceGrpc;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.operation.OperationNames;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * AIP-158 page tokens over gRPC, through the real pipeline on the in-process server: {@code page_size},
 * {@code page_token}, {@code skip} and {@code order_by} bind the operation's {@code PageQuery}, its raw cursor is
 * wrapped into {@code next_page_token} with core's cursor codec, and a token is valid only for the operation, sort and
 * filters it was issued for. Failures are REST's: {@code VALIDATION_FAILED} ({@code INVALID_ARGUMENT}), with the
 * violation on the gRPC field.
 */
@SpringBootTest(classes = GrpcPagingIT.TestApplication.class)
@TestPropertySource(properties = {"spring.grpc.server.inprocess.name=synapse-grpc-paging", "spring.grpc.server.port=0",
        "management.tracing.export.otlp.enabled=false", "management.logging.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class GrpcPagingIT {

    private static final String SHELF = "sf";

    private static final int BOOKS = 45;

    private static final String GENERATED_KEY = "synapse.paging.cursor.key is not set, so paging cursors are "
            + "encrypted with a key generated at startup";

    /** The violation of a token past {@code synapse.paging.max-offset}, at its default of 1000. */
    private static final String OFFSET_LIMIT = "has reached the offset limit (1000); narrow the query or use keyset "
            + "paging";

    private static final List<SortOrder> BY_TITLE = List.of(new SortOrder("TITLE", SortOrder.Direction.ASC));

    @Value("${spring.grpc.server.inprocess.name}")
    String server;

    @Autowired
    Calls calls;

    @Autowired
    PageCursors cursors;

    @BeforeEach
    void reset() {
        calls.count.set(0);
        calls.offsetCount.set(0);
    }

    @Test
    @Tag("smoke")
    void pagesThroughEveryItem() throws InterruptedException {
        List<String> ids = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        String token = "";
        do {
            ListPagedBooksResponse page = list(request(token).setPageSize(20).build());
            sizes.add(page.getBooksCount());
            page.getBooksList().forEach(book -> ids.add(book.getId()));
            assertThat(page.getTotalSize()).isEqualTo(BOOKS);
            // The raw cursor (an index) never reaches the caller.
            assertThat(page.getNextPageToken()).doesNotMatch("[0-9]+");
            assertThat(page.getHasNext()).isEqualTo(!page.getNextPageToken().isEmpty());
            token = page.getNextPageToken();
        } while (!token.isEmpty());

        assertThat(sizes).containsExactly(20, 20, 5);
        assertThat(ids).hasSize(BOOKS).doesNotHaveDuplicates().isEqualTo(Library.ids());
    }

    @Test
    void sortAndOffsetReachTheOperation() throws InterruptedException {
        ListPagedBooksResponse byYear = list(request("").setOrderBy("published_year desc").setPageSize(3).build());
        ListPagedBooksResponse skipped = list(request("").setSkip(40).build());
        PageWithoutTotalResponse all = call(stub -> stub.listAll(PageOnlyRequest.newBuilder().setPageSize(2).build()));

        assertThat(byYear.getBooksList()).extracting(PagedBook::getPublishedYear).containsExactly(1994, 1993, 1992);
        // An offset page by default size: the last five books, and no next token, as the operation returns none.
        assertThat(skipped.getBooksList())
                .extracting(PagedBook::getId)
                .containsExactly("b41", "b42", "b43", "b44", "b45");
        assertThat(skipped.getNextPageToken()).isEmpty();
        // A PageQuery input pages too, and a response without total_size leaves the total out.
        assertThat(all.getBooksList()).extracting(PagedBook::getId).containsExactly("b01", "b02");
        assertThat(all.getNextPageToken()).isNotEmpty();
    }

    @Test
    void tamperedTokenIsInvalidArgument() throws InterruptedException {
        String token = list(request("").setPageSize(5).build()).getNextPageToken();
        // A character in the middle carries six bits of the token (the last one may carry unused bits).
        int middle = token.length() / 2;
        char changed = token.charAt(middle) == 'A' ? 'Q' : 'A';
        String tampered = token.substring(0, middle) + changed + token.substring(middle + 1);
        calls.count.set(0);

        StatusRuntimeException e = failure(stub -> stub.listBooks(request(tampered).build()));

        assertInvalid(e, "page_token", "is not valid for this request");
        assertThat(calls.count.get()).isZero();
    }

    @Test
    void cursorReplayedWithAnotherSortIsInvalid() throws InterruptedException {
        String byTitle = list(request("").setOrderBy("title").setPageSize(5).build()).getNextPageToken();
        calls.count.set(0);

        // Another sort, other filters, or another operation: never a page of other data (Review Focus 2).
        assertInvalid(failure(stub -> stub.listBooks(request(byTitle).setOrderBy("title desc").build())),
                "page_token", "is not valid for this request");
        assertInvalid(failure(stub -> stub.listBooks(request(byTitle).build())), "page_token",
                "is not valid for this request");
        assertInvalid(
                failure(stub -> stub
                        .listBooks(request(byTitle).setShelf("other").setOrderBy("title").build())),
                "page_token", "is not valid for this request");
        assertInvalid(failure(stub -> stub.listTitles(request(byTitle).setOrderBy("title").build())),
                "page_token", "is not valid for this request");
        assertThat(calls.count.get()).isZero();

        // The same request pages on, whatever its page size; the sort may be written with other spacing.
        ListPagedBooksResponse next = list(request(byTitle).setOrderBy(" title  asc ").setPageSize(2).build());
        // Titles run backwards: the first page by title was b45 to b41.
        assertThat(next.getBooksList()).extracting(PagedBook::getId).containsExactly("b40", "b39");
    }

    @Test
    void sizeOverTheCapIsInvalidArgument() {
        // REST's VALIDATION_FAILED and messages, on the gRPC field (REST names its size, offset and sort parameters).
        assertInvalid(failure(stub -> stub.listBooks(request("").setPageSize(101).build())), "page_size",
                "must be less than or equal to 100");
        assertInvalid(failure(stub -> stub.listBooks(request("").setSkip(1001).build())), "skip",
                "must be less than or equal to 1000");
        assertInvalid(failure(stub -> stub.listBooks(request("").setOrderBy("author").build())), "order_by",
                "has a key that is not allowed (allowed: title, published_year)");
        assertThat(calls.count.get()).isZero();
    }

    @Test
    void generatedKeyWarnsOnceWithBothPlugins(CapturedOutput output) {
        long before = generatedKeyWarnings(output);

        // A second context: its gRPC methods page, and another plugin reports too (OtherPluginReport, as REST's
        // RouteCompiler calls it), on the one PageCursors bean of the context.
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                .profiles("test")
                .properties("spring.grpc.server.inprocess.name=synapse-grpc-paging-key", "spring.grpc.server.port=0",
                        "management.tracing.export.otlp.enabled=false",
                        "management.logging.export.otlp.enabled=false",
                        "management.otlp.metrics.export.enabled=false")
                .run()) {
            assertThat(context.getBean(OtherPluginReport.class).reported).isTrue();
            assertThat(context.getBeansOfType(PageCursors.class)).hasSize(1);
        }

        assertThat(generatedKeyWarnings(output) - before).isOne();
    }

    private static long generatedKeyWarnings(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains(GENERATED_KEY)).count();
    }

    @Test
    void offsetPagedOperationPagesThroughEveryItem() throws InterruptedException {
        List<String> ids = new ArrayList<>();
        List<Boolean> hasNext = new ArrayList<>();
        String token = "";
        do {
            ListPagedBooksResponse page = byOffset(request(token).setPageSize(20).setOrderBy("title desc").build());
            page.getBooksList().forEach(book -> ids.add(book.getId()));
            hasNext.add(page.getHasNext());
            token = page.getNextPageToken();
        } while (!token.isEmpty());

        // PageResult.of(items, hasNext), as SpringDataPages returns it: the token carries the next offset.
        assertThat(hasNext).containsExactly(true, true, false);
        // Titles run backwards, so title desc is b01 to b45.
        assertThat(ids).isEqualTo(Library.ids());
    }

    @Test
    void offsetTokenReplayedWithAnotherSortOrTamperedIsInvalid() throws InterruptedException {
        String token = byOffset(request("").setPageSize(5).setOrderBy("title").build()).getNextPageToken();
        int middle = token.length() / 2;
        String tampered = token.substring(0, middle) + (token.charAt(middle) == 'A' ? 'Q' : 'A')
                + token.substring(middle + 1);

        assertInvalid(offsetFailure(request(token).setOrderBy("title desc").build()), "page_token",
                "is not valid for this request");
        assertInvalid(offsetFailure(request(token).setShelf("other").setOrderBy("title").build()), "page_token",
                "is not valid for this request");
        assertInvalid(offsetFailure(request(tampered).setOrderBy("title").build()), "page_token",
                "is not valid for this request");
        // The same request continues at the next offset.
        assertThat(byOffset(request(token).setOrderBy("title").setPageSize(2).build()).getBooksList())
                .extracting(PagedBook::getId)
                .containsExactly("b40", "b39");
    }

    @Test
    void restCappedTokenOverGrpcNamesPageToken() {
        // The token an unversioned REST route writes for GET ?offset=990&size=20&sort=title of the same operation and
        // filters (no extra entries, as a gRPC method), past max-offset 1000.
        String token = Objects
                .requireNonNull(cursors
                        .next(OperationNames.nameOf(OffsetBooksOperation.class),
                                new BookSearch(SHELF, new PageQuery(null, 990L, 20, BY_TITLE)), Map.of(),
                                PageResult.of(List.of(), true), 1000));

        assertInvalid(offsetFailure(request(token).setOrderBy("title").build()), "page_token", OFFSET_LIMIT);
        assertThat(calls.offsetCount.get()).isZero();
    }

    /** The same methods with {@code synapse.paging.max-offset} at 30, so a page of the 45 books passes the cap. */
    @Nested
    @TestPropertySource(properties = {"spring.grpc.server.inprocess.name=synapse-grpc-paging-capped",
            "synapse.paging.max-offset=30"})
    class AtALowerCap {

        @Value("${spring.grpc.server.inprocess.name}")
        String cappedServer;

        @Autowired
        Calls cappedCalls;

        @Test
        void cappedPageHasANextPageToken() throws InterruptedException {
            ListPagedBooksResponse page = byOffset(cappedServer,
                    request("").setSkip(20).setPageSize(20).setOrderBy("title").build());

            // 20 + 20 is past max-offset 30 while more books follow: a token, so the empty token still means the end.
            assertThat(page.getBooksCount()).isEqualTo(20);
            assertThat(page.getHasNext()).isTrue();
            assertThat(page.getNextPageToken()).isNotEmpty();
        }

        @Test
        void usingTheCappedTokenIsInvalidArgument() throws InterruptedException {
            String token = byOffset(cappedServer, request("").setSkip(20).setPageSize(20).setOrderBy("title").build())
                    .getNextPageToken();
            int before = cappedCalls.offsetCount.get();
            // The page that issued the token ran the operation, so the count below is a real check.
            assertThat(before).isPositive();

            StatusRuntimeException failure = catchThrowableOfType(StatusRuntimeException.class,
                    () -> byOffset(cappedServer, request(token).setOrderBy("title").build()));

            assertInvalid(failure, "page_token",
                    "has reached the offset limit (30); narrow the query or use keyset paging");
            // Rejected before the operation runs, as over REST.
            assertThat(cappedCalls.offsetCount.get()).isEqualTo(before);
        }
    }

    private ListPagedBooksResponse byOffset(ListPagedBooksRequest request) throws InterruptedException {
        return byOffset(server, request);
    }

    private static ListPagedBooksResponse byOffset(String server, ListPagedBooksRequest request)
            throws InterruptedException {
        ManagedChannel channel = InProcessChannelBuilder.forName(server).directExecutor().build();
        try {
            return OffsetBookServiceGrpc
                    .newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .listBooks(request);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private StatusRuntimeException offsetFailure(ListPagedBooksRequest request) {
        return catchThrowableOfType(StatusRuntimeException.class, () -> byOffset(request));
    }

    private static ListPagedBooksRequest.Builder request(String token) {
        return ListPagedBooksRequest.newBuilder().setShelf(SHELF).setPageToken(token);
    }

    private static void assertInvalid(StatusRuntimeException e, String field, String description) {
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("VALIDATION_FAILED");
        assertThat(detail(e, BadRequest.class).getFieldViolationsList())
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getField()).isEqualTo(field);
                    assertThat(violation.getDescription()).isEqualTo(description);
                });
    }

    private ListPagedBooksResponse list(ListPagedBooksRequest request) throws InterruptedException {
        return call(stub -> stub.listBooks(request));
    }

    private <T> T call(Function<PagedBookServiceGrpc.PagedBookServiceBlockingStub, T> rpc)
            throws InterruptedException {
        ManagedChannel channel = InProcessChannelBuilder.forName(server).directExecutor().build();
        try {
            return rpc.apply(PagedBookServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private StatusRuntimeException failure(Function<PagedBookServiceGrpc.PagedBookServiceBlockingStub, ?> rpc) {
        return catchThrowableOfType(StatusRuntimeException.class, () -> call(rpc));
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

    /** A shelf filter and a page: {@code ListPagedBooksRequest}. */
    record BookSearch(String shelf, PageQuery page) {
    }

    /** {@code PagedBook}. */
    record Book(String id, String title, int publishedYear) {
    }

    /** The sort keys: {@code order_by} names them {@code title} and {@code published_year}. */
    enum SortKey {
        TITLE, PUBLISHED_YEAR
    }

    /**
     * The 45 books of the shelf {@code sf}: {@code b01} to {@code b45}, whose titles run backwards ({@code b01} is
     * {@code Title 45}) and whose years run from 1950 to 1994.
     */
    static final class Library {

        private Library() {
        }

        static List<Book> books(String shelf) {
            if (!shelf.equals(SHELF)) {
                return List.of();
            }
            List<Book> books = new ArrayList<>();
            for (int i = 1; i <= BOOKS; i++) {
                books.add(new Book("b%02d".formatted(i), "Title %02d".formatted(BOOKS + 1 - i), 1949 + i));
            }
            return books;
        }

        static List<String> ids() {
            return books(SHELF).stream().map(Book::id).toList();
        }

        /** The books in the query's sort, then by id; the raw cursor is the index of the next book. */
        static PageResult<Book> page(String shelf, PageQuery query) {
            Comparator<Book> order = (first, second) -> 0;
            for (SortOrder sort : query.sort()) {
                Comparator<Book> key = switch (SortKey.valueOf(sort.property())) {
                    case TITLE -> Comparator.comparing(Book::title);
                    case PUBLISHED_YEAR -> Comparator.comparingInt(Book::publishedYear);
                };
                order = order.thenComparing(sort.direction() == SortOrder.Direction.DESC ? key.reversed() : key);
            }
            List<Book> sorted = books(shelf).stream().sorted(order.thenComparing(Book::id)).toList();
            String cursor = query.cursor();
            Long offset = query.offset();
            int start = cursor != null ? Integer.parseInt(cursor) : offset != null ? offset.intValue() : 0;
            int end = Math.min(start + query.size(), sorted.size());
            // An offset page returns no cursor, as an offset-paged operation (PageResult.of) does.
            boolean offsetPage = cursor == null && offset != null;
            String next = offsetPage || end >= sorted.size() ? null : String.valueOf(end);
            return PageResult.ofCursor(sorted.subList(start, end), next).withTotal(sorted.size());
        }
    }

    /**
     * How often {@link ListBooksOperation} and {@link OffsetBooksOperation} ran; a bean of its own, as the operation
     * bean is the pipeline's proxy.
     */
    static final class Calls {

        final AtomicInteger count = new AtomicInteger();

        final AtomicInteger offsetCount = new AtomicInteger();
    }

    /** Lists a shelf's books, counting its calls. */
    static class ListBooksOperation implements QueryOperation<BookSearch, PageResult<Book>> {

        private final Calls calls;

        ListBooksOperation(Calls calls) {
            this.calls = calls;
        }

        @Override
        public PageResult<Book> execute(BookSearch input) {
            calls.count.incrementAndGet();
            return Library.page(input.shelf(), input.page());
        }
    }

    /** Another operation with the same input and output. */
    static class ListTitlesOperation implements QueryOperation<BookSearch, PageResult<Book>> {

        @Override
        public PageResult<Book> execute(BookSearch input) {
            return Library.page(input.shelf(), input.page());
        }
    }

    /** Pages by offset only, with no cursor and no total: {@code PageResult.of(items, hasNext)}. */
    static class OffsetBooksOperation implements QueryOperation<BookSearch, PageResult<Book>> {

        private final Calls calls;

        OffsetBooksOperation(Calls calls) {
            this.calls = calls;
        }

        @Override
        public PageResult<Book> execute(BookSearch input) {
            calls.offsetCount.incrementAndGet();
            PageQuery query = input.page();
            // Library.page reads the offset; its cursor and total are dropped.
            PageResult<Book> page = Library
                    .page(input.shelf(), new PageQuery(null,
                            query.offset() == null ? 0L : query.offset(), query.size(), query.sort()));
            long end = (query.offset() == null ? 0 : query.offset()) + page.items().size();
            return PageResult.of(page.items(), end < Library.books(input.shelf()).size());
        }
    }

    /** Lists every book: a {@code PageQuery} input. */
    static class ListAllOperation implements QueryOperation<PageQuery, PageResult<Book>> {

        @Override
        public PageResult<Book> execute(PageQuery input) {
            PageResult<Book> page = Library.page(SHELF, input);
            return PageResult.ofCursor(page.items(), page.nextCursor());
        }
    }

    /**
     * Reports a generated cursor key once every singleton exists, as a second API plugin does (REST's
     * {@code RouteCompiler} for a paged route); this module has no REST plugin on its test class path.
     */
    static final class OtherPluginReport implements SmartInitializingSingleton {

        private final PageCursors cursors;

        boolean reported;

        OtherPluginReport(PageCursors cursors) {
            this.cursors = cursors;
        }

        @Override
        public void afterSingletonsInstantiated() {
            cursors.reportGeneratedKey();
            reported = true;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        Calls calls() {
            return new Calls();
        }

        @Bean
        ListBooksOperation listBooksOperation(Calls calls) {
            return new ListBooksOperation(calls);
        }

        @Bean
        ListTitlesOperation listTitlesOperation() {
            return new ListTitlesOperation();
        }

        @Bean
        ListAllOperation listAllOperation() {
            return new ListAllOperation();
        }

        @Bean
        OtherPluginReport otherPluginReport(PageCursors cursors) {
            return new OtherPluginReport(cursors);
        }

        @Bean
        OffsetBooksOperation offsetBooksOperation(Calls calls) {
            return new OffsetBooksOperation(calls);
        }

        @Bean
        GrpcBindings offsetBookBindings(OffsetBooksOperation offsetBooks) {
            return GrpcBindings
                    .service(OffsetBookServiceGrpc.getServiceDescriptor())
                    .unary(OffsetBookServiceGrpc.getListBooksMethod(), offsetBooks)
                    .sortable(SortKey.class)
                    .build();
        }

        @Bean
        GrpcBindings pagedBookBindings(ListBooksOperation listBooks, ListTitlesOperation listTitles,
                ListAllOperation listAll) {
            return GrpcBindings
                    .service(PagedBookServiceGrpc.getServiceDescriptor())
                    .unary(PagedBookServiceGrpc.getListBooksMethod(), listBooks)
                    .sortable(SortKey.class)
                    .unary(PagedBookServiceGrpc.getListTitlesMethod(), listTitles)
                    .sortable(SortKey.class)
                    .unary(PagedBookServiceGrpc.getListAllMethod(), listAll)
                    .build();
        }
    }
}
