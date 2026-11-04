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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.api.rest.internal.binding.RequestBinder;
import io.americanexpress.synapse.api.rest.internal.edge.RestRequestAttributes;
import io.americanexpress.synapse.api.rest.internal.edge.SizeLimitedRequest;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.CreateBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetBookOperation;
import io.americanexpress.synapse.core.error.SynapseException;

import static io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.route;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class OperationHandlerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final GetBookOperation getBook = new GetBookOperation();

    private final OperationHandler handler = new OperationHandler(
            route(RestBindings.builder().get("/books/{id}", getBook).build()),
            new RequestBinder(MAPPER, new DefaultFormattingConversionService()),
            new ResponseWriter(MAPPER, new DefaultFormattingConversionService()));

    @ParameterizedTest
    @ValueSource(strings = {"", "application/json", "*/*", "application/*", "application/json;charset=UTF-8",
            "text/html, application/json;q=0.5", "application/xml, */*;q=0.1"})
    void jsonCompatibleOrAbsentAcceptRunsTheOperation(String accept) throws Exception {
        MockHttpServletRequest request = request(accept);

        ServerResponse response = handler.handle(ServerRequest.create(request, List.of()));

        assertThat(response.statusCode().value()).isEqualTo(200);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        response.writeTo(request, servletResponse, List::of);
        assertThat(MAPPER.readTree(servletResponse.getContentAsString()).get("id").asLong()).isEqualTo(3);
        assertThat(getBook.calls.get()).isEqualTo(1);
        assertThat(handler.route().operationName()).isEqualTo("get-book");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/html, text/plain", "application/json;q=0",
            "application/problem+json", "not a media type"})
    void otherAcceptIsNotAcceptableBeforeTheOperationRuns(String accept) {
        MockHttpServletRequest request = request(accept);

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> handler.handle(ServerRequest.create(request, List.of())))
                .satisfies(e -> assertThat(e.details()).isEqualTo(RestErrors.NOT_ACCEPTABLE));
        assertThat(getBook.calls.get()).isZero();
        assertThat(RestRequestAttributes.operationName(request)).isEqualTo("get-book");
    }

    @Test
    void setsTheOperationNameBeforeBinding() {
        MockHttpServletRequest request = request("");
        request.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "x"));

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> handler.handle(ServerRequest.create(request, List.of())));
        assertThat(RestRequestAttributes.operationName(request)).isEqualTo("get-book");
        assertThat(getBook.calls.get()).isZero();
    }

    @Test
    void routeRequestSizeLimitAppliesBeforeTheBodyIsRead() throws Exception {
        CreateBookOperation createBook = new CreateBookOperation();
        RequestBinder binder = new RequestBinder(MAPPER, new DefaultFormattingConversionService());
        ResponseWriter writer = new ResponseWriter(MAPPER, new DefaultFormattingConversionService());
        OperationHandler limited = new OperationHandler(
                route(RestBindings.builder().post("/books", createBook).maxRequestSize(DataSize.ofBytes(16)).build()),
                binder, writer);
        OperationHandler unlimited = new OperationHandler(
                route(RestBindings.builder().post("/books", createBook).build()), binder, writer);

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> limited.handle(ServerRequest.create(postBook(), List.of())))
                .satisfies(e -> assertThat(e.details()).isEqualTo(RestErrors.PAYLOAD_TOO_LARGE));

        // Without a route limit, the edge's default (here 1 MB) stays.
        ServerResponse response = unlimited.handle(ServerRequest.create(postBook(), List.of()));
        assertThat(response.statusCode().value()).isEqualTo(200);
    }

    /** A JSON body of more than 16 bytes, behind the size limit the edge filter installs. */
    private static HttpServletRequest postBook() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/books");
        request.setContentType("application/json");
        request.setContent("{\"id\":7,\"title\":\"Dune Messiah\"}".getBytes(StandardCharsets.UTF_8));
        return new SizeLimitedRequest(request, DataSize.ofMegabytes(1).toBytes());
    }

    private static MockHttpServletRequest request(String accept) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/books/3");
        if (!accept.isEmpty()) {
            request.addHeader("Accept", accept);
        }
        request.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "3"));
        return request;
    }
}
