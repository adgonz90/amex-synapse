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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RestBindingsAccess;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.core.internal.paging.InputDigest;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

import static org.assertj.core.api.Assertions.assertThat;

class CursorExtrasTest {

    @Test
    void anUnversionedRouteAddsNothing() {
        RouteDefinition route = routes(RestBindings.builder().get("/books", new SearchOperation()).build()).getFirst();

        assertThat(CursorExtras.of(route)).isEmpty();
    }

    @Test
    void aVersionedRouteAddsItsParsedVersion() {
        SearchOperation operation = new SearchOperation();
        List<RouteDefinition> routes = routes(RestBindings
                .builder()
                .get("/books", operation)
                .version("1")
                .get("/books", operation)
                .version("2")
                .post("/books/search", operation)
                .version("1.0")
                .build());

        assertThat(CursorExtras.of(routes.get(0))).isEqualTo(Map.of("@version", "1.0.0"));
        assertThat(CursorExtras.of(routes.get(1))).isEqualTo(Map.of("@version", "2.0.0"));
        // Versions that parse equal share the scope, as two routes of one operation do.
        assertThat(CursorExtras.of(routes.get(2))).isEqualTo(Map.of("@version", "1.0.0"));
    }

    @Test
    void theVersionBindsTheDigestAsBeforeTheMove() {
        InputDigest digest = new InputDigest();
        SearchOperation operation = new SearchOperation();
        List<RouteDefinition> routes = routes(RestBindings
                .builder()
                .get("/books", operation)
                .version("1")
                .get("/books", operation)
                .version("2")
                .build());
        SearchRequest input = new SearchRequest("a", PageQuery.first(10));

        String v1 = digest.of(input, CursorExtras.of(routes.get(0)));

        // The version is written by its parsed value, under a key no component name can take.
        assertThat(v1)
                .isEqualTo(sha256("{\"@version\":\"1.0.0\",\"author\":\"a\"}"))
                .isNotEqualTo(digest.of(input, Map.of()))
                .isNotEqualTo(digest.of(input, CursorExtras.of(routes.get(1))));
    }

    private static List<RouteDefinition> routes(RestBindings bindings) {
        return new BindingValidator(PagingSettings.defaults()).validate(RestBindingsAccess.routes(bindings), List.of());
    }

    private static String sha256(String text) {
        try {
            return HexFormat
                    .of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record SearchRequest(@Nullable String author, PageQuery page) {
    }

    static class SearchOperation implements QueryOperation<SearchRequest, PageResult<String>> {
        @Override
        public PageResult<String> execute(SearchRequest input) {
            return PageResult.of(List.of(), false);
        }
    }
}
