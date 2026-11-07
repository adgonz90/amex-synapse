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
package io.americanexpress.synapse.api.rest.openapi.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.error.RestErrorStatuses;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class SynapseOpenApiCustomizerTest {

    private static final PagingSettings PAGING = PagingSettings.defaults();

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static SynapseOpenApiCustomizer customizer(List<RouteDefinition> routes) {
        return new SynapseOpenApiCustomizer(routes, MAPPER, VALIDATOR, PAGING,
                new RestErrorStatuses(Map.of(), Map.of()), "X-Correlation-Id", "Request-Timeout");
    }

    @Test
    void synapseOperationsReplaceSpringdocsOnTheSamePathShape() {
        // springdoc's operations of an escape-hatch controller at /books/{bookId}, OpenAPI's same path as /books/{id}.
        Operation controllerGet = new Operation().operationId("controllerGet");
        Operation controllerPatch = new Operation()
                .operationId("patchBook")
                .addParametersItem(new Parameter().in("path").name("bookId").required(true))
                .addParametersItem(new Parameter().in("query").name("bookId"));
        PathItem controller = new PathItem()
                .get(controllerGet)
                .patch(controllerPatch)
                .summary("Books")
                .addParametersItem(new Parameter().in("path").name("bookId").required(true));
        PathItem other = new PathItem().get(new Operation().operationId("health"));
        Paths paths = new Paths();
        paths.addPathItem("/books/{bookId}", controller);
        paths.addPathItem("/status", other);
        paths.addExtension("x-paths", true);
        JsonSchema springdocBook = new JsonSchema();
        springdocBook.addType("string");
        JsonSchema kept = new JsonSchema();
        kept.addType("integer");
        OpenAPI openApi = new OpenAPI()
                .paths(paths)
                .components(new Components().addSchemas("Book", springdocBook).addSchemas("Kept", kept));

        customizer(BookRoutes.books(PAGING)).customise(openApi);

        assertThat(openApi.getPaths()).doesNotContainKey("/books/{bookId}").containsKeys("/books/{id}", "/status");
        assertThat(openApi.getPaths().getExtensions()).containsEntry("x-paths", true);
        assertThat(openApi.getPaths().get("/status")).isSameAs(other);
        PathItem books = Objects.requireNonNull(openApi.getPaths().get("/books/{id}"));
        // Synapse's GET, PUT and DELETE; the controller's PATCH, its path variable named as Synapse names it.
        assertThat(Objects.requireNonNull(books.getGet()).getOperationId()).isEqualTo("get-book");
        assertThat(books.getPut()).isNotNull();
        assertThat(books.getDelete()).isNotNull();
        assertThat(books.getPatch()).isSameAs(controllerPatch);
        assertThat(controllerPatch.getParameters())
                .extracting(parameter -> parameter.getIn() + ":" + parameter.getName())
                .containsExactly("path:id", "query:bookId");
        assertThat(books.getParameters())
                .extracting(parameter -> parameter.getIn() + ":" + parameter.getName())
                .containsExactly("path:id");
        assertThat(books.getSummary()).isEqualTo("Books");
        // Synapse's components replace springdoc's of the same name, and the others are kept.
        assertThat(openApi.getComponents().getSchemas().get("Book")).isNotSameAs(springdocBook);
        assertThat(Objects.requireNonNull(openApi.getComponents().getSchemas().get("Book")).getProperties())
                .containsKey("title");
        assertThat(openApi.getComponents().getSchemas()).containsEntry("Kept", kept).containsKey("SynapseProblem");
        assertThat(openApi.getComponents().getHeaders()).containsOnlyKeys("X-Correlation-Id");
    }

    @Test
    void anEmptyDocumentGetsEveryRoute() {
        OpenAPI openApi = new OpenAPI();

        customizer(BookRoutes.books(PAGING)).customise(openApi);

        assertThat(openApi.getPaths())
                .containsOnlyKeys("/books/{id}", "/books", "/books/stats", "/v2/books/{id}", "/authors",
                        "/books/{id}/loans", "/books/{id}/cover");
        // Each customise documents again: springdoc may change what it was given.
        OpenAPI again = new OpenAPI();
        customizer(BookRoutes.books(PAGING)).customise(again);
        assertThat(again.getPaths().get("/books")).isNotSameAs(openApi.getPaths().get("/books"));
    }

    @Test
    void undescribedTypesWarnAtCreationOnlyWithTheirRouteAndReason() {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest.openapi");
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        Level level = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            SynapseOpenApiCustomizer customizer = customizer(BookRoutes.opaque());
            customizer.customise(new OpenAPI());
            List<RouteDefinition> clash = BookRoutes.clashing();
            customizer(clash);
        } finally {
            logger.detachAppender(lines);
            logger.setLevel(level);
        }

        String opaque = BookRoutes.Opaque.class.getCanonicalName();
        String clash = BookRoutes.Clash.class.getCanonicalName();
        assertThat(lines.list)
                .filteredOn(line -> line.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("OpenAPI: no schema for " + opaque + " (GET /opaque (get-opaque)); documented as {}",
                        "OpenAPI: no schema for " + clash + " (GET /clash (get-clash)); documented as {}, because "
                                + "Jackson failed on it: java.lang.IllegalArgumentException: Conflicting getter "
                                + "definitions for property \"x\": " + BookRoutes.Clash.class.getName() + "#a() vs "
                                + BookRoutes.Clash.class.getName() + "#b()");
        assertThat(lines.list)
                .filteredOn(line -> line.getLevel() == Level.DEBUG)
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.getFormattedMessage()).isEqualTo("OpenAPI: Jackson failed on " + clash);
                    assertThat(Objects.requireNonNull(line.getThrowableProxy()).getClassName())
                            .isEqualTo(IllegalArgumentException.class.getName());
                });
    }

    @Test
    void closesOnlyTheValidatorFactoryItWasGiven() {
        // The fallback when the application has no unique Validator bean: the customizer owns the factory.
        ValidatorFactory owned = mock(ValidatorFactory.class);
        when(owned.getValidator()).thenReturn(VALIDATOR);
        SynapseOpenApiCustomizer owning = new SynapseOpenApiCustomizer(BookRoutes.books(PAGING), MAPPER, owned, PAGING,
                new RestErrorStatuses(Map.of(), Map.of()), "X-Correlation-Id", "Request-Timeout");
        OpenAPI openApi = new OpenAPI();

        owning.customise(openApi);
        verify(owned, never()).close();
        owning.close();

        verify(owned).close();
        assertThat(openApi.getPaths()).containsKey("/books/{id}");
        // Given the application's Validator, here one that is also a factory (as Spring's LocalValidatorFactoryBean
        // is), the customizer owns nothing and closes nothing.
        ValidatorFactory applications = mock(ValidatorFactory.class, withSettings().extraInterfaces(Validator.class));
        Validator asValidator = (Validator) applications;
        when(asValidator.getConstraintsForClass(any()))
                .thenAnswer(invocation -> VALIDATOR.getConstraintsForClass(invocation.getArgument(0)));
        SynapseOpenApiCustomizer borrowing = new SynapseOpenApiCustomizer(BookRoutes.books(PAGING), MAPPER,
                asValidator, PAGING, new RestErrorStatuses(Map.of(), Map.of()), "X-Correlation-Id",
                "Request-Timeout");

        borrowing.close();

        verify(asValidator, atLeastOnce()).getConstraintsForClass(any());
        verify(applications, never()).close();
    }

    @Test
    void theBudgetWarningSaysTheBudgetWasReached() {
        assertThat(SynapseOpenApiCustomizer
                .warning("a.B", "GET /b (get-b)",
                        new SchemaGenerator.Reason("the schema budget was reached (1 types, 2 schemas), so every "
                                + "type after it is documented as {} too", null)))
                .isEqualTo("OpenAPI: no schema for a.B (GET /b (get-b)); documented as {}, because the schema budget "
                        + "was reached (1 types, 2 schemas), so every type after it is documented as {} too");
    }
}
