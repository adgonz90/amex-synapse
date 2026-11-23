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
package io.americanexpress.synapse.api.rest.internal.route;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.ConversionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ETag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ser.std.StdSerializer;

import io.americanexpress.synapse.api.rest.internal.binding.JsonNames;
import io.americanexpress.synapse.api.rest.internal.binding.ResponseRules;
import io.americanexpress.synapse.api.rest.internal.binding.ResponseRules.OutputHeader;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.conditional.ETagValues;
import io.americanexpress.synapse.api.rest.internal.paging.PageBody;
import io.americanexpress.synapse.api.rest.internal.paging.PageLinks;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.internal.conditional.EntityTags;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * Maps an operation's output to the response status, headers and JSON body, following the route's response rules.
 *
 * <ul>
 * <li>Status: {@code .status(int)} or {@code .status(fn)} when declared; otherwise 202 with {@code .accepted(fn)},
 * 201 with {@code .created(fn)}, 204 on {@code DELETE}, and 200. A {@code null} output (a {@code void} operation) is
 * 204, or the declared {@code .status(int)}.</li>
 * <li>An empty {@code Optional} throws the route's {@code .notFound} error ({@code NOT_FOUND} by default), with the
 * route's path variable values in template order as its message arguments, or answers the {@code .emptyAs} status
 * with no body.</li>
 * <li>{@code .created(fn)} sets {@code Location}; {@code .accepted(fn)} sets {@code Location} and
 * {@code Link: <url>; rel="status"}.</li>
 * <li>Output headers are written from their components, formatted by the conversion service, and the components are
 * removed from the body unless {@code .alsoInBody()}. A {@code null} or empty {@code Optional} component writes no
 * header; a {@code List} writes one value per element. {@code .body(component)} makes that component the whole body,
 * and a {@code null} body component is 204 with no body.</li>
 * <li>A {@code PageResult} is written as a page object ({@link PageBody}): its items, the token of its next page bound
 * to the request ({@link PageCursors#next}), {@code hasNext}, and its total. The {@code PageResult} itself is never
 * written, so the operation's raw cursor never reaches the client. On {@code GET} and {@code HEAD}, {@link PageLinks}
 * adds the {@code Link: next}; past {@code synapse.paging.max-offset} it carries the body's token, which fails with
 * the offset limit when used.</li>
 * <li>The body is written by a copy of the application's mapper that refuses a {@code PageResult} anywhere, so a page
 * hidden where the startup checks can't see it (in a {@code Map<String, Object>}, say) is an
 * {@link IllegalStateException}, never a raw cursor on the wire.</li>
 * <li>A route with {@code .etag}: a 200 to {@code GET} or {@code HEAD} carries the strong {@code ETag} with
 * {@code Cache-Control: no-cache} and {@code Vary: Accept}, and is a 304 with only those three headers when
 * {@code If-None-Match} is {@code *} or lists a tag that matches by weak comparison; a malformed
 * {@code If-None-Match} is ignored. A 201 to {@code POST} carries the {@code ETag} only. A {@code null} component
 * sends none of them ({@link ETagValues}).</li>
 * <li>The body is serialized as {@code application/json} by the guarded copy of the application's
 * {@link JsonMapper} described above, which keeps every setting of the application's mapper. A 204 or 304
 * response has no body, and a {@code HEAD} request gets the headers of the {@code GET} response without the body.</li>
 * </ul>
 */
public final class ResponseWriter {

    private static final String NESTED_PAGE = "PageResult must be the operation's direct output";

    private final JsonMapper mapper;

    private final ConversionService conversionService;

    private final PageCursors cursors;

    private final PagingSettings paging;

    private final ETagValues etags;

    /** For each output type, the JSON property name of each component the mapper writes. */
    private final Map<Class<?>, Map<String, String>> jsonNames = new ConcurrentHashMap<>();

    /**
     * Creates the writer.
     *
     * @param mapper the application's mapper; the body is written by a copy of it, built once, with every setting kept
     *        and a guard that refuses to write a {@code PageResult} anywhere
     * @param conversionService formats output header values (in an application, Spring MVC's conversion service
     *        bean)
     * @param cursors wraps the next cursor of a {@code PageResult}
     * @param paging the paging settings, for the parameter names and the offset cap of the {@code Link} header
     * @param etags computes the {@code ETag} of a route with {@code .etag}
     */
    public ResponseWriter(JsonMapper mapper, ConversionService conversionService, PageCursors cursors,
            PagingSettings paging, ETagValues etags) {
        this.conversionService = conversionService;
        this.mapper = mapper
                .rebuild()
                .addModule(new SimpleModule("synapse-page-guard").addSerializer(new PageResultGuard()))
                .build();
        this.cursors = cursors;
        this.paging = paging;
        this.etags = etags;
    }

    /**
     * Writes the operation's output.
     *
     * @param route the resolved route
     * @param result the operation's output, exactly as {@code execute} returned it
     * @param request the request
     * @param input the input the operation was called with, which a {@code PageResult}'s next cursor is bound to
     * @param cursorExtras the route's entries in the cursor scope ({@code CursorExtras.of(route)}), which the handler
     *        computes once per route
     * @return the response
     * @throws SynapseException for an empty {@code Optional} without {@code .emptyAs}
     * @throws IllegalStateException when a header value computed from the output (a location, an output header or the
     *         {@code Link}) has a control character; when the {@code ETag} value is empty or has a character an
     *         entity tag can't carry; when the body holds a {@code PageResult} other than the output itself; or when
     *         the cursor codec returns a token that is empty or not URL-safe
     */
    public ServerResponse write(RouteDefinition route, @Nullable Object result, ServerRequest request,
            @Nullable Object input, Map<String, String> cursorExtras) {
        ResponseRules rules = route.response();
        Object value = result;
        if (result instanceof Optional<?> || route.outputType() == Optional.class) {
            // An operation that returns null for an Optional output means empty.
            Optional<?> present = result instanceof Optional<?> optional ? optional : Optional.empty();
            if (present.isEmpty()) {
                return empty(route, request);
            }
            value = present.get();
        }
        if (value == null) {
            Integer status = rules.status();
            return ServerResponse.status(status != null ? status : 204).build();
        }
        Object output = value;
        HttpHeaders headers = headers(route, output, result);
        ResolvedComponent bodyComponent = rules.bodyComponent();
        Object bodyValue;
        if (output instanceof PageResult<?> page) {
            // The PageResult itself is never written: its next cursor is the operation's raw cursor. The body's
            // token is the one gRPC writes to next_page_token (PageCursors.next, REST's Link: next rule): none without
            // a next page, the wrapped cursor, or the next offset of an offset page, past the cap too (a token that
            // fails when used), also on POST, which has no Link.
            String token = cursors
                    .next(route.operationName(), input, cursorExtras, page, paging.maxOffset());
            bodyValue = new PageBody(page.items(), token, page.hasNext(), page.total());
            // The Link carries the cursor token, the token past the cap as the cursor parameter (no offset parameter
            // past the cap is valid), or for an offset page within the cap offset=N.
            boolean linksToken = page.nextCursor() != null || PageCursors.pastCap(input, page, paging.maxOffset());
            addNextLink(headers, request, input, page, linksToken ? token : null);
        } else {
            bodyValue = bodyComponent != null
                    ? componentValue(bodyComponent, output)
                    : guarded(() -> body(rules, output));
        }
        // A null body component means there is nothing to send.
        int status = bodyValue == null ? 204 : status(route, output, result);
        ETag tag = status == 200 || status == 201 ? etags.of(route, output) : null;
        // The ETag of a 200 to GET or HEAD, set on the servlet response when the body is written (see below).
        @Nullable
        String readTag = null;
        if (tag != null) {
            if (status == 200 && isRead(request)) {
                if (notModified(request, tag)) {
                    return notModifiedResponse(tag);
                }
                readTag = ETagValues.header(tag);
                addCachingHeaders(headers);
            } else if (status == 201 && HttpMethod.POST.equals(request.method())) {
                headers.set(HttpHeaders.ETAG, ETagValues.header(tag));
            }
        }
        ServerResponse.BodyBuilder response = ServerResponse.status(status);
        response.headers(target -> target.addAll(headers));
        if (status == 204 || status == 304) {
            return response.build();
        }
        Object written = bodyValue;
        byte[] body = guarded(() -> mapper.writeValueAsBytes(written));
        // HEAD gets the GET response's headers, Content-Length included, without the body.
        boolean head = HttpMethod.HEAD.equals(request.method());
        @Nullable
        String etag = readTag;
        return response
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .build((servletRequest, servletResponse) -> {
                    // Spring evaluates If-None-Match itself, with its lenient parser, for a GET or HEAD response whose
                    // headers carry an ETag. This writer has decided already (strictly, a malformed header ignored),
                    // so the ETag is set here, after Spring's check.
                    if (etag != null) {
                        servletResponse.setHeader(HttpHeaders.ETAG, etag);
                    }
                    if (!head) {
                        servletResponse.getOutputStream().write(body);
                    }
                    return null;
                });
    }

    private static boolean isRead(ServerRequest request) {
        return HttpMethod.GET.equals(request.method()) || HttpMethod.HEAD.equals(request.method());
    }

    /**
     * Whether the request's {@code If-None-Match} matches the tag by weak comparison, or is {@code *}. An absent or
     * malformed header doesn't match: the response is the 200.
     */
    private static boolean notModified(ServerRequest request, ETag tag) {
        List<String> values = request.headers().header(HttpHeaders.IF_NONE_MATCH);
        if (values.isEmpty()) {
            return false;
        }
        EntityTags.Parsed parsed;
        try {
            parsed = EntityTags.parse(values);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return !(parsed instanceof EntityTags.Parsed.Tags list) || list
                .tags()
                .stream()
                .anyMatch(candidate -> new ETag(candidate.opaque(), candidate.weak()).compare(tag, false));
    }

    /**
     * The 304: the validators, and nothing that describes a body (RFC 9110 §15.4.5), so no output header,
     * {@code Content-Type} or {@code Content-Length}.
     */
    private static ServerResponse notModifiedResponse(ETag tag) {
        String etag = ETagValues.header(tag);
        return ServerResponse.status(304).headers(target -> {
            target.set(HttpHeaders.ETAG, etag);
            addCachingHeaders(target);
        }).build();
    }

    /** Sets the caching headers that make clients revalidate the {@code ETag} before reusing a stored response. */
    private static void addCachingHeaders(HttpHeaders headers) {
        headers.set(HttpHeaders.CACHE_CONTROL, CacheControl.noCache().getHeaderValue());
        headers.set(HttpHeaders.VARY, HttpHeaders.ACCEPT);
    }

    /**
     * Adds {@code Link: <target>; rel="next"} to a page on GET and HEAD. Other methods get no link, and the link
     * carries only the wrapped token or the next offset.
     */
    private void addNextLink(HttpHeaders headers, ServerRequest request, @Nullable Object input, PageResult<?> page,
            @Nullable String token) {
        PageQuery query = PageCursors.query(input);
        if (query == null || !(HttpMethod.GET.equals(request.method()) || HttpMethod.HEAD.equals(request.method()))) {
            return;
        }
        String link = PageLinks.next(request.servletRequest(), paging, query, page, token);
        if (link != null) {
            headers.add(HttpHeaders.LINK, checked(HttpHeaders.LINK, link));
        }
    }

    /**
     * Runs a serialization with the body mapper. When the guard met a {@code PageResult}, the failure is replaced by a
     * plain {@link IllegalStateException} with no cause, so neither the mapper's message nor the page's raw cursor
     * reaches a log or the client.
     */
    private static <T> T guarded(Supplier<T> serialization) {
        try {
            return serialization.get();
        } catch (RuntimeException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof NestedPageException) {
                    throw new IllegalStateException(NESTED_PAGE);
                }
            }
            throw e;
        }
    }

    /**
     * Answers an empty {@code Optional}: the {@code .emptyAs} status, or the not-found error with the route's path
     * variable values (as the request carries them, in template order) as its message arguments.
     */
    private static ServerResponse empty(RouteDefinition route, ServerRequest request) {
        ResponseRules rules = route.response();
        Integer emptyAs = rules.emptyAs();
        if (emptyAs != null) {
            return ServerResponse.status(emptyAs).build();
        }
        ErrorDetails notFound = rules.notFound();
        Map<String, String> values = request.pathVariables();
        Object[] args = route.pathVariables().stream().filter(values::containsKey).map(values::get).toArray();
        throw new SynapseException(notFound != null ? notFound : SynapseErrors.NOT_FOUND, args);
    }

    private static int status(RouteDefinition route, Object output, @Nullable Object result) {
        ResponseRules rules = route.response();
        Integer fixed = rules.status();
        if (fixed != null) {
            return fixed;
        }
        ToIntFunction<Object> computed = rules.statusFunction();
        if (computed != null) {
            return computed.applyAsInt(result != null ? result : output);
        }
        if (rules.accepted() != null) {
            return 202;
        }
        if (rules.created() != null) {
            return 201;
        }
        return HttpMethod.DELETE.equals(route.method()) ? 204 : 200;
    }

    /** The response headers: the locations, then the output headers in declaration order. */
    private HttpHeaders headers(RouteDefinition route, Object output, @Nullable Object result) {
        ResponseRules rules = route.response();
        Object raw = result != null ? result : output;
        HttpHeaders headers = new HttpHeaders();
        Function<Object, String> accepted = rules.accepted();
        Function<Object, String> created = rules.created();
        if (accepted != null) {
            String location = checked(HttpHeaders.LOCATION, accepted.apply(raw));
            headers.add(HttpHeaders.LOCATION, location);
            headers.add(HttpHeaders.LINK, "<" + location + ">; rel=\"status\"");
        } else if (created != null) {
            headers.add(HttpHeaders.LOCATION, checked(HttpHeaders.LOCATION, created.apply(raw)));
        }
        for (OutputHeader header : rules.outputHeaders()) {
            Object value = componentValue(header.component(), output);
            if (value instanceof Optional<?> optional) {
                value = optional.orElse(null);
            }
            if (value instanceof Iterable<?> values) {
                for (Object element : values) {
                    addHeader(headers, header.name(), element);
                }
            } else {
                addHeader(headers, header.name(), value);
            }
        }
        return headers;
    }

    /** Adds one header value, formatted by the conversion service; a null value adds nothing. */
    private void addHeader(HttpHeaders headers, String name, @Nullable Object value) {
        if (value == null) {
            return;
        }
        String text = conversionService.convert(value, String.class);
        if (text != null) {
            headers.add(name, checked(name, text));
        }
    }

    /** Refuses a header value with a control character, which could split the response. */
    private static String checked(String name, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) {
                throw new IllegalStateException("The " + name + " header value computed from the operation's output "
                        + "has a control character");
            }
        }
        return value;
    }

    /** The whole output, without the components written to headers only. */
    private Object body(ResponseRules rules, Object output) {
        List<String> removed = new ArrayList<>();
        for (OutputHeader header : rules.outputHeaders()) {
            if (!header.alsoInBody()) {
                removed.add(header.component().name());
            }
        }
        if (removed.isEmpty()) {
            return output;
        }
        JsonNode tree = mapper.valueToTree(output);
        if (tree instanceof ObjectNode object) {
            Map<String, String> names = jsonNames
                    .computeIfAbsent(output.getClass(),
                            type -> JsonNames.serialized(mapper, type));
            for (String component : removed) {
                String name = names.get(component);
                if (name != null) {
                    object.remove(name);
                }
            }
        }
        return tree;
    }

    private static @Nullable Object componentValue(ResolvedComponent component, Object record) {
        Method accessor = component.component().getAccessor();
        ReflectionUtils.makeAccessible(accessor);
        return ReflectionUtils.invokeMethod(accessor, record);
    }

    /**
     * Refuses to write a {@code PageResult}: one can reach the client only as the {@link PageBody} this writer builds,
     * whose cursor is wrapped. The startup checks reject the outputs that declare one elsewhere; this guards the
     * values they can't see, such as a page inside a {@code Map<String, Object>}.
     */
    private static final class PageResultGuard extends StdSerializer<PageResult<?>> {

        PageResultGuard() {
            super(PageResult.class);
        }

        @Override
        public void serialize(PageResult<?> value, JsonGenerator generator, SerializationContext context) {
            throw new NestedPageException();
        }
    }

    /** Raised by the guard; {@link #guarded} replaces it, and whatever wraps it, before it leaves the writer. */
    private static final class NestedPageException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        NestedPageException() {
            super(NESTED_PAGE);
        }
    }
}
