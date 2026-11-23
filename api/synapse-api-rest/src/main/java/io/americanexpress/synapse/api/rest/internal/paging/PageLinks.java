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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * Builds the {@code Link: <target>; rel="next"} header of a page. The target is relative: the request URI and its raw
 * query string with the cursor and offset parameters removed (the others keep their order and encoding), then the
 * cursor token or the next offset appended. It never depends on forwarded headers, and carries only the wrapped token,
 * never the operation's raw cursor.
 */
public final class PageLinks {

    private PageLinks() {
    }

    /**
     * Returns the next-page link.
     *
     * <p>
     * With a token, the link carries it as the cursor parameter: the wrapped next cursor, or, when the next offset
     * passes the offset cap ({@code PageCursors.pastCap}), the page's offset token, which fails with the offset limit
     * when it is used. Without one, an offset link is returned only when the request had no cursor and the next offset
     * does not exceed the offset cap.
     *
     * @param request the request
     * @param settings the paging settings: the parameter names and the offset cap
     * @param query the page query the operation was called with
     * @param result the operation's page
     * @param token the wrapped next cursor, the offset token of a page past the cap, or {@code null}
     * @return the header value {@code <target>; rel="next"}, or {@code null} when there is no next page to link to
     */
    public static @Nullable String next(HttpServletRequest request, PagingSettings settings, PageQuery query,
            PageResult<?> result, @Nullable String token) {
        if (!result.hasNext()) {
            return null;
        }
        String name;
        String value;
        if (token != null) {
            name = settings.cursor();
            value = token;
        } else if (query.cursor() == null) {
            long offset = query.offset() == null ? 0 : query.offset();
            long next = offset + query.size();
            if (next > settings.maxOffset()) {
                return null;
            }
            name = settings.offset();
            value = Long.toString(next);
        } else {
            return null;
        }
        StringBuilder target = new StringBuilder(request.getRequestURI()).append('?');
        String rawQuery = request.getQueryString();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                if (!pair.isEmpty() && !isCursorOrOffset(pair, settings)) {
                    target.append(pair).append('&');
                }
            }
        }
        // The token is base64url and the offset is digits, so neither needs encoding.
        target.append(name).append('=').append(value);
        return "<" + target + ">; rel=\"next\"";
    }

    private static boolean isCursorOrOffset(String pair, PagingSettings settings) {
        int equals = pair.indexOf('=');
        String name = decode(equals < 0 ? pair : pair.substring(0, equals));
        return name.equals(settings.cursor()) || name.equals(settings.offset());
    }

    private static String decode(String name) {
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // A malformed escape can't be one of the paging names; keep the pair as the client sent it.
            return name;
        }
    }
}
