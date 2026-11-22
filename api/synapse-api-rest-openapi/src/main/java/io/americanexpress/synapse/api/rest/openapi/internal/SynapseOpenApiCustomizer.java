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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.customizers.OpenApiCustomizer;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.error.RestErrorStatuses;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;

/**
 * Adds the REST routes to the OpenAPI document springdoc serves.
 *
 * <p>
 * The routes are documented once when the customizer is created, and every type documented as {@code {}} is logged at
 * WARN, once, with the first route that uses it and, for a limit or a Jackson failure, why (the failure's stack trace
 * at DEBUG). Each {@link #customise(OpenAPI)} documents them again, without logging, and merges:
 * <ul>
 * <li>a Synapse operation replaces springdoc's for the same path and method. Two templates are the same path when they
 * differ only in variable names ({@code /books/{id}} and {@code /books/{bookId}}), as OpenAPI compares them: the path
 * keeps Synapse's template, and springdoc's other operations on it are kept with their path variables renamed;</li>
 * <li>springdoc's other paths are kept;</li>
 * <li>Synapse's components replace springdoc's of the same name.</li>
 * </ul>
 *
 * <p>
 * Every documentation pass has its own schema generator, on a private copy of the application's mapper: a Jackson
 * lookup the generator stops (a stack overflow on a type Jackson can't resolve) never leaves the application's mapper
 * in an unknown state.
 *
 * <p>
 * When it is given a {@link ValidatorFactory} (the fallback without a unique {@code Validator} bean), it owns the
 * factory and closes it in {@link #close()}, which Spring calls when the context closes.
 */
public final class SynapseOpenApiCustomizer implements OpenApiCustomizer, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest.openapi");

    /** A template variable, with an optional catch-all marker and pattern: group 1 is the name. */
    private static final Pattern VARIABLE = Pattern.compile("\\{\\*?([^}:]*)(?::[^}]*)?}");

    private final List<RouteDefinition> routes;

    private final JsonMapper mapper;

    private final Validator validator;

    private final PagingSettings paging;

    private final RestErrorStatuses statuses;

    private final String correlationHeader;

    private final String deadlineHeader;

    /** The factory this customizer built its validator from and closes; {@code null} for the application's. */
    private final @Nullable ValidatorFactory ownedValidators;

    /**
     * Creates the customizer: documents the routes and logs the types documented as {@code {}}.
     *
     * @param routes the resolved routes, in declaration order
     * @param mapper the application's mapper, which names the properties and the enum values
     * @param validator the application's validator, whose constraint metadata becomes keywords
     * @param paging the paging settings
     * @param statuses the error statuses, overrides included
     * @param correlationHeader the correlation header
     * @param deadlineHeader the deadline header
     */
    public SynapseOpenApiCustomizer(List<RouteDefinition> routes, JsonMapper mapper, Validator validator,
            PagingSettings paging, RestErrorStatuses statuses, String correlationHeader, String deadlineHeader) {
        this(routes, mapper, validator, null, paging, statuses, correlationHeader, deadlineHeader);
    }

    /**
     * Creates the customizer with a validator factory it owns: it uses the factory's validator, and {@link #close()}
     * closes the factory.
     *
     * @param routes the resolved routes, in declaration order
     * @param mapper the application's mapper, which names the properties and the enum values
     * @param validators the validator factory, whose validator's constraint metadata becomes keywords
     * @param paging the paging settings
     * @param statuses the error statuses, overrides included
     * @param correlationHeader the correlation header
     * @param deadlineHeader the deadline header
     */
    public SynapseOpenApiCustomizer(List<RouteDefinition> routes, JsonMapper mapper, ValidatorFactory validators,
            PagingSettings paging, RestErrorStatuses statuses, String correlationHeader, String deadlineHeader) {
        this(routes, mapper, validators.getValidator(), validators, paging, statuses, correlationHeader,
                deadlineHeader);
    }

    private SynapseOpenApiCustomizer(List<RouteDefinition> routes, JsonMapper mapper, Validator validator,
            @Nullable ValidatorFactory ownedValidators, PagingSettings paging, RestErrorStatuses statuses,
            String correlationHeader, String deadlineHeader) {
        this.ownedValidators = ownedValidators;
        this.routes = List.copyOf(routes);
        this.mapper = mapper;
        this.validator = validator;
        this.paging = paging;
        this.statuses = statuses;
        this.correlationHeader = correlationHeader;
        this.deadlineHeader = deadlineHeader;
        SchemaGenerator schemas = generator();
        RouteDocumenter.Fragment fragment = documenter(schemas).document(this.routes);
        fragment.undescribed().forEach((type, route) -> {
            SchemaGenerator.Reason reason = schemas.reasons().get(type);
            LOG.warn("{}", warning(type, route, reason));
            Throwable cause = reason == null ? null : reason.cause();
            if (cause != null) {
                LOG.debug("OpenAPI: Jackson failed on {}", type, cause);
            }
        });
    }

    /**
     * The startup warning for a type documented as {@code {}}.
     *
     * @param type the type
     * @param route the first route that uses it, as {@code METHOD path (operation)}
     * @param reason why, for a limit or a Jackson failure
     * @return the warning
     */
    static String warning(String type, String route, SchemaGenerator.@Nullable Reason reason) {
        String warning = "OpenAPI: no schema for " + type + " (" + route + "); documented as {}";
        return reason == null ? warning : warning + ", because " + reason.text();
    }

    /** Closes the validator factory this customizer owns, if any; the application's validator is left alone. */
    @Override
    public void close() {
        if (ownedValidators != null) {
            ownedValidators.close();
        }
    }

    /** A generator on a private copy of the mapper. */
    private SchemaGenerator generator() {
        return new SchemaGenerator(mapper.rebuild().build(), validator);
    }

    private RouteDocumenter documenter(SchemaGenerator schemas) {
        return new RouteDocumenter(schemas, paging, statuses, correlationHeader, deadlineHeader);
    }

    @Override
    public void customise(OpenAPI openApi) {
        RouteDocumenter.Fragment fragment = documenter(generator()).document(routes);
        Paths springdoc = openApi.getPaths();
        openApi.setPaths(merge(springdoc != null ? springdoc : new Paths(), fragment.paths()));
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }
        fragment.schemas().forEach(components::addSchemas);
        fragment.headers().forEach(components::addHeaders);
    }

    /** Synapse's paths, each with springdoc's other operations on the same path, then springdoc's other paths. */
    private static Paths merge(Paths springdoc, Map<String, PathItem> synapse) {
        Map<String, String> synapseByShape = new HashMap<>();
        synapse.keySet().forEach(path -> synapseByShape.put(shape(path), path));
        Map<String, PathItem> merged = new LinkedHashMap<>();
        synapse.keySet().forEach(path -> merged.put(path, new PathItem()));
        springdoc.forEach((path, item) -> {
            String ours = synapseByShape.get(shape(path));
            if (ours == null) {
                merged.put(path, item);
            } else {
                keep(Objects.requireNonNull(merged.get(ours)), item, renames(path, ours));
            }
        });
        // Last, so that a Synapse operation replaces springdoc's for the same method.
        synapse
                .forEach((path,
                        item) -> item.readOperationsMap().forEach(Objects.requireNonNull(merged.get(path))::operation));
        Paths paths = new Paths();
        merged.forEach(paths::addPathItem);
        paths.setExtensions(springdoc.getExtensions());
        return paths;
    }

    /** Adds springdoc's operations and path-level settings to the item, with their path variables renamed. */
    private static void keep(PathItem item, PathItem theirs, Map<String, String> renames) {
        theirs.readOperationsMap().forEach((method, operation) -> {
            rename(operation.getParameters(), renames);
            item.operation(method, operation);
        });
        List<Parameter> parameters = theirs.getParameters();
        if (parameters != null) {
            rename(parameters, renames);
            parameters.forEach(item::addParametersItem);
        }
        if (item.getSummary() == null) {
            item.setSummary(theirs.getSummary());
        }
        if (item.getDescription() == null) {
            item.setDescription(theirs.getDescription());
        }
        if (item.getServers() == null) {
            item.setServers(theirs.getServers());
        }
    }

    private static void rename(@Nullable List<Parameter> parameters, Map<String, String> renames) {
        if (parameters == null) {
            return;
        }
        for (Parameter parameter : parameters) {
            String renamed = "path".equals(parameter.getIn()) ? renames.get(parameter.getName()) : null;
            if (renamed != null) {
                parameter.setName(renamed);
            }
        }
    }

    /** springdoc's path variables renamed to Synapse's, by position. */
    private static Map<String, String> renames(String theirs, String ours) {
        Map<String, String> renames = new HashMap<>();
        Matcher from = VARIABLE.matcher(theirs);
        Matcher to = VARIABLE.matcher(ours);
        while (from.find() && to.find()) {
            renames.put(from.group(1), to.group(1));
        }
        return renames;
    }

    /** The template with its variables blanked: the templates OpenAPI treats as one path. */
    private static String shape(String path) {
        return VARIABLE.matcher(path).replaceAll("{}");
    }
}
