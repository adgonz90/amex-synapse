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
package io.americanexpress.synapse.core.internal.paging;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.paging.CursorCodec;
import io.americanexpress.synapse.core.paging.CursorScope;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.core.paging.SortOrder.Direction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PageCursorsTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static final String GENERATED_KEY_TEXT = "synapse.paging.cursor.key is not set, so paging cursors are"
            + " encrypted with a key generated at startup: a cursor is rejected after a restart or on another"
            + " instance. Set the same base64 256-bit key on every instance (for example: openssl rand -base64 32).";

    private static final String SEARCH = "search-books";

    private static final String ALL = "list-all";

    private static final String FIELD = "cursor";

    private static final Map<String, ?> NONE = Map.of();

    /** {@code synapse.paging.max-offset}'s default. */
    private static final long CAP = 1000;

    /** The violation of an offset token past {@link #CAP}. */
    private static final String OFFSET_LIMIT = "has reached the offset limit (1000); narrow the query or use keyset "
            + "paging";

    private static final List<SortOrder> BY_TITLE = List.of(new SortOrder("TITLE", Direction.ASC));

    private final InputDigest digest = new InputDigest();

    private final PageCursors cursors = new PageCursors(codec(KEY), digest);

    @Test
    void unwrapReplacesTokenWithRawCursor() {
        SearchRequest first = new SearchRequest("guin", new PageQuery(null, null, 2, BY_TITLE));
        String token = cursors.wrap(SEARCH, first, NONE, "raw-3");
        assertThat(token).isNotNull().isNotEqualTo("raw-3").doesNotContain("raw-3");

        Object unwrapped = cursors
                .unwrap(SEARCH, new SearchRequest("guin", new PageQuery(token, null, 5, BY_TITLE)), NONE, FIELD, CAP);

        assertThat(unwrapped).isEqualTo(new SearchRequest("guin", new PageQuery("raw-3", null, 5, BY_TITLE)));
    }

    @Test
    void unwrapReplacesTheTokenOfAWholePageQueryInput() {
        String token = cursors.wrap(ALL, PageQuery.first(2), NONE, "raw-9");

        assertThat(cursors.unwrap(ALL, new PageQuery(token, null, 4, List.of()), NONE, FIELD, CAP))
                .isEqualTo(new PageQuery("raw-9", null, 4, List.of()));
    }

    @Test
    void inputWithoutACursorIsReturnedAsItIs() {
        SearchRequest first = new SearchRequest("guin", PageQuery.first(2));

        assertThat(cursors.unwrap(SEARCH, first, NONE, FIELD, CAP)).isSameAs(first);
        assertThat(cursors.unwrap(ALL, null, NONE, FIELD, CAP)).isNull();
        PageQuery offset = PageQuery.ofOffset(4, 2, List.of());
        assertThat(cursors.unwrap(ALL, offset, NONE, FIELD, CAP)).isSameAs(offset);
        Plain plain = new Plain("x");
        assertThat(cursors.unwrap(ALL, plain, NONE, FIELD, CAP)).isSameAs(plain);
    }

    @Test
    void operationNameAloneBindsTheToken() {
        // Both inputs are a whole PageQuery: the same digest and the same (empty) sort; only the operation differs.
        String token = Objects.requireNonNull(cursors.wrap(ALL, PageQuery.first(2), NONE, "raw-1"));
        PageQuery next = new PageQuery(token, null, 2, List.of());

        assertThat(cursors.unwrap(ALL, next, NONE, FIELD, CAP)).isEqualTo(new PageQuery("raw-1", null, 2, List.of()));
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors.unwrap("list-other", next, NONE, FIELD, CAP))
                .satisfies(e -> assertThat(e.violations())
                        .containsExactly(new Violation(FIELD, "is not valid for this request")));
    }

    @Test
    void wrapWithoutARawCursorIsNull() {
        assertThat(cursors.wrap(SEARCH, new SearchRequest("guin", PageQuery.first(2)), NONE, null)).isNull();
        assertThat(cursors.wrap(ALL, null, NONE, null)).isNull();
    }

    @Test
    void wrapUsesTheInputsSort() {
        SearchRequest byTitle = new SearchRequest("guin", new PageQuery(null, null, 2, BY_TITLE));
        String token = Objects.requireNonNull(cursors.wrap(SEARCH, byTitle, NONE, "raw-3"));

        // The token is bound to the sort of the input's PageQuery, as encode binds it to the sort it is given.
        assertThat(cursors.decode(SEARCH, BY_TITLE, byTitle, NONE, token, FIELD)).isEqualTo("raw-3");
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors.decode(SEARCH, List.of(), byTitle, NONE, token, FIELD));
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors
                        .decode(SEARCH, List.of(new SortOrder("TITLE", Direction.DESC)), byTitle, NONE, token,
                                FIELD));
        // An input without a PageQuery is scoped to no sort.
        String unsorted = Objects.requireNonNull(cursors.wrap(SEARCH, new Plain("x"), NONE, "raw-4"));
        assertThat(cursors.decode(SEARCH, List.of(), new Plain("x"), NONE, unsorted, FIELD)).isEqualTo("raw-4");
    }

    @Test
    void encodeAndDecodeBindTheGivenScope() {
        SearchRequest input = new SearchRequest("guin", PageQuery.first(2));
        String token = cursors.encode(SEARCH, BY_TITLE, input, NONE, "raw-5");

        assertThat(token).matches("[A-Za-z0-9_-]+");
        assertThat(cursors.decode(SEARCH, BY_TITLE, input, NONE, token, FIELD)).isEqualTo("raw-5");
        // The digest leaves the PageQuery out, so another token, size or sort in it doesn't change the scope.
        assertThat(cursors
                .decode(SEARCH, BY_TITLE, new SearchRequest("guin", new PageQuery("t", null, 9, List.of())), NONE,
                        token, FIELD))
                .isEqualTo("raw-5");
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors
                        .decode(SEARCH, BY_TITLE, new SearchRequest("herbert", PageQuery.first(2)), NONE, token,
                                FIELD));
    }

    @Test
    void extraEntriesBindTheToken() {
        Map<String, ?> v1 = Map.of("@version", "1.0.0");
        String token = Objects.requireNonNull(cursors.wrap(ALL, PageQuery.first(2), v1, "raw-1"));
        PageQuery next = new PageQuery(token, null, 2, List.of());

        assertThat(cursors.unwrap(ALL, next, v1, FIELD, CAP)).isEqualTo(new PageQuery("raw-1", null, 2, List.of()));
        for (Map<String, ?> other : List.<Map<String, ?>>of(NONE, Map.of("@version", "2.0.0"))) {
            assertThatExceptionOfType(SynapseException.class)
                    .isThrownBy(() -> cursors.unwrap(ALL, next, other, FIELD, CAP))
                    .satisfies(e -> assertThat(e.violations())
                            .containsExactly(new Violation(FIELD, "is not valid for this request")));
        }
    }

    @Test
    void invalidTokenIsCursorViolation() {
        SearchRequest first = new SearchRequest("guin", new PageQuery(null, null, 2, BY_TITLE));
        String token = Objects.requireNonNull(cursors.wrap(SEARCH, first, NONE, "raw-3"));
        List<String> rejected = List
                .of("garbage", token.substring(0, token.length() - 4),
                        // Issued for another filter.
                        cursors.wrap(SEARCH, new SearchRequest("herbert", first.page()), NONE, "raw-3"),
                        // Issued for another sort.
                        cursors.wrap(SEARCH, new SearchRequest("guin", PageQuery.first(2)), NONE, "raw-3"),
                        // Issued for another operation.
                        cursors.wrap(ALL, first, NONE, "raw-3"),
                        // Issued with another key.
                        new PageCursors(codec(Base64
                                .getEncoder()
                                .encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9,
                                        10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
                                        30, 31,
                                        32})),
                                digest).wrap(SEARCH, first, NONE, "raw-3"));

        for (String each : rejected) {
            SearchRequest input = new SearchRequest("guin", new PageQuery(each, null, 2, BY_TITLE));
            List<ILoggingEvent> lines = captured(() -> assertThatExceptionOfType(SynapseException.class)
                    .isThrownBy(() -> cursors.unwrap(SEARCH, input, NONE, FIELD, CAP))
                    .satisfies(e -> {
                        assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                        assertThat(e.violations())
                                .containsExactly(new Violation(FIELD, "is not valid for this request"));
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
    void rejectedTokenNamesTheCallersField() {
        Violation violation = new Violation("page_token", "is not valid for this request");

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors
                        .unwrap(ALL, new PageQuery("garbage", null, 2, List.of()), NONE,
                                "page_token", CAP))
                .satisfies(e -> assertThat(e.violations()).containsExactly(violation));
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors.decode(ALL, List.of(), null, NONE, "garbage", "page_token"))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                    assertThat(e.violations()).containsExactly(violation);
                });
    }

    @Test
    void generatedKeyIsReportedOnce() {
        PageCursors generated = new PageCursors(codec(null), digest);

        assertThat(captured(() -> {
            generated.reportGeneratedKey();
            // A second plugin of the same context reports through the same instance.
            generated.reportGeneratedKey();
        })).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getLoggerName()).isEqualTo("io.americanexpress.synapse.core");
            assertThat(line.getFormattedMessage()).isEqualTo(GENERATED_KEY_TEXT);
        });
        assertThat(captured(generated::reportGeneratedKey)).isEmpty();
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

        assertThat(captured(cursors::reportGeneratedKey)).isEmpty();
        assertThat(captured(() -> new PageCursors(other, digest).reportGeneratedKey())).isEmpty();
    }

    @Test
    void nonUrlSafeTokenIsAnIllegalState() {
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
            }, digest);

            for (Runnable call : List
                    .<Runnable>of(() -> standardBase64
                            .wrap(ALL, PageQuery.first(2), NONE,
                                    "raw-secret"),
                            () -> standardBase64.encode(ALL, List.of(), null, NONE, "raw-secret"))) {
                assertThatExceptionOfType(IllegalStateException.class)
                        .as(token)
                        .isThrownBy(call::run)
                        .satisfies(e -> {
                            assertThat(e.getMessage())
                                    .contains("CursorCodec", "A-Z, a-z, 0-9, '-' and '_'")
                                    // The anonymous codec's class name, PageCursorsTest$<n>.
                                    .contains(PageCursorsTest.class.getName() + "$")
                                    .doesNotContain("raw-secret");
                            if (!token.isEmpty()) {
                                assertThat(e.getMessage()).doesNotContain(token);
                            }
                            assertThat(e.getCause()).isNull();
                        });
            }
        }
        // The default codec's tokens are base64url without padding.
        assertThat(cursors.wrap(ALL, PageQuery.first(2), NONE, "raw-1")).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void recordThatRejectsTheRawCursorIsValidationFailedWithoutTheCursor() {
        String rejected = Objects
                .requireNonNull(cursors.wrap("picky", new PickyRequest("a", PageQuery.first(2)), NONE, "raw-rejected"));
        String own = Objects
                .requireNonNull(cursors.wrap("picky", new PickyRequest("a", PageQuery.first(2)), NONE, "raw-own"));

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors
                        .unwrap("picky", new PickyRequest("a", PageQuery.ofCursor(rejected, 2, List.of())), NONE,
                                FIELD, CAP))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                    assertThat(e.getCause()).isNull();
                    assertThat(e.violations()).isEmpty();
                });
        // The record's own error reaches the caller as it is.
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors
                        .unwrap("picky", new PickyRequest("a", PageQuery.ofCursor(own, 2, List.of())), NONE, FIELD,
                                CAP))
                .satisfies(e -> assertThat(e.details()).isEqualTo(SynapseErrors.NOT_FOUND));
    }

    @Test
    void offsetPageGetsATokenThatUnwrapsToTheNextOffset() {
        SearchRequest first = new SearchRequest("guin", new PageQuery(null, null, 2, BY_TITLE));
        String token = cursors.next(SEARCH, first, NONE, PageResult.of(List.of("a", "b"), true), 1000);

        assertThat(token).isNotNull().matches("[A-Za-z0-9_-]+");
        assertThat(cursors
                .unwrap(SEARCH, new SearchRequest("guin", new PageQuery(token, null, 5, BY_TITLE)), NONE,
                        FIELD, CAP))
                .isEqualTo(new SearchRequest("guin", new PageQuery(null, 2L, 5, BY_TITLE)));

        // From an offset request, the next offset is the offset plus the size, as REST's Link: next.
        SearchRequest third = new SearchRequest("guin", new PageQuery(null, 4L, 2, BY_TITLE));
        String fourth = cursors.next(SEARCH, third, NONE, PageResult.of(List.of("e", "f"), true), 1000);
        assertThat(cursors
                .unwrap(SEARCH, new SearchRequest("guin", new PageQuery(fourth, null, 2, BY_TITLE)), NONE,
                        FIELD, CAP))
                .isEqualTo(new SearchRequest("guin", new PageQuery(null, 6L, 2, BY_TITLE)));
    }

    @Test
    void nextFollowsRestsLinkRule() {
        SearchRequest first = new SearchRequest("guin", new PageQuery(null, null, 2, BY_TITLE));
        SearchRequest byCursor = new SearchRequest("guin", new PageQuery("raw-2", null, 2, BY_TITLE));

        // No next page, even with a cursor: no token, as REST sends no Link.
        assertThat(cursors.next(SEARCH, first, NONE, new PageResult<>(List.of("a"), "raw-1", false, null), 1000))
                .isNull();
        assertThat(cursors.next(SEARCH, first, NONE, PageResult.of(List.of("a"), false), 1000)).isNull();
        // A next cursor is wrapped.
        String cursor = cursors.next(SEARCH, first, NONE, PageResult.ofCursor(List.of("a"), "raw-1"), 1000);
        assertThat(cursors.decode(SEARCH, BY_TITLE, first, NONE, Objects.requireNonNull(cursor), FIELD))
                .isEqualTo("raw-1");
        // A cursor page that returns no cursor can't continue by offset.
        assertThat(cursors.next(SEARCH, byCursor, NONE, PageResult.of(List.of("c"), true), 1000)).isNull();
        // The next offset is 2. At max-offset 2 (inclusive) its token reads back to offset 2; at max-offset 1 the page
        // still gets a token, which fails with the offset limit.
        String atTheCap = Objects
                .requireNonNull(cursors.next(SEARCH, first, NONE, PageResult.of(List.of("a"), true), 2));
        assertThat(cursors
                .unwrap(SEARCH, new SearchRequest("guin", new PageQuery(atTheCap, null, 2, BY_TITLE)), NONE, FIELD,
                        2))
                .isEqualTo(new SearchRequest("guin", new PageQuery(null, 2L, 2, BY_TITLE)));
        String pastTheCap = Objects
                .requireNonNull(cursors.next(SEARCH, first, NONE, PageResult.of(List.of("a"), true), 1));
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors
                        .unwrap(SEARCH, new SearchRequest("guin", new PageQuery(pastTheCap, null, 2, BY_TITLE)), NONE,
                                FIELD, 1))
                .satisfies(e -> assertThat(e.violations())
                        .containsExactly(new Violation(FIELD,
                                "has reached the offset limit (1); narrow the query or use keyset paging")));
        // Without a PageQuery there is no size to add.
        assertThat(cursors.next(SEARCH, new Plain("x"), NONE, PageResult.of(List.of("a"), true), 1000)).isNull();
    }

    @Test
    void offsetTokenIsScopedLikeACursor() {
        SearchRequest first = new SearchRequest("guin", new PageQuery(null, null, 2, BY_TITLE));
        String token = Objects
                .requireNonNull(cursors.next(SEARCH, first, NONE, PageResult.of(List.of("a", "b"), true), 1000));
        List<SearchRequest> others = List
                .of(new SearchRequest("guin", new PageQuery(token, null, 2, List.of())),
                        new SearchRequest("herbert", new PageQuery(token, null, 2, BY_TITLE)),
                        new SearchRequest("guin", new PageQuery(token.substring(0, token.length() - 4), null, 2,
                                BY_TITLE)));

        for (SearchRequest other : others) {
            assertThatExceptionOfType(SynapseException.class)
                    .isThrownBy(() -> cursors.unwrap(SEARCH, other, NONE, FIELD, CAP))
                    .satisfies(e -> assertThat(e.violations())
                            .containsExactly(new Violation(FIELD, "is not valid for this request")));
        }
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors
                        .unwrap(ALL, new SearchRequest("guin", new PageQuery(token, null, 2,
                                BY_TITLE)), NONE, FIELD, CAP));
        // decode returns raw cursors only: an offset token is not one.
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors.decode(SEARCH, BY_TITLE, first, NONE, token, FIELD))
                .satisfies(e -> assertThat(e.violations())
                        .containsExactly(new Violation(FIELD, "is not valid for this request")));
    }

    @Test
    void nextPastTheCapIsAToken() {
        SearchRequest capped = new SearchRequest("guin", new PageQuery(null, 990L, 20, BY_TITLE));

        // 990 + 20 is past max-offset 1000 and the page has a next page: a token, so a client that follows tokens
        // learns why it stops.
        assertThat(cursors.next(SEARCH, capped, NONE, PageResult.of(List.of("a"), true), CAP))
                .isNotNull()
                .matches("[A-Za-z0-9_-]+");
    }

    @Test
    void capTokenFailsWithTheOffsetLimitMessage() {
        SearchRequest capped = new SearchRequest("guin", new PageQuery(null, 990L, 20, BY_TITLE));
        String token = Objects
                .requireNonNull(cursors.next(SEARCH, capped, NONE, PageResult.of(List.of("a"), true), CAP));
        SearchRequest next = new SearchRequest("guin", new PageQuery(token, null, 20, BY_TITLE));

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors.unwrap(SEARCH, next, NONE, "page_token", CAP))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                    assertThat(e.violations()).containsExactly(new Violation("page_token", OFFSET_LIMIT));
                });
        // The same for a whole PageQuery input, on REST's cursor parameter.
        String whole = Objects
                .requireNonNull(cursors
                        .next(ALL, new PageQuery(null, 990L, 20, List.of()), NONE, PageResult.of(List.of("a"), true),
                                CAP));
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors.unwrap(ALL, new PageQuery(whole, null, 20, List.of()), NONE, FIELD, CAP))
                .satisfies(e -> assertThat(e.violations()).containsExactly(new Violation(FIELD, OFFSET_LIMIT)));
    }

    @Test
    void capTokenWithAnotherQueryIsNotValid() {
        SearchRequest capped = new SearchRequest("guin", new PageQuery(null, 990L, 20, BY_TITLE));
        String token = Objects
                .requireNonNull(cursors.next(SEARCH, capped, NONE, PageResult.of(List.of("a"), true), CAP));

        // Pin (R-11): the scope is checked first: with other filters, another sort or another operation, the capped
        // token is the one cursor violation, never the offset limit, so it says nothing about the query it was issued
        // for.
        List<SearchRequest> others = List
                .of(new SearchRequest("herbert", new PageQuery(token, null, 20, BY_TITLE)),
                        new SearchRequest("guin", new PageQuery(token, null, 20, List.of())));
        for (SearchRequest other : others) {
            assertThatExceptionOfType(SynapseException.class)
                    .isThrownBy(() -> cursors.unwrap(SEARCH, other, NONE, FIELD, CAP))
                    .satisfies(e -> assertThat(e.violations())
                            .containsExactly(new Violation(FIELD, "is not valid for this request")));
        }
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors
                        .unwrap(ALL, new SearchRequest("guin", new PageQuery(token, null, 20, BY_TITLE)), NONE, FIELD,
                                CAP))
                .satisfies(e -> assertThat(e.violations())
                        .containsExactly(new Violation(FIELD, "is not valid for this request")));
    }

    @Test
    void tokenFromAHigherCapGetsTheSameMessage() {
        SearchRequest first = new SearchRequest("guin", new PageQuery(null, null, 20, BY_TITLE));
        String token = Objects
                .requireNonNull(cursors.next(SEARCH, first, NONE, PageResult.of(List.of("a"), true), CAP));
        SearchRequest next = new SearchRequest("guin", new PageQuery(token, null, 20, BY_TITLE));

        // Issued for offset 20 under a cap of 1000; read back after the cap was lowered to 10.
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> cursors.unwrap(SEARCH, next, NONE, FIELD, 10))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                    assertThat(e.violations())
                            .containsExactly(new Violation(FIELD,
                                    "has reached the offset limit (10); narrow the query or use keyset paging"));
                });
        // At the cap, inclusive, it still reads.
        assertThat(cursors.unwrap(SEARCH, next, NONE, FIELD, 20))
                .isEqualTo(new SearchRequest("guin", new PageQuery(null, 20L, 20, BY_TITLE)));
    }

    @Test
    void noTokenAtTheRealEnd() {
        // Pin (R-11): the token is empty only at the real end of the collection, past the cap too.
        SearchRequest capped = new SearchRequest("guin", new PageQuery(null, 990L, 20, BY_TITLE));

        assertThat(cursors.next(SEARCH, capped, NONE, PageResult.of(List.of("a"), false), CAP)).isNull();
        assertThat(cursors.next(SEARCH, capped, NONE, new PageResult<>(List.of("a"), "raw-1", false, null), CAP))
                .isNull();
    }

    @Test
    void cursorPageWithoutACursorStillHasNoToken() {
        // Pin (R-11): a page read by cursor can't continue by offset, whatever the cap.
        SearchRequest byCursor = new SearchRequest("guin", new PageQuery("raw-2", null, 20, BY_TITLE));

        assertThat(cursors.next(SEARCH, byCursor, NONE, PageResult.of(List.of("c"), true), CAP)).isNull();
        assertThat(cursors.next(SEARCH, byCursor, NONE, PageResult.of(List.of("c"), true), 0)).isNull();
    }

    @Test
    void nextWithinTheCapIsUnchanged() {
        // Pin (R-11): within the cap, inclusive, the token carries the next offset and reads back to it.
        for (long offset : new long[]{960, 980}) {
            SearchRequest page = new SearchRequest("guin", new PageQuery(null, offset, 20, BY_TITLE));
            String token = Objects
                    .requireNonNull(cursors.next(SEARCH, page, NONE, PageResult.of(List.of("a"), true), CAP));

            assertThat(cursors
                    .unwrap(SEARCH, new SearchRequest("guin", new PageQuery(token, null, 20, BY_TITLE)), NONE, FIELD,
                            CAP))
                    .isEqualTo(new SearchRequest("guin", new PageQuery(null, offset + 20, 20, BY_TITLE)));
        }
    }

    @Test
    void pastCapIsTheOffsetPageWhoseNextOffsetPassesTheCap() {
        PageResult<String> more = PageResult.of(List.of("a"), true);
        SearchRequest capped = new SearchRequest("guin", new PageQuery(null, 990L, 20, BY_TITLE));

        assertThat(PageCursors.pastCap(capped, more, CAP)).isTrue();
        // The first page, without an offset, under a cap below its size.
        assertThat(PageCursors.pastCap(new PageQuery(null, null, 20, List.of()), more, 19)).isTrue();
        // Within the cap, inclusive.
        assertThat(PageCursors.pastCap(new SearchRequest("guin", new PageQuery(null, 980L, 20, BY_TITLE)), more, CAP))
                .isFalse();
        // No next page, a next cursor, a request by cursor, or no PageQuery: never past the cap.
        assertThat(PageCursors.pastCap(capped, PageResult.of(List.of("a"), false), CAP)).isFalse();
        assertThat(PageCursors.pastCap(capped, PageResult.ofCursor(List.of("a"), "raw-1"), CAP)).isFalse();
        assertThat(PageCursors.pastCap(new SearchRequest("guin", new PageQuery("raw-2", null, 20, BY_TITLE)), more, 0))
                .isFalse();
        assertThat(PageCursors.pastCap(new Plain("x"), more, 0)).isFalse();
        assertThat(PageCursors.pastCap(null, more, 0)).isFalse();
    }

    @Test
    void rawCursorOver512BytesIsAnOperationBug() {
        SearchRequest first = new SearchRequest("guin", PageQuery.first(2));
        String longest = cursors.encode(SEARCH, List.of(), first, NONE, "x".repeat(512));

        assertThat(cursors.decode(SEARCH, List.of(), first, NONE, longest, FIELD)).isEqualTo("x".repeat(512));
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> cursors.encode(SEARCH, List.of(), first, NONE, "x".repeat(513)))
                .withMessage("The operation search-books returned a cursor of 513 UTF-8 bytes; the most a cursor may "
                        + "have is 512");
    }

    @Test
    void tokenOfAnotherCodecWithoutAKindIsRejected() {
        // A replacement codec that hands back what it is given, with a token issued before kinds were tagged.
        PageCursors identity = new PageCursors(new CursorCodec() {
            @Override
            public String encode(String cursor, CursorScope scope) {
                return "token";
            }

            @Override
            public String decode(String token, CursorScope scope) {
                return "untagged-raw";
            }
        }, digest);

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> identity.unwrap(ALL, new PageQuery("token", null, 2, List.of()), NONE, FIELD, CAP))
                .satisfies(e -> assertThat(e.violations())
                        .containsExactly(new Violation(FIELD, "is not valid for this request")));
    }

    @Test
    void queryIsTheInputOrItsPageQueryComponent() {
        PageQuery page = PageQuery.first(2);

        assertThat(PageCursors.query(page)).isSameAs(page);
        assertThat(PageCursors.query(new SearchRequest("guin", page))).isSameAs(page);
        assertThat(PageCursors.query(new Plain("x"))).isNull();
        assertThat(PageCursors.query("not a record")).isNull();
        assertThat(PageCursors.query(null)).isNull();
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

    private static AesGcmCursorCodec codec(@Nullable String key) {
        SecureRandom random = new SecureRandom();
        SynapsePagingProperties.Cursor cursor = new SynapsePagingProperties.Cursor(key, List.of(), null);
        return new AesGcmCursorCodec(CursorKeys.from(cursor, random), Clock.systemUTC(), null, random);
    }

    record SearchRequest(@Nullable String author, PageQuery page) {
    }

    record Plain(String value) {
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
}
