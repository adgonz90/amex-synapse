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
package io.americanexpress.synapse.api.rest.internal.paging;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pages through cursor-based operations on a real server: the operation's raw cursors are wrapped in tokens bound to
 * the operation, the sort and the filters, and never reach the client.
 */
@SpringBootTest(classes = RestPagingIT.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false", "synapse.operations.search-books.logging.payloads=true"})
@AutoConfigureRestTestClient
class RestPagingIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The prefix of every raw cursor the fixture operations issue. */
    private static final String RAW = "RAW-";

    private static final String NOT_VALID = "is not valid for this request";

    @Autowired
    private RestTestClient client;

    @Autowired
    private SearchBooksOperation books;

    @Autowired
    private LettersOperation letters;

    @Autowired
    private TenantBooksOperation tenantBooks;

    /** A response: its status, headers and body. */
    record Result(int status, HttpHeaders headers, String body) {

        JsonNode json() {
            return MAPPER.readTree(body);
        }

        String nextCursor() {
            return json().get("nextCursor").asString();
        }

        List<String> titles() {
            return json().get("items").valueStream().map(item -> item.get("title").asString()).toList();
        }
    }

    static Result call(RestTestClient.RequestHeadersSpec<?> spec) {
        var result = spec.exchange().returnResult(String.class);
        String body = result.getResponseBody();
        return new Result(result.getStatus().value(), result.getResponseHeaders(), body == null ? "" : body);
    }

    @BeforeEach
    void forgetInputs() {
        books.inputs().clear();
        letters.inputs().clear();
    }

    @Test
    @Tag("smoke")
    void firstPageThenCursorPage() {
        Result first = call(client.get().uri("/books?size=2"));

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.titles()).containsExactly("A Wizard of Earthsea", "Children of Dune");
        assertThat(first.json().get("hasNext").asBoolean()).isTrue();
        assertThat(first.json().get("total").asLong()).isEqualTo(6);
        String token = first.nextCursor();
        assertThat(token).doesNotStartWith(RAW).matches("[A-Za-z0-9_-]+");

        Result second = call(client.get().uri("/books?size=2&cursor={cursor}", token));

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.titles()).containsExactly("Dune", "Dune Messiah");
        assertThat(books.inputs()).hasSize(2);
        assertThat(books.inputs().get(0).page().cursor()).isNull();
        // The operation received the raw cursor it issued.
        assertThat(books.inputs().get(1).page().cursor()).isEqualTo(RAW + "2");
        assertThat(books.inputs().get(1).author()).isNull();
    }

    @Test
    void payloadLogsMaskTheRawCursorsWhileTheClientGetsTheToken() {
        Logger payloads = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.payload");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        payloads.addAppender(appender);
        Level level = payloads.getLevel();
        payloads.setLevel(Level.DEBUG);
        Result first;
        Result second;
        try {
            first = call(client.get().uri("/books?size=2"));
            second = call(client.get().uri("/books?size=2&cursor={cursor}", first.nextCursor()));
        } finally {
            payloads.setLevel(level);
            payloads.detachAppender(appender);
        }

        // @Masked is for logs only: the page body still carries the wrapped token, which still works.
        assertThat(first.nextCursor()).doesNotStartWith(RAW).matches("[A-Za-z0-9_-]+");
        assertThat(second.status()).isEqualTo(200);
        assertThat(books.inputs().get(1).page().cursor()).isEqualTo(RAW + "2");
        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines)
                .anyMatch(line -> line.startsWith("Operation search-books input")
                        && line.contains("\"cursor\":\"***\""))
                .anyMatch(line -> line.startsWith("Operation search-books output")
                        && line.contains("\"nextCursor\":\"***\""))
                .noneMatch(line -> line.contains(RAW));
    }

    @Test
    void pageQueryBindsOnPost() {
        Result first = call(client
                .post()
                .uri("/books/search?size=2")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"author\":\"guin\"}"));

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.titles()).containsExactly("A Wizard of Earthsea", "The Dispossessed");
        assertThat(first.json().get("hasNext").asBoolean()).isTrue();

        Result next = call(client
                .post()
                .uri("/books/search?size=2&cursor={cursor}", first.nextCursor())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"author\":\"guin\"}"));

        assertThat(next.status()).isEqualTo(200);
        assertThat(next.titles()).containsExactly("The Left Hand of Darkness");
        assertThat(next.json().get("hasNext").asBoolean()).isFalse();
        assertThat(next.json().has("nextCursor")).isFalse();
        assertThat(books.inputs().get(1))
                .isEqualTo(new SearchBooksRequest("guin", PageQuery
                        .ofCursor(RAW + "2", 2,
                                List.of())));
    }

    @Test
    void wholePageQueryInputPagesToo() {
        Result first = call(client.get().uri("/authors?size=1"));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().get("items").get(0).asString()).isEqualTo("guin");

        Result next = call(client.get().uri("/authors?size=1&cursor={cursor}", first.nextCursor()));

        assertThat(next.status()).isEqualTo(200);
        assertThat(next.json().get("items").get(0).asString()).isEqualTo("herbert");
        assertThat(next.json().get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void sizeOverTheCapIs400() {
        Result result = call(client.get().uri("/books?size=101"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(violations(result)).containsExactly(Map.entry("size", "must be less than or equal to 100"));
        assertThat(books.inputs()).isEmpty();
    }

    @Test
    void unknownSortKeyIs400() {
        Result result = call(client.get().uri("/books?sort=price"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(violations(result)).extracting(Map.Entry::getKey).containsExactly("sort");
        assertThat(books.inputs()).isEmpty();
    }

    @Test
    void tamperedCursorIs400() {
        String token = call(client.get().uri("/books?size=2")).nextCursor();
        books.inputs().clear();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

        // The version byte, the key id, the nonce, the ciphertext and the tag; the last character carries the tag's
        // final bits.
        for (int position : List.of(0, 3, 10, token.length() / 2, token.length() - 2, token.length() - 1)) {
            // Flipping the high bit of the 6-bit value changes a bit that the decoder keeps, even in the last
            // character, whose low bits are padding.
            char changed = alphabet.charAt(alphabet.indexOf(token.charAt(position)) ^ 0x20);
            String tampered = token.substring(0, position) + changed + token.substring(position + 1);

            Result result = call(client.get().uri("/books?size=2&cursor={cursor}", tampered));

            assertThat(result.status()).as("position %d", position).isEqualTo(400);
            assertCursorViolation(result);
        }
        assertThat(books.inputs()).isEmpty();
    }

    @Test
    void cursorFromAnotherRouteSortOrFilterIs400() {
        String fromBooks = call(client.get().uri("/books?size=2")).nextCursor();
        String byTitle = call(client.get().uri("/books?size=2&sort=title")).nextCursor();
        String forGuin = call(client.get().uri("/books?size=1&author=guin")).nextCursor();
        String fromAuthors = call(client.get().uri("/authors?size=1")).nextCursor();
        books.inputs().clear();

        assertCursorViolation(call(client.get().uri("/letters?size=2&cursor={cursor}", fromBooks)));
        // /authors and /letters both take a whole PageQuery: the same digest and sort, only the operation differs.
        assertCursorViolation(call(client.get().uri("/letters?size=1&cursor={cursor}", fromAuthors)));
        assertCursorViolation(call(client.get().uri("/books?size=2&sort=-title&cursor={cursor}", byTitle)));
        assertCursorViolation(call(client.get().uri("/books?size=2&cursor={cursor}", byTitle)));
        assertCursorViolation(call(client.get().uri("/books?size=1&author=herbert&cursor={cursor}", forGuin)));
        assertCursorViolation(call(client.get().uri("/books?size=1&cursor={cursor}", forGuin)));

        assertThat(books.inputs()).isEmpty();
        assertThat(letters.inputs()).isEmpty();
        // The same token in its own scope still works, and the page size may change.
        assertThat(call(client.get().uri("/books?size=3&sort=title&cursor={cursor}", byTitle)).status())
                .isEqualTo(200);
        assertThat(call(client.get().uri("/books?size=1&author=guin&cursor={cursor}", forGuin)).status())
                .isEqualTo(200);
    }

    @Test
    void cursorOfOneTenantIs400ForAnother() {
        String forGuin = call(client.get().uri("/shelves?size=1").header("X-Tenant", "guin")).nextCursor();
        tenantBooks.inputs().clear();

        assertCursorViolation(
                call(client.get().uri("/shelves?size=1&cursor={cursor}", forGuin).header("X-Tenant", "herbert")));
        assertCursorViolation(call(client.get().uri("/shelves?size=1&cursor={cursor}", forGuin)));
        assertThat(tenantBooks.inputs()).isEmpty();

        Result own = call(client.get().uri("/shelves?size=1&cursor={cursor}", forGuin).header("X-Tenant", "guin"));
        assertThat(own.status()).isEqualTo(200);
        assertThat(own.titles()).containsExactly("The Dispossessed");
        assertThat(tenantBooks.inputs())
                .singleElement()
                .satisfies(input -> assertThat(input.page().cursor())
                        .isEqualTo(RAW + "1"));
    }

    @Test
    void rawCursorNeverReachesTheClient() {
        Result get = call(client.get().uri("/books?size=2"));
        Result head = call(client.head().uri("/books?size=2"));
        Result next = call(client.get().uri("/books?size=2&cursor={cursor}", get.nextCursor()));
        Result headNext = call(client.head().uri("/books?size=2&cursor={cursor}", get.nextCursor()));
        Result post = call(client
                .post()
                .uri("/books/search?size=1")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"author\":\"herbert\"}"));
        Result postNext = call(client
                .post()
                .uri("/books/search?size=1&cursor={cursor}", post.nextCursor())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"author\":\"herbert\"}"));
        Result authors = call(client.get().uri("/authors?size=1"));
        Result rejected = call(client.get().uri("/letters?size=1&cursor={cursor}", get.nextCursor()));

        // The operations did issue raw cursors on every call.
        assertThat(books.inputs()).hasSize(6);
        for (Result result : List.of(get, head, next, headNext, post, postNext, authors, rejected)) {
            assertThat(result.body()).doesNotContain(RAW);
            result.headers().forEach((name, values) -> assertThat(values).as(name).noneMatch(v -> v.contains(RAW)));
        }
        assertThat(List.of(get, head, next, headNext, post, postNext, authors))
                .allSatisfy(result -> assertThat(result.status()).isEqualTo(200));
        assertThat(head.body()).isEmpty();
        assertThat(postNext.json().has("nextCursor")).isTrue();
    }

    @Test
    void cursorFromAnotherInstanceIs400() {
        String local = call(client.get().uri("/books?size=2")).nextCursor();
        try (ConfigurableApplicationContext other = new SpringApplicationBuilder(TestApplication.class)
                .properties("server.port=0", "management.logging.export.otlp.enabled=false",
                        "management.tracing.export.otlp.enabled=false",
                        "management.otlp.metrics.export.enabled=false")
                .run()) {
            RestTestClient otherClient = RestTestClient
                    .bindToServer()
                    .baseUrl("http://localhost:" + other.getEnvironment().getProperty("local.server.port"))
                    .build();
            String remote = call(otherClient.get().uri("/books?size=2")).nextCursor();
            SearchBooksOperation otherBooks = other.getBean(SearchBooksOperation.class);
            otherBooks.inputs().clear();
            books.inputs().clear();

            // Each instance generated its own key, so neither reads the other's tokens.
            assertCursorViolation(call(client.get().uri("/books?size=2&cursor={cursor}", remote)));
            assertCursorViolation(call(otherClient.get().uri("/books?size=2&cursor={cursor}", local)));

            assertThat(books.inputs()).isEmpty();
            assertThat(otherBooks.inputs()).isEmpty();
        }
    }

    @Test
    void getAndHeadSendLinkAndPostDoesNot() {
        Result get = call(client.get().uri("/books?size=2"));
        Result head = call(client.head().uri("/books?size=2"));
        Result post = call(client
                .post()
                .uri("/books/search?size=2")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"author\":\"guin\"}"));

        String expected = "</books?size=2&cursor=" + get.nextCursor() + ">; rel=\"next\"";
        assertThat(get.headers().get(HttpHeaders.LINK)).containsExactly(expected);
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.headers().get(HttpHeaders.LINK)).hasSize(1);
        assertThat(head.headers().getFirst(HttpHeaders.LINK))
                .startsWith("</books?size=2&cursor=")
                .endsWith(">; rel=\"next\"")
                .doesNotContain(RAW);
        assertThat(post.status()).isEqualTo(200);
        assertThat(post.json().get("hasNext").asBoolean()).isTrue();
        assertThat(post.headers().get(HttpHeaders.LINK)).isNull();
    }

    @Test
    void followingLinksReachesTheLastPage() {
        List<String> titles = new ArrayList<>();
        String uri = "/books?size=2";
        int pages = 0;
        Result page;
        while (true) {
            page = call(client.get().uri(URI.create(uri)));
            assertThat(page.status()).isEqualTo(200);
            titles.addAll(page.titles());
            pages++;
            String link = page.headers().getFirst(HttpHeaders.LINK);
            if (link == null) {
                break;
            }
            assertThat(link).endsWith(">; rel=\"next\"").doesNotContain(RAW);
            uri = link.substring(1, link.indexOf('>'));
        }

        assertThat(pages).isEqualTo(3);
        assertThat(titles)
                .containsExactly("A Wizard of Earthsea", "Children of Dune", "Dune", "Dune Messiah",
                        "The Dispossessed", "The Left Hand of Darkness");
        assertThat(page.json().get("hasNext").asBoolean()).isFalse();
    }

    private static void assertCursorViolation(Result result) {
        assertThat(result.status()).isEqualTo(400);
        assertThat(result.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(violations(result)).containsExactly(Map.entry("cursor", NOT_VALID));
    }

    private static List<Map.Entry<String, String>> violations(Result result) {
        return result
                .json()
                .get("violations")
                .valueStream()
                .map(v -> Map.entry(v.get("field").asString(), v.get("message").asString()))
                .toList();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        SearchBooksOperation searchBooksOperation() {
            return new SearchBooksOperation();
        }

        @Bean
        ListAuthorsOperation listAuthorsOperation() {
            return new ListAuthorsOperation();
        }

        @Bean
        LettersOperation lettersOperation() {
            return new LettersOperation();
        }

        @Bean
        TenantBooksOperation tenantBooksOperation() {
            return new TenantBooksOperation();
        }

        @Bean
        RestBindings paging(SearchBooksOperation books, ListAuthorsOperation authors, LettersOperation letters,
                TenantBooksOperation tenantBooks) {
            return RestBindings
                    .builder()
                    .get("/books", books)
                    .sortable(SortKey.class)
                    .post("/books/search", books)
                    .sortable(SortKey.class)
                    .get("/authors", authors)
                    .get("/letters", letters)
                    .get("/shelves", tenantBooks)
                    .from(TenantBooksRequest::tenant, request -> request.headers().firstHeader("X-Tenant"))
                    .build();
        }
    }

    enum SortKey {
        TITLE, YEAR
    }

    record Book(String title, String author, int year) {
    }

    record SearchBooksRequest(@Nullable String author, PageQuery page) {
    }

    private static final List<Book> BOOKS = List
            .of(new Book("A Wizard of Earthsea", "guin", 1968), new Book("Children of Dune", "herbert", 1976),
                    new Book("Dune", "herbert", 1965), new Book("Dune Messiah", "herbert", 1969),
                    new Book("The Dispossessed", "guin", 1974), new Book("The Left Hand of Darkness", "guin", 1969));

    /** Pages with raw cursors {@code RAW-<index>}, and records the inputs it receives. */
    static class SearchBooksOperation implements QueryOperation<SearchBooksRequest, PageResult<Book>> {

        private final List<SearchBooksRequest> inputs = new CopyOnWriteArrayList<>();

        /** Public, so the call reaches the target through the pipeline proxy. */
        public List<SearchBooksRequest> inputs() {
            return inputs;
        }

        @Override
        public PageResult<Book> execute(SearchBooksRequest input) {
            inputs.add(input);
            List<Book> matching = BOOKS
                    .stream()
                    .filter(book -> input.author() == null || book.author().equals(input.author()))
                    .sorted(order(input.page().sort()))
                    .toList();
            return page(matching, input.page(), (long) matching.size());
        }

        private static Comparator<Book> order(List<SortOrder> sort) {
            Comparator<Book> order = Comparator.comparing(Book::title);
            for (SortOrder each : sort.reversed()) {
                Comparator<Book> key = SortKey.valueOf(each.property()) == SortKey.TITLE
                        ? Comparator.comparing(Book::title)
                        : Comparator.comparingInt(Book::year);
                order = (each.direction() == SortOrder.Direction.DESC ? key.reversed() : key).thenComparing(order);
            }
            return order;
        }
    }

    record TenantBooksRequest(@Nullable String tenant, PageQuery page) {
    }

    /** Pages the books of the tenant named by {@code X-Tenant}, bound with {@code .from(...)}. */
    static class TenantBooksOperation implements QueryOperation<TenantBooksRequest, PageResult<Book>> {

        private final List<TenantBooksRequest> inputs = new CopyOnWriteArrayList<>();

        /** Public, so the call reaches the target through the pipeline proxy. */
        public List<TenantBooksRequest> inputs() {
            return inputs;
        }

        @Override
        public PageResult<Book> execute(TenantBooksRequest input) {
            inputs.add(input);
            List<Book> owned = BOOKS.stream().filter(book -> book.author().equals(input.tenant())).toList();
            return page(owned, input.page(), null);
        }
    }

    /** A {@code PageQuery} as the whole input. */
    static class ListAuthorsOperation implements QueryOperation<PageQuery, PageResult<String>> {
        @Override
        public PageResult<String> execute(PageQuery input) {
            return page(List.of("guin", "herbert"), input, null);
        }
    }

    /** Another cursor operation, with cursors of the same shape as the books. */
    static class LettersOperation implements QueryOperation<PageQuery, PageResult<String>> {

        private final List<PageQuery> inputs = new CopyOnWriteArrayList<>();

        /** Public, so the call reaches the target through the pipeline proxy. */
        public List<PageQuery> inputs() {
            return inputs;
        }

        @Override
        public PageResult<String> execute(PageQuery input) {
            inputs.add(input);
            return page(List.of("a", "b", "c"), input, null);
        }
    }

    /** One page of the items, starting at the raw cursor's index. */
    static <T> PageResult<T> page(List<T> items, PageQuery page, @Nullable Long total) {
        String cursor = page.cursor();
        int start = cursor == null ? 0 : Integer.parseInt(cursor.substring(RAW.length()));
        int end = Math.min(start + page.size(), items.size());
        String next = end < items.size() ? RAW + end : null;
        return new PageResult<>(items.subList(start, end), next, next != null, total);
    }
}
