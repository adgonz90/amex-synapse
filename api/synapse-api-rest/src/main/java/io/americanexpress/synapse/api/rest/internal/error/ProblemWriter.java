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
package io.americanexpress.synapse.api.rest.internal.error;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.internal.binding.WireNames;
import io.americanexpress.synapse.api.rest.internal.edge.RestRequestAttributes;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;

/**
 * Writes a {@link SynapseException} as an {@code application/problem+json} response (RFC 9457). The body carries the
 * code, the correlation id and the validation violations, whose fields a Synapse route's operation raised are renamed
 * to the route's wire names ({@link RestRequestAttributes#WIRE_NAMES}); the detail message only when
 * {@link SynapseErrorProperties#includeDetail()} is {@code true}, which for a {@link KeptStatusException} with a reason
 * is that reason. The title and detail are resolved in the locale of the caller's {@code Accept-Language}, or in the
 * root locale when it sent none. The status is {@link RestErrorStatuses#statusFor(SynapseException, String)}'s. Stack
 * traces, exception class names and internal messages are never written.
 */
public final class ProblemWriter {

    private static final String ABOUT_BLANK = "about:blank";

    private final RestErrorStatuses statuses;

    private final ErrorMessages messages;

    private final ProblemTitles titles;

    private final SynapseErrorProperties properties;

    private final JsonMapper mapper;

    private final String correlationHeader;

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    /**
     * Creates the writer.
     *
     * @param statuses decides the HTTP status of an error
     * @param messages resolves the detail message
     * @param titles resolves the category titles
     * @param properties the error settings
     * @param mapper serializes the body
     * @param correlationHeader the response header that echoes the correlation id
     */
    public ProblemWriter(RestErrorStatuses statuses, ErrorMessages messages, ProblemTitles titles,
            SynapseErrorProperties properties, JsonMapper mapper, String correlationHeader) {
        this.statuses = statuses;
        this.messages = messages;
        this.titles = titles;
        this.properties = properties;
        this.mapper = mapper;
        this.correlationHeader = correlationHeader;
    }

    /**
     * Returns the mapper the writer serializes problems with: the application's, so Spring MVC's client errors name a
     * body's fields as it reads them.
     *
     * @return the mapper
     */
    public JsonMapper mapper() {
        return mapper;
    }

    /**
     * Writes the error as the response.
     *
     * @param request the request
     * @param response the response, which must not be committed
     * @param error the error
     */
    public void write(HttpServletRequest request, HttpServletResponse response, SynapseException error) {
        // Only a caller's Accept-Language counts: without it, the servlet API answers the server's default locale.
        Locale locale = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE) == null ? null : request.getLocale();
        int status = statusOf(request, error);
        URI baseUri = properties.typeBaseUri();
        URI type;
        String title;
        if (baseUri == null) {
            type = URI.create(ABOUT_BLANK);
            HttpStatus known = HttpStatus.resolve(status);
            title = known == null ? titles.title(error.category(), locale) : known.getReasonPhrase();
        } else {
            String base = baseUri.toString();
            type = URI.create((base.endsWith("/") ? base : base + "/") + error.code());
            title = titles.title(error.category(), locale);
        }
        String detail = properties.includeDetail() ? detail(error, locale) : null;
        String correlationId = correlationId(request);
        // An operation's violations are in core's Java names; the route renames them to what the client sent.
        WireNames wireNames = RestRequestAttributes.wireNames(request);
        List<Violation> violations = wireNames == null ? error.violations() : wireNames.rename(error.violations());
        ProblemBody body = new ProblemBody(type, title, status, detail, error.code(), correlationId, violations);

        if (error instanceof RestProtocolException protocol) {
            protocol.headers().forEach(response::setHeader);
        }
        if (status == HttpStatus.UNAUTHORIZED.value() && !response.containsHeader(HttpHeaders.WWW_AUTHENTICATE)) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        response.setHeader(correlationHeader, correlationId);
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        byte[] bytes = mapper.writeValueAsBytes(body.toMap());
        try {
            response.getOutputStream().write(bytes);
            response.flushBuffer();
        } catch (IOException e) {
            // Typically the client went away; there is nobody left to answer.
            LOG.debug("Could not write the problem response: {}", e.toString());
        }
    }

    /**
     * Returns the HTTP status the error is written with.
     *
     * @param request the request
     * @param error the error
     * @return the status
     */
    int statusOf(HttpServletRequest request, SynapseException error) {
        return statuses.statusFor(error, RestRequestAttributes.operationName(request));
    }

    /** The detail when it is included: a kept status's reason, or the code's message. */
    private String detail(SynapseException error, @Nullable Locale locale) {
        String reason = error instanceof KeptStatusException kept ? kept.reason() : null;
        return reason != null ? reason : messages.detail(error, locale);
    }

    private static String correlationId(HttpServletRequest request) {
        String fromRequest = RestRequestAttributes.correlationId(request);
        if (fromRequest != null) {
            return fromRequest;
        }
        return RequestContext.find().map(RequestContext::correlationId).orElseGet(() -> UUID.randomUUID().toString());
    }
}
