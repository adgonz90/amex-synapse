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

import java.util.List;
import java.util.Map;
import jakarta.servlet.DispatcherType;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.api.rest.internal.binding.RestBindingsAccess;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.error.RestProtocolException;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.CreateBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.DeleteBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.ListBooksOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class MethodNotAllowedHandlerTest {

    @Test
    void listsDeclaredMethodsInAllow() {
        MethodNotAllowedHandler handler = new MethodNotAllowedHandler(RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .get("/books/{id}", new GetBookOperation())
                        .put("/books/{id}", new CreateBookOperation())
                        .build()));

        assertThat(allow(handler, "DELETE", "/books/1")).isEqualTo("GET, HEAD, PUT, OPTIONS");
    }

    @Test
    void allowFollowsTheFixedMethodOrderAcrossMatchingTemplates() {
        List<RouteDefinition> routes = RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .delete("/books/{id}", new DeleteBookOperation())
                        .put("/books/{id}", new CreateBookOperation())
                        .post("/books/{id}", new CreateBookOperation())
                        .head("/books/{id}", new GetBookOperation())
                        .get("/books/latest", new GetBookOperation())
                        .get("/books", new ListBooksOperation())
                        .build());
        MethodNotAllowedHandler handler = new MethodNotAllowedHandler(routes);

        assertThat(allow(handler, "PATCH", "/books/latest")).isEqualTo("GET, HEAD, POST, PUT, DELETE, OPTIONS");
        assertThat(allow(handler, "PATCH", "/books/7")).isEqualTo("HEAD, POST, PUT, DELETE, OPTIONS");
        assertThat(allow(handler, "DELETE", "/books")).isEqualTo("GET, HEAD, OPTIONS");
    }

    @Test
    void matchesOnlyPathsOfSynapseTemplates() {
        MethodNotAllowedHandler handler = new MethodNotAllowedHandler(RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .get("/books/{id}", new GetBookOperation())
                        .get("/files/{*path}", new GetBookOperation())
                        .build()));

        assertThat(handler.test(request("DELETE", "/books/1"))).isTrue();
        assertThat(handler.test(request("DELETE", "/files/a/b"))).isTrue();
        assertThat(handler.test(request("GET", "/nope"))).isFalse();
        assertThat(handler.test(request("DELETE", "/books/1/extra"))).isFalse();
        assertThat(handler.test(request("DELETE", "/books"))).isFalse();
        assertThat(handler.test(request("GET", "/error"))).isFalse();
    }

    @Test
    void versionedRoutesUseTheirServedPaths() {
        MethodNotAllowedHandler handler = new MethodNotAllowedHandler(RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .get("/books/{id}", new GetBookOperation())
                        .version("2")
                        .build()));

        assertThat(allow(handler, "DELETE", "/v2/books/1")).isEqualTo("GET, HEAD, OPTIONS");
        assertThat(handler.test(request("DELETE", "/books/1"))).isFalse();
        assertThat(handler.test(request("DELETE", "/v1/books/1"))).isFalse();
    }

    @Test
    void neverMatchesTheErrorDispatch() {
        MethodNotAllowedHandler handler = new MethodNotAllowedHandler(
                RestBindingsAccess.routes(RestBindings.builder().get("/{id}", new GetBookOperation()).build()));

        assertThat(handler.test(request("DELETE", "/error"))).isTrue();
        for (String method : List.of("GET", "DELETE", "OPTIONS")) {
            MockHttpServletRequest errorDispatch = new MockHttpServletRequest(method, "/error");
            errorDispatch.setDispatcherType(DispatcherType.ERROR);
            assertThat(handler.test(ServerRequest.create(errorDispatch, List.of()))).as(method).isFalse();
        }
    }

    private static String allow(MethodNotAllowedHandler handler, String method, String path) {
        ServerRequest request = request(method, path);
        assertThat(handler.test(request)).isTrue();
        try {
            handler.handle(request);
        } catch (RestProtocolException failure) {
            assertThat(failure.details()).isEqualTo(RestErrors.METHOD_NOT_ALLOWED);
            assertThat(failure.headers()).containsOnlyKeys("Allow");
            return String.valueOf(failure.headers().get("Allow"));
        }
        throw new AssertionError("no 405 for " + method + " " + path);
    }

    private static ServerRequest request(String method, String path) {
        return ServerRequest.create(new MockHttpServletRequest(method, path), List.of());
    }

    @Test
    void optionsAnswersAllowWithoutABody() throws Exception {
        MethodNotAllowedHandler handler = new MethodNotAllowedHandler(RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .get("/books/{id}", new GetBookOperation())
                        .put("/books/{id}", new CreateBookOperation())
                        .delete("/books/{id}", new DeleteBookOperation())
                        .build()));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("OPTIONS", "/books/1");

        ServerResponse response = handler.handle(ServerRequest.create(servletRequest, List.of()));

        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        response.writeTo(servletRequest, servletResponse, List::of);
        assertThat(servletResponse.getStatus()).isEqualTo(200);
        assertThat(servletResponse.getHeader("Allow")).isEqualTo("GET, HEAD, PUT, DELETE, OPTIONS");
        assertThat(servletResponse.getContentAsString()).isEmpty();
    }

    @Test
    void rejectsWithoutCallingAnything() {
        MethodNotAllowedHandler handler = new MethodNotAllowedHandler(
                RestBindingsAccess.routes(RestBindings.builder().get("/books/{id}", new GetBookOperation()).build()));

        assertThatExceptionOfType(RestProtocolException.class)
                .isThrownBy(() -> handler.handle(request("POST", "/books/2")))
                .satisfies(e -> assertThat(e.headers()).isEqualTo(Map.of("Allow", "GET, HEAD, OPTIONS")));
    }
}
