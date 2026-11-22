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
package io.americanexpress.synapse.api.rest.internal.version;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.accept.ApiVersionStrategy;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.SemanticApiVersionParser;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.util.ServletRequestPathUtils;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RestBindingsAccess;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SynapseApiVersioningTest {

    private static final SemanticApiVersionParser PARSER = new SemanticApiVersionParser();

    private final MockEnvironment environment = new MockEnvironment();

    @Test
    void withoutVersionedRoutesVersioningStaysOff() {
        for (List<RestBindings> bindings : List.of(List.<RestBindings>of(), List.of(unversioned()))) {
            ApiVersionConfigurer untouched = mock(ApiVersionConfigurer.class);
            versioning(bindings).configureApiVersioning(untouched);
            verifyNoInteractions(untouched);

            // Spring asserts that a configurer without a resolver was not customized, then builds no strategy.
            ExposedConfigurer configurer = new ExposedConfigurer();
            versioning(bindings).configureApiVersioning(configurer);
            assertThat(configurer.strategy()).isNull();
        }
    }

    @Test
    void versionedRoutesAddAnOptionalPathVersion() {
        ExposedConfigurer configurer = new ExposedConfigurer();

        versioning(List.of(unversioned(), versioned())).configureApiVersioning(configurer);

        ApiVersionStrategy strategy = Objects.requireNonNull(configurer.strategy(), "no strategy");
        // Only the paths of versioned routes are parsed.
        assertThat(strategy.resolveVersion(request("/v2/books/1"))).isEqualTo("v2");
        assertThat(strategy.resolveVersion(request("/v1/books"))).isEqualTo("v1");
        assertThat(strategy.resolveVersion(request("/books/1"))).isNull();
        assertThat(strategy.resolveVersion(request("/v3/books/1"))).isNull();
        assertThat(strategy.resolveVersion(request("/v2/books/1/extra"))).isNull();
        // The version is optional.
        assertThat(strategy.resolveParseAndValidateVersion(request("/books/1"))).isNull();
        assertThatCode(() -> strategy.validateVersion(null, request("/books/1"))).doesNotThrowAnyException();
        assertThat(strategy.resolveParseAndValidateVersion(request("/v2/books/1"))).isEqualTo(PARSER.parseVersion("2"));
        // The declared versions are supported, and no other.
        assertThatCode(() -> strategy.validateVersion(PARSER.parseVersion("1"), request("/v1/books")))
                .doesNotThrowAnyException();
        assertThatExceptionOfType(InvalidApiVersionException.class)
                .isThrownBy(() -> strategy.validateVersion(PARSER.parseVersion("9"), request("/books/1")));
        assertThat(strategy.getDefaultVersion()).isNull();
    }

    @Test
    void pathSegmentPropertyFailsStartup() {
        for (String key : List
                .of("spring.mvc.apiversion.use.path-segment", "spring.mvc.apiversion.use.pathSegment")) {
            MockEnvironment withSegment = new MockEnvironment().withProperty(key, "1");
            SynapseApiVersioning versioning = new SynapseApiVersioning(provider(List.of(unversioned(), versioned())),
                    withSegment);

            assertThatExceptionOfType(SynapseConfigurationException.class)
                    .as(key)
                    .isThrownBy(() -> versioning.configureApiVersioning(new ExposedConfigurer()))
                    .satisfies(ex -> {
                        assertThat(ex.description())
                                .isEqualTo("spring.mvc.apiversion.use.path-segment is set while REST routes are "
                                        + "versioned (GET /v2/books/{id} (get-book-v2), GET /v1/books (list-books))");
                        assertThat(ex.action())
                                .isEqualTo("Remove spring.mvc.apiversion.use.path-segment. Synapse puts a route's "
                                        + "version in its first path segment and parses only those paths; Boot's "
                                        + "path-segment resolver would parse every path, unversioned routes "
                                        + "included.");
                    });
        }
    }

    @Test
    void pathSegmentPropertyWithoutVersionedRoutesIsLeftToBoot() {
        MockEnvironment withSegment = new MockEnvironment()
                .withProperty("spring.mvc.apiversion.use.path-segment",
                        "1");
        ApiVersionConfigurer untouched = mock(ApiVersionConfigurer.class);

        new SynapseApiVersioning(provider(List.of(unversioned())), withSegment).configureApiVersioning(untouched);

        verifyNoInteractions(untouched);
    }

    @Test
    void malformedVersionFailsWithTheBindingValidatorsMessage() {
        for (String version : List.of("2+", "v2")) {
            RestBindings bindings = RestBindings
                    .builder()
                    .get("/books/{id}", new GetBookV2Operation())
                    .version(version)
                    .build();
            SynapseConfigurationException expected = validationFailure(bindings);

            assertThatExceptionOfType(SynapseConfigurationException.class)
                    .as(version)
                    .isThrownBy(() -> versioning(List.of(bindings)).configureApiVersioning(new ExposedConfigurer()))
                    .satisfies(ex -> {
                        assertThat(ex.description())
                                .isEqualTo(expected.description())
                                .isEqualTo("REST route GET /v" + version + "/books/{id} (get-book-v2): .version(\""
                                        + version + "\") is not a version: a version is major[.minor[.patch]], such "
                                        + "as 2 or 1.2");
                        assertThat(ex.action()).isEqualTo(expected.action());
                    });
        }
    }

    @Test
    void runsBeforeBootsAdapter() {
        assertThat(versioning(List.of()).getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    private SynapseApiVersioning versioning(List<RestBindings> bindings) {
        return new SynapseApiVersioning(provider(bindings), environment);
    }

    private static ObjectProvider<RestBindings> provider(List<RestBindings> bindings) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < bindings.size(); i++) {
            factory.registerSingleton("bindings" + i, bindings.get(i));
        }
        return factory.getBeanProvider(RestBindings.class);
    }

    private static SynapseConfigurationException validationFailure(RestBindings bindings) {
        try {
            new BindingValidator(PagingSettings.defaults()).validate(RestBindingsAccess.routes(bindings), List.of());
        } catch (SynapseConfigurationException ex) {
            return ex;
        }
        throw new AssertionError("the bindings are valid");
    }

    private static RestBindings unversioned() {
        return RestBindings.builder().get("/books/{id}", new GetBookOperation()).build();
    }

    private static RestBindings versioned() {
        return RestBindings
                .builder()
                .get("/books/{id}", new GetBookV2Operation())
                .version("2")
                .get("/books", new ListBooksOperation())
                .version("1")
                .build();
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        ServletRequestPathUtils.parseAndCache(request);
        return request;
    }

    /** Exposes the strategy the configurer builds. */
    static final class ExposedConfigurer extends ApiVersionConfigurer {

        @Nullable
        ApiVersionStrategy strategy() {
            return getApiVersionStrategy();
        }
    }

    record GetBookRequest(long id) {
    }

    record Book(long id, String title) {
    }

    static final class GetBookOperation implements QueryOperation<GetBookRequest, Book> {
        @Override
        public Book execute(GetBookRequest input) {
            return new Book(input.id(), "Dune");
        }
    }

    static final class GetBookV2Operation implements QueryOperation<GetBookRequest, Book> {
        @Override
        public Book execute(GetBookRequest input) {
            return new Book(input.id(), "Dune");
        }
    }

    static final class ListBooksOperation implements QueryOperation<Void, List<Book>> {
        @Override
        public List<Book> execute(Void input) {
            return List.of();
        }
    }
}
