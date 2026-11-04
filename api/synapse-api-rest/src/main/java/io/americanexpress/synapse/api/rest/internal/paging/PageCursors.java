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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ReflectionUtils;

import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.paging.CursorCodec;
import io.americanexpress.synapse.api.rest.paging.CursorScope;
import io.americanexpress.synapse.api.rest.paging.InvalidCursorException;
import io.americanexpress.synapse.core.environment.SynapseEnvironment;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.SortOrder;

/**
 * Converts between the operation's raw cursors and the opaque tokens the client sees, at the edge. A token is bound to
 * its {@link CursorScope}: the operation name, the sort and the {@link InputDigest} of the request's filters. The scope
 * is the operation, not the route: a token issued by {@code GET /books} works on {@code POST /books/search} when both
 * routes call the same operation with the same filters and sort. The raw cursor never reaches the client, and a token
 * the codec rejects is the one cursor violation, whatever the reason. The codec logs the reason at DEBUG; this class
 * logs nothing about a token. Unwrapping rebuilds the input record with its canonical constructor, so a compact
 * constructor runs twice: on the bound input, and on the input with the raw cursor.
 */
public final class PageCursors {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    private static final String NOT_VALID = "is not valid for this request";

    private static final String GENERATED_KEY = "synapse.api.rest.paging.cursor.key is not set, so paging cursors are"
            + " encrypted with a key generated at startup: a cursor fails with 400 after a restart or on another"
            + " instance. Set the same base64 256-bit key on every instance (for example: openssl rand -base64 32).";

    private final CursorCodec codec;

    private final InputDigest digest;

    private final PagingSettings settings;

    /** The canonical constructor of each input record. */
    private final Map<Class<?>, Constructor<?>> constructors = new ConcurrentHashMap<>();

    /**
     * Creates the cursors.
     *
     * @param codec wraps and unwraps the raw cursors
     * @param digest digests the request's filters
     * @param settings the paging settings, for the cursor parameter name
     */
    public PageCursors(CursorCodec codec, InputDigest digest, PagingSettings settings) {
        this.codec = codec;
        this.digest = digest;
        this.settings = settings;
    }

    /**
     * Replaces the client's token in the bound input with the raw cursor it wraps. An input without a cursor is
     * returned as it is.
     *
     * @param route the resolved route
     * @param input the bound input
     * @return the input with the raw cursor: a new record built with its canonical constructor
     * @throws SynapseException {@link SynapseErrors#VALIDATION_FAILED} with the violation
     *         {@code {<cursor>, "is not valid for this request"}} when the codec rejects the token
     */
    public @Nullable Object unwrap(RouteDefinition route, @Nullable Object input) {
        PageQuery page = page(input);
        String token = page == null ? null : page.cursor();
        if (page == null || token == null) {
            return input;
        }
        String raw;
        try {
            raw = codec.decode(token, scope(route, input, page));
        } catch (InvalidCursorException e) {
            // The codec has logged the reason; the client gets one message for every reason, and no cause is kept.
            throw SynapseException
                    .ofViolations(SynapseErrors.VALIDATION_FAILED,
                            List.of(new Violation(settings.cursor(), NOT_VALID)));
        }
        PageQuery unwrapped = new PageQuery(raw, null, page.size(), page.sort());
        return input instanceof Record record && !(input instanceof PageQuery) ? rebuild(record, unwrapped) : unwrapped;
    }

    /**
     * Returns the page query of an input: the input itself, or its {@code PageQuery} component.
     *
     * @param input the input the operation was called with
     * @return the page query, or {@code null} when the input has none
     */
    public @Nullable PageQuery query(@Nullable Object input) {
        return page(input);
    }

    /**
     * Returns the paging settings these cursors use.
     *
     * @return the settings
     */
    public PagingSettings settings() {
        return settings;
    }

    /**
     * Wraps the operation's raw cursor in a token bound to the request.
     *
     * @param route the resolved route
     * @param input the input the operation was called with
     * @param rawCursor the operation's next cursor, or {@code null}
     * @return the token, or {@code null} without a raw cursor
     * @throws IllegalStateException when the codec returns a token that is empty or not URL-safe (a replacement codec
     *         that breaks the {@link CursorCodec#encode} contract); the message names the codec, never the token
     */
    public @Nullable String wrap(RouteDefinition route, @Nullable Object input, @Nullable String rawCursor) {
        if (rawCursor == null) {
            return null;
        }
        String token = codec.encode(rawCursor, scope(route, input, page(input)));
        if (!urlSafe(token)) {
            throw new IllegalStateException("The CursorCodec " + codec.getClass().getName() + " returned a token that "
                    + "is empty or not URL-safe; CursorCodec.encode must return only A-Z, a-z, 0-9, '-' and '_' "
                    + "(base64url without padding, for example)");
        }
        return token;
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

    /**
     * Logs, once, that the cursor key was generated at startup, when at least one route returns {@code PageResult}:
     * at WARN in production and at INFO otherwise. Nothing is logged for a configured key or another codec.
     *
     * @param routes the resolved routes
     * @param environment tells whether this is production
     */
    public void reportGeneratedKey(List<RouteDefinition> routes, SynapseEnvironment environment) {
        if (!(codec instanceof AesGcmCursorCodec aes) || !aes.keyGenerated()
                || routes.stream().noneMatch(RouteDefinition::pagedOutput)) {
            return;
        }
        if (environment.isProduction()) {
            LOG.warn(GENERATED_KEY);
        } else {
            LOG.info(GENERATED_KEY);
        }
    }

    private CursorScope scope(RouteDefinition route, @Nullable Object input, @Nullable PageQuery page) {
        List<SortOrder> sort = page == null ? List.of() : page.sort();
        return new CursorScope(route.operationName(), sort, digest.of(route, input));
    }

    /** The input's {@code PageQuery}: the input itself, or its {@code PageQuery} component. */
    private static @Nullable PageQuery page(@Nullable Object input) {
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
