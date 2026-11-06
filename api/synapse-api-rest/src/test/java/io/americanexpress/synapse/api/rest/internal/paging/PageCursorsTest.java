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

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestProperties;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.paging.CursorCodec;
import io.americanexpress.synapse.api.rest.paging.CursorScope;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.core.paging.SortOrder.Direction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PageCursorsTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static final String GENERATED_KEY_TEXT = "synapse.api.rest.paging.cursor.key is not set, so paging cursors"
            + " are encrypted with a key generated at startup: a cursor fails with 400 after a restart or on another"
            + " instance. Set the same base64 256-bit key on every instance (for example: openssl rand -base64 32).";

    private static final List<SortOrder> BY_TITLE = List.of(new SortOrder("title", Direction.ASC));

    private final InputDigest digest = new InputDigest();

    private final PageCursors cursors = new PageCursors(codec(KEY), digest, PagingSettings.defaults());

    private final RouteDefinition search = route(
            RestBindings.builder().get("/books", new SearchOperation()).sortable("title").build());

    private final RouteDefinition all = route(RestBindings.builder().get("/all", new AllOperation()).build());

    @Test
    void unwrapReplacesTokenWithRawCursor() {
        SearchRequest first = new SearchRequest("guin", new PageQuery(null, null, 2, BY_TITLE));
        String token = cursors.wrap(search, first, "raw-3");
        assertThat(token).isNotNull().isNotEqualTo("raw-3").doesNotContain("raw-3");

        Object unwrapped = cursors.unwrap(search, new SearchRequest("guin", new PageQuery(token, null, 5, BY_TITLE)));

        assertThat(unwrapped).isEqualTo(new SearchRequest("guin", new PageQuery("raw-3", null, 5, BY_TITLE)));
    }

    @Test
    void unwrapReplacesTheTokenOfAWholePageQueryInput() {
        String token = cursors.wrap(all, PageQuery.first(2), "raw-9");

        assertThat(cursors.unwrap(all, new PageQuery(token, null, 4, List.of())))
                .isEqualTo(new PageQuery("raw-9", null, 4, List.of()));
    }

    @Test
    void inputWithoutACursorIsReturnedAsItIs() {
        SearchRequest first = new SearchRequest("guin", PageQuery.first(2));

        assertThat(cursors.unwrap(search, first)).isSameAs(first);
        assertThat(cursors.unwrap(all, null)).isNull();
        PageQuery offset = PageQuery.ofOffset(4, 2, List.of());
        assertThat(cursors.unwrap(all, offset)).isSameAs(offset);
    }

    @Test
    void operationNameAloneBindsTheToken() {
        RouteDefinition other = route(RestBindings.builder().get("/other", new OtherAllOperation()).build());
        // Both inputs are a whole PageQuery: the same digest and the same (empty) sort; only the operation differs.
        String token = Objects.requireNonNull(cursors.wrap(all, PageQuery.first(2), "raw-1"));
        PageQuery next = new PageQuery(token, null, 2, List.of());

        assertThat(cursors.unwrap(all, next)).isEqualTo(new PageQuery("raw-1", null, 2, List.of()));
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors.unwrap(other, next))
                .satisfies(e -> assertThat(e.violations())
                        .containsExactly(new Violation("cursor", "is not valid for this request")));
    }

    @Test
    void wrapWithoutARawCursorIsNull() {
        assertThat(cursors.wrap(search, new SearchRequest("guin", PageQuery.first(2)), null)).isNull();
    }

    @Test
    void invalidTokenIsCursorViolation() {
        SearchRequest first = new SearchRequest("guin", new PageQuery(null, null, 2, BY_TITLE));
        String token = Objects.requireNonNull(cursors.wrap(search, first, "raw-3"));
        List<String> rejected = List
                .of("garbage", token.substring(0, token.length() - 4),
                        // Issued for another filter.
                        cursors.wrap(search, new SearchRequest("herbert", first.page()), "raw-3"),
                        // Issued for another sort.
                        cursors.wrap(search, new SearchRequest("guin", PageQuery.first(2)), "raw-3"),
                        // Issued for another operation.
                        cursors.wrap(all, PageQuery.first(2), "raw-3"),
                        // Issued with another key.
                        new PageCursors(codec(Base64
                                .getEncoder()
                                .encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9,
                                        10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
                                        30, 31,
                                        32})),
                                digest, PagingSettings.defaults()).wrap(search, first, "raw-3"));

        for (String each : rejected) {
            SearchRequest input = new SearchRequest("guin", new PageQuery(each, null, 2, BY_TITLE));
            List<ILoggingEvent> lines = captured(() -> assertThatExceptionOfType(SynapseException.class)
                    .isThrownBy(() -> cursors.unwrap(search, input))
                    .satisfies(e -> {
                        assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                        assertThat(e.violations())
                                .containsExactly(new Violation("cursor", "is not valid for this request"));
                        assertThat(e.getCause()).isNull();
                    }));
            // Only the codec's own DEBUG line; PageCursors adds none.
            assertThat(lines).singleElement().satisfies(line -> {
                assertThat(line.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(line.getFormattedMessage()).startsWith("Rejected a paging cursor: ");
            });
        }
    }

    @Test
    void cursorViolationUsesTheConfiguredParameterName() {
        PagingSettings renamed = new PagingSettings("pageToken", "offset", "size", "sort", 1000, 20, 100);
        PageCursors named = new PageCursors(codec(KEY), digest, renamed);

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> named.unwrap(all, new PageQuery("garbage", null, 2, List.of())))
                .satisfies(e -> assertThat(e.violations())
                        .containsExactly(new Violation("pageToken", "is not valid for this request")));
    }

    @Test
    void generatedKeyWarnsInEveryEnvironmentOnlyForPagedRoutes() {
        PageCursors generated = new PageCursors(codec(null), digest, PagingSettings.defaults());
        RouteDefinition plain = route(RestBindings.builder().get("/plain", new PlainOperation()).build());

        assertThat(captured(() -> generated.reportGeneratedKey(List.of(plain, search))))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.getLevel()).isEqualTo(Level.WARN);
                    assertThat(line.getLoggerName()).isEqualTo("io.americanexpress.synapse.api.rest");
                    assertThat(line.getFormattedMessage()).isEqualTo(GENERATED_KEY_TEXT);
                });
        assertThat(captured(() -> generated.reportGeneratedKey(List.of(plain)))).isEmpty();
        assertThat(captured(() -> generated.reportGeneratedKey(List.of()))).isEmpty();
        assertThat(captured(() -> generated.reportGeneratedKey(List.of(all))))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.getLevel()).isEqualTo(Level.WARN);
                    assertThat(line.getFormattedMessage()).isEqualTo(GENERATED_KEY_TEXT);
                });
    }

    @Test
    void configuredKeyOrAnotherCodecReportsNothing() {
        CursorCodec other = new CursorCodec() {
            @Override
            public String encode(String cursor, CursorScope scope) {
                return cursor;
            }

            @Override
            public String decode(String token, CursorScope scope) {
                return token;
            }
        };

        assertThat(captured(() -> cursors.reportGeneratedKey(List.of(search)))).isEmpty();
        assertThat(captured(() -> new PageCursors(other, digest, PagingSettings.defaults())
                .reportGeneratedKey(List.of(search)))).isEmpty();
    }

    @Test
    void aReplacementCodecMustReturnUrlSafeTokens() {
        for (String token : List.of("ab+/cd==", "a b", "", "abcé")) {
            PageCursors standardBase64 = new PageCursors(new CursorCodec() {
                @Override
                public String encode(String cursor, CursorScope scope) {
                    return token;
                }

                @Override
                public String decode(String received, CursorScope scope) {
                    return received;
                }
            }, digest, PagingSettings.defaults());

            assertThatExceptionOfType(IllegalStateException.class)
                    .as(token)
                    .isThrownBy(() -> standardBase64.wrap(all, PageQuery.first(2), "raw-secret"))
                    .satisfies(e -> {
                        assertThat(e.getMessage())
                                .contains("CursorCodec", "A-Z, a-z, 0-9, '-' and '_'")
                                .doesNotContain("raw-secret");
                        if (!token.isEmpty()) {
                            assertThat(e.getMessage()).doesNotContain(token);
                        }
                        assertThat(e.getCause()).isNull();
                    });
        }
        // The default codec's tokens are base64url without padding.
        assertThat(cursors.wrap(all, PageQuery.first(2), "raw-1")).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void recordThatRejectsTheRawCursorIsValidationFailedWithoutTheCursor() {
        RouteDefinition picky = route(RestBindings.builder().get("/picky", new PickyOperation()).build());
        String rejected = Objects
                .requireNonNull(cursors.wrap(picky, new PickyRequest("a", PageQuery.first(2)), "raw-rejected"));
        String own = Objects.requireNonNull(cursors.wrap(picky, new PickyRequest("a", PageQuery.first(2)), "raw-own"));

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors
                        .unwrap(picky, new PickyRequest("a", PageQuery
                                .ofCursor(rejected, 2,
                                        List.of()))))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                    assertThat(e.getCause()).isNull();
                    assertThat(e.violations()).isEmpty();
                });
        // The record's own error reaches the caller as it is.
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors
                        .unwrap(picky, new PickyRequest("a", PageQuery
                                .ofCursor(own, 2,
                                        List.of()))))
                .satisfies(e -> assertThat(e.details()).isEqualTo(SynapseErrors.NOT_FOUND));
    }

    /** Runs the action and returns what it logged on the REST logger, at DEBUG and above. */
    private static List<ILoggingEvent> captured(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");
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

    private static AesGcmCursorCodec codec(@Nullable String key) {
        SecureRandom random = new SecureRandom();
        SynapseRestProperties.Paging.Cursor cursor = new SynapseRestProperties.Paging.Cursor(key, List.of(), null);
        return new AesGcmCursorCodec(CursorKeys.from(cursor, random), Clock.systemUTC(), null, random);
    }

    private static RouteDefinition route(RestBindings bindings) {
        return new BindingValidator(PagingSettings.defaults()).validate(bindings.routes(), List.of()).getFirst();
    }

    record SearchRequest(@Nullable String author, PageQuery page) {
    }

    static class SearchOperation implements QueryOperation<SearchRequest, PageResult<String>> {
        @Override
        public PageResult<String> execute(SearchRequest input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** Rejects two raw cursors: one with an exception of its own, one with a {@code SynapseException}. */
    record PickyRequest(String author, PageQuery page) {
        PickyRequest {
            if ("raw-rejected".equals(page.cursor())) {
                throw new IllegalArgumentException("rejected " + page.cursor());
            }
            if ("raw-own".equals(page.cursor())) {
                throw new SynapseException(SynapseErrors.NOT_FOUND);
            }
        }
    }

    static class PickyOperation implements QueryOperation<PickyRequest, PageResult<String>> {
        @Override
        public PageResult<String> execute(PickyRequest input) {
            return PageResult.of(List.of(), false);
        }
    }

    static class OtherAllOperation implements QueryOperation<PageQuery, PageResult<String>> {
        @Override
        public PageResult<String> execute(PageQuery input) {
            return PageResult.of(List.of(), false);
        }
    }

    static class AllOperation implements QueryOperation<PageQuery, PageResult<String>> {
        @Override
        public PageResult<String> execute(PageQuery input) {
            return PageResult.of(List.of(), false);
        }
    }

    static class PlainOperation implements QueryOperation<Void, String> {
        @Override
        public String execute(Void input) {
            return "plain";
        }
    }
}
