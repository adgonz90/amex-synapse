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
package io.americanexpress.synapse.api.rest.internal.aot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import jakarta.servlet.Filter;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.ReflectionHintsPredicates;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.test.tools.CompileWithForkedClassLoader;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.javapoet.ClassName;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestAutoConfiguration;
import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestErrorAutoConfiguration;
import io.americanexpress.synapse.api.rest.internal.edge.ManagementPaths;
import io.americanexpress.synapse.api.rest.internal.error.SynapseErrorController;
import io.americanexpress.synapse.api.rest.internal.paging.PageBody;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Runs Spring's AOT processing on a REST service with the Synapse core and REST auto-configurations and one
 * {@link RestBindings} bean, compiles the generated code and serves a request from it, as a native image or a JVM
 * with {@code -Dspring.aot.enabled=true} would.
 */
// Generated code calls package-private members, so it must be defined in the same class loader as the test classes.
@CompileWithForkedClassLoader
class RestAotProcessingTest {

    private static final ReflectionHintsPredicates REFLECTION = RuntimeHintsPredicates.reflection();

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(AopAutoConfiguration.class, ValidationAutoConfiguration.class, JacksonAutoConfiguration.class,
                            HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class,
                            ObservationAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
                            OpenTelemetryTracingAutoConfiguration.class, MicrometerTracingAutoConfiguration.class,
                            SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class,
                            SynapseOperationAutoConfiguration.class, SynapseRestErrorAutoConfiguration.class,
                            SynapseRestAutoConfiguration.class))
            .withUserConfiguration(Routes.class);

    @Test
    void generatedContextServesTheRoute() {
        runner.prepare(context -> {
            TestGenerationContext generationContext = new TestGenerationContext();
            ClassName initializerName = new ApplicationContextAotGenerator()
                    .processAheadOfTime((GenericApplicationContext) context.getSourceApplicationContext(),
                            generationContext);
            generationContext.writeGeneratedContent();

            RuntimeHints hints = generationContext.getRuntimeHints();
            // The REST plugin's own hints.
            assertThat(hints.reflection().lambdaHints())
                    .anySatisfy(lambda -> assertThat(lambda.getDeclaringClass())
                            .isEqualTo(TypeReference.of(Routes.class)));
            assertThat(REFLECTION.onType(ManagementPaths.HealthGroup.class)).accepts(hints);
            // The core's operation type hints reach the query record and the body component's type.
            assertThat(REFLECTION.onType(Page.class)).accepts(hints);
            assertThat(REFLECTION.onType(Book.class)).accepts(hints);
            assertThat(REFLECTION.onMethodInvocation(Book.class, "title")).accepts(hints);
            // Spring registers the error controller's handler method.
            assertThat(REFLECTION.onMethodInvocation(SynapseErrorController.class, "error")).accepts(hints);

            TestCompiler.forSystem().with(generationContext).compile(compiled -> {
                try (GenericWebApplicationContext aotContext = new GenericWebApplicationContext(
                        new MockServletContext())) {
                    @SuppressWarnings("unchecked")
                    ApplicationContextInitializer<GenericApplicationContext> initializer = compiled
                            .getInstance(ApplicationContextInitializer.class, initializerName.toString());
                    initializer.initialize(aotContext);
                    aotContext.refresh();

                    MockHttpServletResponse response = getBook(aotContext);

                    assertThat(response.getStatus()).isEqualTo(200);
                    assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
                    assertThat(response.getHeader("Book-Version")).isEqualTo("v1");
                    JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsByteArray());
                    assertThat(body.get("id").asString()).isEqualTo("1");
                    assertThat(body.get("title").asString()).isEqualTo("Dune");
                }
            });
        });
    }

    @Test
    void generatedContextServesPagedAndConditionalRoutes() {
        runner.prepare(context -> {
            TestGenerationContext generationContext = new TestGenerationContext();
            ClassName initializerName = new ApplicationContextAotGenerator()
                    .processAheadOfTime((GenericApplicationContext) context.getSourceApplicationContext(),
                            generationContext);
            generationContext.writeGeneratedContent();

            RuntimeHints hints = generationContext.getRuntimeHints();
            // Jackson writes the page body reflectively.
            assertThat(REFLECTION.onType(PageBody.class)).accepts(hints);
            assertThat(REFLECTION.onMethodInvocation(PageBody.class, "nextCursor")).accepts(hints);
            // The cursor and digest code reflects on the input record: the core's hints reach its accessors and its
            // canonical constructor, and the page's item type.
            assertThat(REFLECTION.onMethodInvocation(ShelfSearch.class, "author")).accepts(hints);
            assertThat(REFLECTION.onMethodInvocation(ShelfSearch.class, "page")).accepts(hints);
            assertThat(REFLECTION
                    .onConstructorInvocation(ShelfSearch.class.getDeclaredConstructor(String.class, PageQuery.class)))
                    .accepts(hints);
            assertThat(REFLECTION.onMethodInvocation(Shelved.class, "title")).accepts(hints);
            assertThat(REFLECTION.onMethodInvocation(Versioned.class, "version")).accepts(hints);

            TestCompiler.forSystem().with(generationContext).compile(compiled -> {
                try (GenericWebApplicationContext aotContext = new GenericWebApplicationContext(
                        new MockServletContext())) {
                    @SuppressWarnings("unchecked")
                    ApplicationContextInitializer<GenericApplicationContext> initializer = compiled
                            .getInstance(ApplicationContextInitializer.class, initializerName.toString());
                    initializer.initialize(aotContext);
                    aotContext.refresh();
                    MockMvc mvc = MockMvcBuilders
                            .webAppContextSetup(aotContext)
                            .addFilters(filters(aotContext))
                            .build();
                    JsonMapper json = JsonMapper.builder().build();

                    MockHttpServletResponse first = mvc
                            .perform(get("/shelf?size=1"))
                            .andReturn()
                            .getResponse();
                    assertThat(first.getStatus()).isEqualTo(200);
                    JsonNode page = json.readTree(first.getContentAsByteArray());
                    String token = page.get("nextCursor").asString();
                    assertThat(token).isNotBlank().isNotEqualTo("raw-1");
                    assertThat(page.get("items").get(0).get("title").asString()).isEqualTo("Dune");
                    assertThat(page.get("hasNext").asBoolean()).isTrue();

                    MockHttpServletResponse second = mvc
                            .perform(get("/shelf?size=1&cursor={token}", token))
                            .andReturn()
                            .getResponse();
                    assertThat(second.getStatus()).isEqualTo(200);
                    JsonNode last = json.readTree(second.getContentAsByteArray());
                    assertThat(last.get("items").get(0).get("title").asString()).isEqualTo("Emma");

                    MockHttpServletResponse versioned = mvc.perform(get("/versioned/1")).andReturn().getResponse();
                    assertThat(versioned.getStatus()).isEqualTo(200);
                    String etag = versioned.getHeader("ETag");
                    assertThat(etag).isNotBlank();
                    MockHttpServletResponse notModified = mvc
                            .perform(get("/versioned/1").header("If-None-Match", Objects.requireNonNull(etag)))
                            .andReturn()
                            .getResponse();
                    assertThat(notModified.getStatus()).isEqualTo(304);
                } catch (Exception ex) {
                    throw new AssertionError("serving the paged and conditional routes failed", ex);
                }
            });
        });
    }

    /** Sends {@code GET /books/1} through the context's filters and dispatcher. */
    private static MockHttpServletResponse getBook(GenericWebApplicationContext context) {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters(context)).build();
        try {
            return mvc.perform(get("/books/1")).andReturn().getResponse();
        } catch (Exception ex) {
            throw new AssertionError("GET /books/1 failed", ex);
        }
    }

    /** The context's servlet filters, in order, as the server would apply them. */
    private static Filter[] filters(GenericWebApplicationContext context) {
        List<FilterRegistrationBean<?>> registrations = new ArrayList<>();
        context.getBeansOfType(FilterRegistrationBean.class).values().forEach(registrations::add);
        AnnotationAwareOrderComparator.sort(registrations);
        return registrations.stream().map(FilterRegistrationBean::getFilter).toArray(Filter[]::new);
    }

    record Page(@Nullable Integer limit) {
    }

    record GetBookRequest(String id, @Nullable Page page) {
    }

    record Book(String id, String title) {
    }

    record BookView(String version, Book book) {
    }

    record ShelfSearch(@Nullable String author, PageQuery page) {
    }

    record Shelved(String title) {
    }

    record VersionedId(String id) {
    }

    record Versioned(String id, long version) {
    }

    static class ShelfOperation implements QueryOperation<ShelfSearch, PageResult<Shelved>> {

        @Override
        public PageResult<Shelved> execute(ShelfSearch input) {
            boolean first = input.page().cursor() == null;
            return PageResult.ofCursor(List.of(new Shelved(first ? "Dune" : "Emma")), first ? "raw-1" : null);
        }
    }

    static class VersionedOperation implements QueryOperation<VersionedId, Versioned> {

        @Override
        public Versioned execute(VersionedId input) {
            return new Versioned(input.id(), 3);
        }
    }

    static class GetBookOperation implements QueryOperation<GetBookRequest, BookView> {

        @Override
        public BookView execute(GetBookRequest input) {
            return new BookView("v1", new Book(input.id(), "Dune"));
        }
    }

    @Configuration
    static class Routes {

        @Bean
        GetBookOperation getBookOperation() {
            return new GetBookOperation();
        }

        @Bean
        ShelfOperation shelfOperation() {
            return new ShelfOperation();
        }

        @Bean
        VersionedOperation versionedOperation() {
            return new VersionedOperation();
        }

        @Bean
        RestBindings pagedAndConditional(ShelfOperation shelf, VersionedOperation versioned) {
            return RestBindings
                    .builder()
                    .get("/shelf", shelf)
                    .get("/versioned/{id}", versioned)
                    .etag(Versioned::version)
                    .build();
        }

        @Bean
        RestBindings restBindings(GetBookOperation getBook) {
            return RestBindings
                    .builder()
                    .get("/books/{key}", getBook)
                    .path("key", GetBookRequest::id)
                    .header("Book-Version", BookView::version)
                    .body(BookView::book)
                    .build();
        }

        @Bean
        RestConventions restConventions() {
            return RestConventions.builder().query(Page.class).build();
        }
    }
}
