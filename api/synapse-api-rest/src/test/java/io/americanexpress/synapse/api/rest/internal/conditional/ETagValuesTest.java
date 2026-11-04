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
package io.americanexpress.synapse.api.rest.internal.conditional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.ETag;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ETagValuesTest {

    private final DefaultFormattingConversionService conversions = new DefaultFormattingConversionService();

    private final ETagValues etags = new ETagValues(conversions);

    @Test
    void formatsSimpleComponents() {
        RouteDefinition versioned = route(
                RestBindings.builder().get("/books/{id}", new GetVersioned()).etag(Versioned::version).build());
        assertThat(etags.of(versioned, new Versioned("b", 7))).isEqualTo(new ETag("7", false));

        Instant modified = Instant.parse("2026-11-04T10:15:30Z");
        RouteDefinition dated = route(
                RestBindings.builder().get("/books/{id}", new GetDated()).etag(Dated::modified).build());
        String text = Objects.requireNonNull(conversions.convert(modified, String.class));
        assertThat(text).isNotEmpty();
        assertThat(etags.of(dated, new Dated("b", modified))).isEqualTo(new ETag(text, false));

        RouteDefinition labeled = route(
                RestBindings.builder().get("/books/{id}", new GetLabeled()).etag(Labeled::label).build());
        assertThat(etags.of(labeled, new Labeled("b", null))).isNull();
        assertThat(etags.of(labeled, new Labeled("b", "!#~"))).isEqualTo(new ETag("!#~", false));

        // A route without .etag has no tag.
        RouteDefinition plain = route(RestBindings.builder().get("/books/{id}", new GetVersioned()).build());
        assertThat(etags.of(plain, new Versioned("b", 7))).isNull();
    }

    @Test
    void formatsWithTheConversionService() {
        DefaultFormattingConversionService custom = new DefaultFormattingConversionService();
        custom.addConverter(Long.class, String.class, version -> "v" + version);
        RouteDefinition versioned = route(
                RestBindings.builder().get("/books/{id}", new GetVersioned()).etag(Versioned::version).build());

        assertThat(new ETagValues(custom).of(versioned, new Versioned("b", 7))).isEqualTo(new ETag("v7", false));
    }

    @Test
    void rejectsCharactersOutsideEtagc() {
        RouteDefinition labeled = route(
                RestBindings.builder().get("/books/{id}", new GetLabeled()).etag(Labeled::label).build());

        for (String bad : List.of("a b", "a\"b", "a\u0001", "a\u007f", "a\tb", "a\r\nSet-Cookie: x=y", "aĀ")) {
            assertThatIllegalStateException()
                    .as(bad)
                    .isThrownBy(() -> etags.of(labeled, new Labeled("b", bad)))
                    .withMessageContaining("ETag")
                    .withMessageNotContaining(bad)
                    .withMessageNotContaining("Set-Cookie");
        }
    }

    @Test
    void rejectsAnEmptyValue() {
        RouteDefinition labeled = route(
                RestBindings.builder().get("/books/{id}", new GetLabeled()).etag(Labeled::label).build());

        assertThatIllegalStateException()
                .isThrownBy(() -> etags.of(labeled, new Labeled("b", "")))
                .withMessageContaining("ETag")
                .withMessageContaining("empty");
    }

    @Test
    void errorMessageListsEveryRejectedClass() {
        RouteDefinition labeled = route(
                RestBindings.builder().get("/books/{id}", new GetLabeled()).etag(Labeled::label).build());

        assertThatIllegalStateException()
                .isThrownBy(() -> etags.of(labeled, new Labeled("b", "a b")))
                .withMessageContaining("double quote")
                .withMessageContaining("space")
                .withMessageContaining("control character")
                .withMessageContaining("DEL")
                .withMessageContaining("U+00FF");
    }

    @Test
    void headerQuotesTheTag() {
        assertThat(ETagValues.header(new ETag("7", false))).isEqualTo("\"7\"");
        assertThat(ETagValues.header(new ETag("", false))).isEqualTo("\"\"");
    }

    private static RouteDefinition route(RestBindings bindings) {
        return new BindingValidator(PagingSettings.defaults()).validate(bindings.routes(), List.of()).getFirst();
    }

    record Id(String id) {
    }

    record Versioned(String id, long version) {
    }

    record Dated(String id, Instant modified) {
    }

    record Labeled(String id, @Nullable String label) {
    }

    static class GetVersioned implements QueryOperation<Id, Versioned> {
        @Override
        public Versioned execute(Id input) {
            return new Versioned(input.id(), 1);
        }
    }

    static class GetDated implements QueryOperation<Id, Dated> {
        @Override
        public Dated execute(Id input) {
            return new Dated(input.id(), Instant.EPOCH);
        }
    }

    static class GetLabeled implements QueryOperation<Id, Labeled> {
        @Override
        public Labeled execute(Id input) {
            return new Labeled(input.id(), null);
        }
    }
}
