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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ReflectionUtils;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.paging.CursorCodec;
import io.americanexpress.synapse.core.paging.CursorScope;
import io.americanexpress.synapse.core.paging.InvalidCursorException;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;

/**
 * Converts between the operations' raw cursors and the opaque tokens callers see, for every API plugin. A token is
 * bound to its {@link CursorScope}: the operation name, the sort and the {@link InputDigest} of the request's filters
 * and the plugin's extra entries. The scope is the operation, not the route or method: a token issued by
 * {@code GET /books} works on {@code POST /books/search}, and over gRPC, when they call the same operation with the
 * same filters, sort and extra entries; REST passes a versioned route's version as {@code @version}, so a token issued
 * by {@code GET /v1/books} is rejected by {@code GET /v2/books}. The raw cursor never reaches the caller, and a token
 * the codec rejects is the one cursor violation, whatever the reason, on the field the caller names. The codec logs
 * the reason at DEBUG; this class logs nothing about a token. Unwrapping rebuilds the input record with its canonical
 * constructor, so a compact constructor runs twice: on the bound input, and on the input with the raw cursor.
 *
 * <p>
 * A token carries one of two kinds of payload, inside the codec's envelope: the operation's raw cursor
 * ({@code c:<raw>}), or the next offset of a page that has a next page but no cursor ({@code o:<offset>}, see
 * {@link #next}), also past {@code synapse.paging.max-offset}, where {@link #unwrap} rejects it with the offset limit.
 * The codec sees the tagged payload, at most {@value #MAX_CURSOR_BYTES} + 2 UTF-8 bytes; a token without a known kind
 * is rejected.
 */
public final class PageCursors {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.core");

    private static final String NOT_VALID = "is not valid for this request";

    /** The most UTF-8 bytes an operation's raw cursor may have. */
    static final int MAX_CURSOR_BYTES = 512;

    /** The kind of a payload that carries the operation's raw cursor. */
    private static final String CURSOR = "c:";

    /** The kind of a payload that carries the next offset of an offset page. */
    private static final String OFFSET = "o:";

    /** The digits of {@code Long.MAX_VALUE}: a longer offset can't be one. */
    private static final int MAX_OFFSET_DIGITS = 19;

    private static final String GENERATED_KEY = "synapse.paging.cursor.key is not set, so paging cursors are"
            + " encrypted with a key generated at startup: a cursor is rejected after a restart or on another"
            + " instance. Set the same base64 256-bit key on every instance (for example: openssl rand -base64 32).";

    private final CursorCodec codec;

    private final InputDigest digest;

    /** Whether the generated key has been reported, so it is reported once whatever the number of plugins. */
    private final AtomicBoolean reported = new AtomicBoolean();

    /** The canonical constructor of each input record. */
    private final Map<Class<?>, Constructor<?>> constructors = new ConcurrentHashMap<>();

    /**
     * Creates the cursors.
     *
     * @param codec wraps and unwraps the raw cursors
     * @param digest digests the request's filters
     */
    public PageCursors(CursorCodec codec, InputDigest digest) {
        this.codec = codec;
        this.digest = digest;
    }

    /**
     * Wraps a raw cursor in a token bound to {@code CursorScope(operation, sort, digest.of(input, extra))}.
     *
     * @param operation the name of the operation
     * @param sort the request's sort
     * @param input the input the operation was called with
     * @param extra the plugin's extra digest entries ({@link InputDigest#of})
     * @param rawCursor the operation's raw cursor, at most {@value #MAX_CURSOR_BYTES} UTF-8 bytes
     * @return the token
     * @throws IllegalStateException when the raw cursor is longer than {@value #MAX_CURSOR_BYTES} UTF-8 bytes (a bug
     *         in the operation; the message never repeats the cursor), or the codec returns a token that is empty or
     *         not URL-safe (a replacement codec that breaks the {@link CursorCodec#encode} contract; the message names
     *         the codec class, never the token)
     */
    public String encode(String operation, List<SortOrder> sort, @Nullable Object input, Map<String, ?> extra,
            String rawCursor) {
        int bytes = rawCursor.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_CURSOR_BYTES) {
            throw new IllegalStateException("The operation " + operation + " returned a cursor of " + bytes
                    + " UTF-8 bytes; the most a cursor may have is " + MAX_CURSOR_BYTES);
        }
        return seal(operation, sort, input, extra, CURSOR + rawCursor);
    }

    /**
     * Unwraps a cursor token bound to {@code CursorScope(operation, sort, digest.of(input, extra))}.
     *
     * @param operation the name of the operation
     * @param sort the request's sort
     * @param input the bound input
     * @param extra the plugin's extra digest entries ({@link InputDigest#of})
     * @param token the token the caller sent
     * @param field the name the caller used for the token: REST's cursor parameter, gRPC's {@code page_token}
     * @return the raw cursor
     * @throws SynapseException {@link SynapseErrors#VALIDATION_FAILED} with the violation
     *         {@code {<field>, "is not valid for this request"}} when the codec rejects the token, or the token carries
     *         an offset rather than a cursor
     */
    public String decode(String operation, List<SortOrder> sort, @Nullable Object input, Map<String, ?> extra,
            String token, String field) {
        Position position = open(operation, sort, input, extra, token, field);
        String cursor = position.cursor();
        if (cursor == null) {
            throw notValid(field);
        }
        return cursor;
    }

    /**
     * Replaces the caller's token in the bound input with what it carries, scoped by the sort of the input's
     * {@code PageQuery}: the raw cursor it wraps ({@code PageQuery(raw, null, size, sort)}), or the next offset of an
     * offset page ({@code PageQuery(null, offset, size, sort)}). An input without a token is returned as it is. An
     * offset is checked against the cap, so the token of a page past the cap ({@link #next}), one issued under a higher
     * cap, or one forged through a replacement codec that doesn't authenticate, can't reach past it. Its violation says
     * the offset limit was reached; REST's {@code offset} parameter over the cap keeps its own text.
     *
     * @param operation the name of the operation
     * @param input the bound input
     * @param extra the plugin's extra digest entries ({@link InputDigest#of})
     * @param field the name the caller used for the token
     * @param maxOffset {@code synapse.paging.max-offset} now
     * @return the input with the raw cursor or the offset: a new record built with its canonical constructor
     * @throws SynapseException {@link SynapseErrors#VALIDATION_FAILED} with the violation
     *         {@code {<field>, "is not valid for this request"}} when the codec rejects the token, or
     *         {@code {<field>, PagingViolations.offsetLimitReached(maxOffset)}} when it carries an offset over the cap
     */
    public @Nullable Object unwrap(String operation, @Nullable Object input, Map<String, ?> extra, String field,
            long maxOffset) {
        PageQuery page = query(input);
        String token = page == null ? null : page.cursor();
        if (page == null || token == null) {
            return input;
        }
        // The digest leaves the PageQuery out, so the unwrapped input has the same scope as the bound one.
        Position position = open(operation, page.sort(), input, extra, token, field);
        Long offset = position.offset();
        if (offset != null && offset > maxOffset) {
            // The token of a page at the cap, or one from a higher cap: the same text on every protocol.
            throw SynapseException
                    .ofViolations(SynapseErrors.VALIDATION_FAILED,
                            List.of(new Violation(field, PagingViolations.offsetLimitReached(maxOffset))));
        }
        PageQuery unwrapped = new PageQuery(position.cursor(), offset, page.size(), page.sort());
        return input instanceof Record record && !(input instanceof PageQuery) ? rebuild(record, unwrapped) : unwrapped;
    }

    /**
     * Wraps the operation's next raw cursor in a token bound to the request, scoped by the sort of the input's
     * {@code PageQuery} (no sort without one).
     *
     * @param operation the name of the operation
     * @param input the input the operation was called with
     * @param extra the plugin's extra digest entries ({@link InputDigest#of})
     * @param rawCursor the operation's next cursor, or {@code null}
     * @return the token, or {@code null} without a raw cursor
     * @throws IllegalStateException when the raw cursor is too long, or the codec returns a token that is empty or
     *         not URL-safe
     */
    public @Nullable String wrap(String operation, @Nullable Object input, Map<String, ?> extra,
            @Nullable String rawCursor) {
        if (rawCursor == null) {
            return null;
        }
        PageQuery page = query(input);
        return encode(operation, page == null ? List.of() : page.sort(), input, extra, rawCursor);
    }

    /**
     * Returns the token of the page after this one, by REST's rule for {@code Link: next}, for a protocol that has one
     * token field and no link (gRPC's {@code next_page_token}):
     * <ul>
     * <li>no token when the page has no next page ({@code hasNext} is false), even with a cursor;</li>
     * <li>the page's next cursor, wrapped, when it has one;</li>
     * <li>otherwise, when the request had no cursor, a token that carries the next offset, the request's offset (0
     * without one) plus its size; {@link #unwrap} turns it into {@code PageQuery(null, offset, size, sort)}. When that
     * offset passes {@code maxOffset} ({@link #pastCap}), the token is the same kind, and {@link #unwrap} rejects it
     * with {@link PagingViolations#offsetLimitReached}: a client that follows tokens learns why it stops, and an empty
     * token still means the end of the collection, as AIP-158 asks;</li>
     * <li>otherwise no token: a cursor page that returns no cursor can't continue by offset, and an input without a
     * {@code PageQuery} has no size to add.</li>
     * </ul>
     * Both kinds of token have the same scope: the operation, the sort of the input's {@code PageQuery} and the input's
     * digest.
     *
     * @param operation the name of the operation
     * @param input the input the operation was called with, with the raw cursor or offset it was unwrapped to
     * @param extra the plugin's extra digest entries ({@link InputDigest#of})
     * @param page the operation's page
     * @param maxOffset {@code synapse.paging.max-offset}
     * @return the token, or {@code null} when there is no page after this one
     * @throws IllegalStateException when the raw cursor is too long, or the codec returns a token that is empty or
     *         not URL-safe
     */
    public @Nullable String next(String operation, @Nullable Object input, Map<String, ?> extra, PageResult<?> page,
            long maxOffset) {
        if (!page.hasNext()) {
            return null;
        }
        String rawCursor = page.nextCursor();
        if (rawCursor != null) {
            return wrap(operation, input, extra, rawCursor);
        }
        PageQuery query = query(input);
        if (query == null || query.cursor() != null) {
            return null;
        }
        // Past the cap too: unwrap rejects that token with the offset limit.
        return seal(operation, query.sort(), input, extra, OFFSET + nextOffset(query));
    }

    /**
     * Returns whether the token {@link #next} returns for this page carries an offset past the cap: the page has a next
     * page, no next cursor, the request had no cursor, and its offset (0 without one) plus its size passes
     * {@code maxOffset}. REST's {@code Link: next} carries that token as the cursor parameter, as no offset
     * parameter past the cap is valid.
     *
     * @param input the input the operation was called with
     * @param page the operation's page
     * @param maxOffset {@code synapse.paging.max-offset}
     * @return whether the next page's token is past the cap
     */
    public static boolean pastCap(@Nullable Object input, PageResult<?> page, long maxOffset) {
        if (!page.hasNext() || page.nextCursor() != null) {
            return false;
        }
        PageQuery query = query(input);
        return query != null && query.cursor() == null && nextOffset(query) > maxOffset;
    }

    /** The offset of the page after an offset page: its offset, 0 without one, plus its size. */
    private static long nextOffset(PageQuery query) {
        return (query.offset() == null ? 0 : query.offset()) + query.size();
    }

    /**
     * Returns the page query of an input: the input itself, or its {@code PageQuery} component.
     *
     * @param input the input the operation was called with
     * @return the page query, or {@code null} when the input has none
     */
    public static @Nullable PageQuery query(@Nullable Object input) {
        if (input instanceof PageQuery page) {
            return page;
        }
        if (input instanceof Record record) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                if (component.getType() == PageQuery.class) {
                    return (PageQuery) value(component, record);
                }
            }
        }
        return null;
    }

    /**
     * Logs, at WARN and at most once for this instance (so once per application context, whichever plugins call it),
     * that the cursor key was generated at startup. Each plugin calls it when at least one of its routes or methods
     * returns {@code PageResult}. Nothing is logged for a configured key or another codec.
     */
    public void reportGeneratedKey() {
        if (codec instanceof AesGcmCursorCodec aes && aes.keyGenerated() && reported.compareAndSet(false, true)) {
            LOG.warn(GENERATED_KEY);
        }
    }

    /** Encodes a tagged payload and checks the token the codec returns. */
    private String seal(String operation, List<SortOrder> sort, @Nullable Object input, Map<String, ?> extra,
            String payload) {
        String token = codec.encode(payload, scope(operation, sort, input, extra));
        if (!urlSafe(token)) {
            throw new IllegalStateException("The CursorCodec " + codec.getClass().getName() + " returned a token that "
                    + "is empty or not URL-safe; CursorCodec.encode must return only A-Z, a-z, 0-9, '-' and '_' "
                    + "(base64url without padding, for example)");
        }
        return token;
    }

    /**
     * Decodes a token and reads its tagged payload. A payload without a known kind, or an offset that isn't a
     * non-negative decimal {@code long} (a token of another format, or a replacement codec's bug), is rejected like
     * any other token, without a log line: the payload may hold the raw cursor.
     */
    private Position open(String operation, List<SortOrder> sort, @Nullable Object input, Map<String, ?> extra,
            String token, String field) {
        String payload;
        try {
            payload = codec.decode(token, scope(operation, sort, input, extra));
        } catch (InvalidCursorException e) {
            // The codec has logged the reason; the caller gets one message for every reason, and no cause is kept.
            throw notValid(field);
        }
        if (payload.startsWith(CURSOR)) {
            return new Position(payload.substring(CURSOR.length()), null);
        }
        if (payload.startsWith(OFFSET)) {
            String digits = payload.substring(OFFSET.length());
            if (!digits.isEmpty() && digits.length() <= MAX_OFFSET_DIGITS
                    && digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
                try {
                    return new Position(null, Long.parseLong(digits));
                } catch (NumberFormatException e) {
                    // Nineteen digits beyond Long.MAX_VALUE: not an offset Synapse wrote.
                }
            }
        }
        throw notValid(field);
    }

    private static SynapseException notValid(String field) {
        return SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, List.of(new Violation(field, NOT_VALID)));
    }

    /**
     * What a token carries: a raw cursor or a next offset, never both.
     *
     * @param cursor the operation's raw cursor, or {@code null} for an offset token
     * @param offset the next offset, or {@code null} for a cursor token
     */
    private record Position(@Nullable String cursor, @Nullable Long offset) {
    }

    private CursorScope scope(String operation, List<SortOrder> sort, @Nullable Object input, Map<String, ?> extra) {
        return new CursorScope(operation, sort, digest.of(input, extra));
    }

    /** Whether the token is non-empty and uses only {@code A-Z}, {@code a-z}, {@code 0-9}, {@code -} and {@code _}. */
    private static boolean urlSafe(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'
                    || c == '_';
            if (!safe) {
                return false;
            }
        }
        return true;
    }

    /** The record with its {@code PageQuery} component replaced, built with its canonical constructor. */
    private Object rebuild(Record record, PageQuery page) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        @Nullable
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            args[i] = components[i].getType() == PageQuery.class ? page : value(components[i], record);
        }
        Constructor<?> constructor = constructors.computeIfAbsent(record.getClass(), PageCursors::canonical);
        try {
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof SynapseException synapse) {
                throw synapse;
            }
            if (e.getTargetException() instanceof Error error) {
                throw error;
            }
            // The record accepted these values with the token; the raw cursor is never put in a message or a log.
            throw new SynapseException(SynapseErrors.VALIDATION_FAILED);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Can't build " + record.getClass().getName(), e);
        }
    }

    private static Constructor<?> canonical(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(types);
            ReflectionUtils.makeAccessible(constructor);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(type.getName() + " has no canonical constructor", e);
        }
    }

    private static @Nullable Object value(RecordComponent component, Record record) {
        Method accessor = component.getAccessor();
        ReflectionUtils.makeAccessible(accessor);
        return ReflectionUtils.invokeMethod(accessor, record);
    }
}
