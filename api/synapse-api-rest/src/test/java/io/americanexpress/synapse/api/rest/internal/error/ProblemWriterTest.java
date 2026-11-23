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
import java.util.Map;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RestBindingsAccess;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.binding.WireNames;
import io.americanexpress.synapse.api.rest.internal.edge.RestRequestAttributes;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemWriterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final StaticMessageSource messages = new StaticMessageSource();

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/books/42");

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private ProblemWriter writer(boolean includeDetail, @Nullable String typeBaseUri) {
        SynapseErrorProperties properties = new SynapseErrorProperties(includeDetail,
                typeBaseUri == null ? null : URI.create(typeBaseUri));
        return new ProblemWriter(new RestErrorStatuses(Map.of(), Map.of()), new ErrorMessages(messages),
                new ProblemTitles(messages), properties, MAPPER, "X-Correlation-Id");
    }

    private JsonNode write(ProblemWriter writer, SynapseException error) {
        writer.write(request, response, error);
        return MAPPER.readTree(response.getContentAsByteArray());
    }

    @Test
    void aboutBlankWithStatusPhraseByDefault() {
        JsonNode body = write(writer(false, null), new SynapseException(BookErrors.BOOK_NOT_FOUND, 42));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(body.get("type").asString()).isEqualTo("about:blank");
        assertThat(body.get("title").asString()).isEqualTo("Not Found");
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.get("code").asString()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(body.get("correlationId").asString()).isNotEmpty();
        assertThat(body.has("instance")).isFalse();
        assertThat(body.has("violations")).isFalse();
    }

    @Test
    void typeFromBaseUriAndCategoryTitle() {
        JsonNode body = write(writer(false, "https://errors.example.com/"),
                new SynapseException(BookErrors.BOOK_NOT_FOUND, 42));

        assertThat(body.get("type").asString()).isEqualTo("https://errors.example.com/BOOK_NOT_FOUND");
        assertThat(body.get("title").asString()).isEqualTo("Not Found");

        MockHttpServletResponse second = new MockHttpServletResponse();
        writer(false, "https://errors.example.com")
                .write(request, second, new SynapseException(BookErrors.BOOK_NOT_FOUND, 42));
        assertThat(MAPPER.readTree(second.getContentAsByteArray()).get("type").asString())
                .isEqualTo("https://errors.example.com/BOOK_NOT_FOUND");
    }

    @Test
    void categoryTitleOverriddenByMessageSource() {
        messages.addMessage("synapse.category.NOT_FOUND", Locale.ENGLISH, "Introuvable");
        request.addHeader("Accept-Language", "en");
        JsonNode body = write(writer(false, "https://errors.example.com"),
                new SynapseException(BookErrors.BOOK_NOT_FOUND, 42));

        assertThat(body.get("title").asString()).isEqualTo("Introuvable");
    }

    @Test
    void withoutAcceptLanguageTheRootLocaleIsUsed() {
        // The mock request, like a servlet container, answers a default locale (English) without Accept-Language.
        messages.addMessage("synapse.category.NOT_FOUND", Locale.ENGLISH, "Introuvable");
        messages.addMessage("synapse.category.NOT_FOUND", Locale.ROOT, "Missing");
        messages.addMessage("BOOK_NOT_FOUND", Locale.ENGLISH, "Livre {0} introuvable");
        messages.addMessage("BOOK_NOT_FOUND", Locale.ROOT, "Book {0} is missing");

        JsonNode body = write(writer(true, "https://errors.example.com"),
                new SynapseException(BookErrors.BOOK_NOT_FOUND, 42));

        assertThat(body.get("title").asString()).isEqualTo("Missing");
        assertThat(body.get("detail").asString()).isEqualTo("Book 42 is missing");
    }

    @Test
    void detailOnlyWhenIncludeDetailIsTrue() {
        JsonNode body = write(writer(true, null), new SynapseException(BookErrors.BOOK_NOT_FOUND, 42));
        assertThat(body.get("detail").asString()).isEqualTo("Book 42 was not found");

        MockHttpServletResponse second = new MockHttpServletResponse();
        writer(false, null).write(request, second, new SynapseException(BookErrors.BOOK_NOT_FOUND, 42));
        assertThat(MAPPER.readTree(second.getContentAsByteArray()).has("detail")).isFalse();
    }

    @Test
    void anInternalCausedByViolationsShowsNoneOfThemEvenWithDetail() {
        // What the pipeline answers for a business input, a nested call or an entity at flush (as gRPC's writer).
        JsonNode body = write(writer(true, null), new SynapseException(SynapseErrors.INTERNAL, SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED,
                        List.of(new Violation("audit.internalLedgerCode", "must not be null")))));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL");
        assertThat(body.has("violations")).isFalse();
        assertThat(body.toString()).doesNotContain("internalLedgerCode", "must not be null");
    }

    record FindBookRequest(long id) {
    }

    static final class FindBookOperation implements QueryOperation<FindBookRequest, String> {
        @Override
        public String execute(FindBookRequest input) {
            return "found";
        }
    }

    @Test
    void operationViolationsUseTheRoutesWireNames() {
        RouteDefinition route = new BindingValidator(PagingSettings.defaults())
                .validate(RestBindingsAccess
                        .routes(RestBindings
                                .builder()
                                .get("/books/{bookId}", new FindBookOperation())
                                .path("bookId", FindBookRequest::id)
                                .build()),
                        List.of())
                .getFirst();
        request.setAttribute(RestRequestAttributes.WIRE_NAMES, WireNames.of(route, MAPPER, PagingSettings.defaults()));

        JsonNode body = write(writer(false, null), SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED,
                        List.of(new Violation("id", "must be positive"), new Violation("isbn", "must not be blank"))));

        assertThat(body.get("violations").get(0).get("field").asString()).isEqualTo("bookId");
        assertThat(body.get("violations").get(1).get("field").asString()).isEqualTo("isbn");
    }

    @Test
    void violationsAlwaysIncluded() {
        JsonNode body = write(writer(false, null), SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED, List.of(new Violation("title", "must not be blank"))));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body.get("violations")).hasSize(1);
        assertThat(body.get("violations").get(0).get("field").asString()).isEqualTo("title");
        assertThat(body.get("violations").get(0).get("message").asString()).isEqualTo("must not be blank");
    }

    @Test
    void correlationIdFromRequestAttributeAndEchoed() {
        request.setAttribute(RestRequestAttributes.CORRELATION_ID, "c-1");
        JsonNode body = write(writer(false, null), new SynapseException(BookErrors.BOOK_NOT_FOUND, 42));

        assertThat(body.get("correlationId").asString()).isEqualTo("c-1");
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("c-1");
    }

    @Test
    void unauthenticatedCarriesBearerChallenge() {
        write(writer(false, null), new SynapseException(SynapseErrors.UNAUTHENTICATED));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void existingChallengeIsKept() {
        response.setHeader("WWW-Authenticate", "Basic");
        write(writer(false, null), new SynapseException(SynapseErrors.UNAUTHENTICATED));

        assertThat(response.getHeaders("WWW-Authenticate")).containsExactly("Basic");
    }

    @Test
    void protocolExceptionHeadersAreSet() {
        write(writer(false, null),
                new RestProtocolException(RestErrors.METHOD_NOT_ALLOWED, Map.of("Allow", "GET, PUT")));

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getHeader("Allow")).isEqualTo("GET, PUT");
    }

    @Test
    void keptStatusIsWrittenWithItsPhrase() {
        JsonNode body = write(writer(false, null),
                new KeptStatusException(RestErrors.CLIENT_ERROR, 418, Map.of(), null, null));

        assertThat(response.getStatus()).isEqualTo(418);
        assertThat(body.get("status").asInt()).isEqualTo(418);
        assertThat(body.get("title").asString()).isEqualTo("I'm a teapot");
        assertThat(body.get("code").asString()).isEqualTo("CLIENT_ERROR");
    }

    @Test
    void configuredStatusForTheCodeWinsOverTheKeptStatus() {
        ProblemWriter writer = new ProblemWriter(new RestErrorStatuses(Map.of("CLIENT_ERROR", 400), Map.of()),
                new ErrorMessages(messages), new ProblemTitles(messages), new SynapseErrorProperties(false, null),
                MAPPER, "X-Correlation-Id");
        JsonNode body = write(writer, new KeptStatusException(RestErrors.CLIENT_ERROR, 422, Map.of(), null, null));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body.get("status").asInt()).isEqualTo(400);
    }

    @Test
    void keptHeadersAreSet() {
        write(writer(false, null),
                new KeptStatusException(SynapseErrors.UNAVAILABLE, null, Map.of("Retry-After", "5"), null, null));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    }

    @Test
    void keptReasonIsTheDetailOnlyWithIncludeDetail() {
        KeptStatusException quota = new KeptStatusException(SynapseErrors.RATE_LIMITED, null, Map.of(), "quota",
                null);

        JsonNode body = write(writer(true, null), quota);
        assertThat(body.get("detail").asString()).isEqualTo("quota");

        MockHttpServletResponse second = new MockHttpServletResponse();
        writer(false, null).write(request, second, quota);
        assertThat(MAPPER.readTree(second.getContentAsByteArray()).toString()).doesNotContain("quota");

        // Without a reason, the detail is the code's message.
        MockHttpServletResponse third = new MockHttpServletResponse();
        writer(true, null)
                .write(request, third, new KeptStatusException(RestErrors.CLIENT_ERROR, 422, Map.of(), null, null));
        assertThat(MAPPER.readTree(third.getContentAsByteArray()).get("detail").asString())
                .isEqualTo("The request was rejected");
    }

    @Test
    void writeFailureOfADisconnectedClientIsSwallowed() {
        MockHttpServletResponse failing = new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                    }

                    @Override
                    public void write(int b) throws IOException {
                        throw new IOException("Broken pipe");
                    }
                };
            }
        };

        writer(false, null).write(request, failing, new SynapseException(BookErrors.BOOK_NOT_FOUND, 42));
        assertThat(failing.getStatus()).isEqualTo(404);
    }

    @Test
    void operationOverrideChangesStatus() {
        request.setAttribute(RestRequestAttributes.OPERATION_NAME, "get-book");
        ProblemWriter writer = new ProblemWriter(
                new RestErrorStatuses(Map.of("BOOK_NOT_FOUND", 423),
                        Map.of("get-book", Map.of("BOOK_NOT_FOUND", 410))),
                new ErrorMessages(messages), new ProblemTitles(messages), new SynapseErrorProperties(false, null),
                MAPPER, "X-Correlation-Id");
        JsonNode body = write(writer, new SynapseException(BookErrors.BOOK_NOT_FOUND, 42));

        assertThat(response.getStatus()).isEqualTo(410);
        assertThat(body.get("status").asInt()).isEqualTo(410);
        assertThat(body.get("title").asString()).isEqualTo("Gone");
    }
}
