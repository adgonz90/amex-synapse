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
package io.americanexpress.synapse.api.rest.openapi.autoconfigure;

import java.util.List;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SpringDocConfigProperties.ApiDocs.OpenApiVersion;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.function.RouterFunction;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestAutoConfiguration;
import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestProperties;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.error.RestErrorStatuses;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.api.rest.internal.route.OperationRoutes;
import io.americanexpress.synapse.api.rest.openapi.internal.RouteDocumenter;
import io.americanexpress.synapse.api.rest.openapi.internal.SynapseOpenApiCustomizer;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCorrelationProperties;

/**
 * Auto-configuration of the OpenAPI documentation of REST bindings: the routes of every {@link RestBindings} bean join
 * the document springdoc serves at {@code /v3/api-docs}, as OpenAPI 3.1. springdoc's defaults are set by
 * {@link OpenApiDefaultsEnvironmentPostProcessor} and tuned with {@code springdoc.*}. Active in servlet web
 * applications with springdoc.
 */
@AutoConfiguration(after = {SynapseRestAutoConfiguration.class, SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class})
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass(OpenApiCustomizer.class)
public class SynapseRestOpenApiAutoConfiguration {

    static final String VERSION = "springdoc.api-docs.version";

    private static final String PATHS_TO_MATCH = "springdoc.paths-to-match";

    private static final String PATHS_TO_EXCLUDE = "springdoc.paths-to-exclude";

    /**
     * Adds Synapse's routes to springdoc's document. The routes are documented once here, and every type documented as
     * {@code {}} is logged at WARN; springdoc caches the document, which documents them again. Synapse's operations
     * replace springdoc's for the same path and method, and its components replace springdoc's of the same name.
     * {@code springdoc.paths-to-match} and {@code springdoc.paths-to-exclude} apply to the routes as springdoc applies
     * them to its own handlers, also while the document is off, when they are read from the environment. The routes
     * are documented in declaration order, so an operation's first declared route keeps its plain operationId.
     * Creating it fails when {@value #VERSION} is {@code openapi_3_0}: the schemas use JSON Schema 2020-12. Define a
     * bean with the same name to replace it.
     *
     * @param routes the routes bean
     * @param bindings the bindings, for the declaration order of the routes
     * @param jsonMapper the application's mapper, which names the properties
     * @param validator the application's validator, whose constraints the schemas state; without a unique one, the
     *        customizer builds the default validator factory and closes it with the context
     * @param paging the paging settings
     * @param statuses the error statuses, overrides included
     * @param correlation the correlation properties, for the correlation header
     * @param properties the REST properties, for the deadline header
     * @param environment the environment, for {@value #VERSION}, and for the path filters while the document is off
     * @param springdoc springdoc's properties, for its path filters; absent while the document is off
     * @return the customizer
     * @throws SynapseConfigurationException when {@value #VERSION} is {@code openapi_3_0}
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean(name = "synapseRestOpenApiCustomizer")
    SynapseOpenApiCustomizer synapseRestOpenApiCustomizer(@Qualifier("synapseRestRoutes") RouterFunction<?> routes,
            ObjectProvider<RestBindings> bindings, ObjectProvider<JsonMapper> jsonMapper,
            ObjectProvider<Validator> validator, PagingSettings paging,
            RestErrorStatuses statuses, SynapseCorrelationProperties correlation, SynapseRestProperties properties,
            Environment environment, ObjectProvider<SpringDocConfigProperties> springdoc) {
        if (Binder.get(environment).bind(VERSION, OpenApiVersion.class).orElse(null) == OpenApiVersion.OPENAPI_3_0) {
            throw new SynapseConfigurationException(
                    VERSION + " is openapi_3_0, but Synapse documents its REST routes in OpenAPI 3.1 (JSON Schema "
                            + "2020-12)",
                    "Remove " + VERSION + ", or set it to openapi_3_1.");
        }
        // The routes bean is ordered by specificity; the bindings in bean order are the order the routes bean compiles.
        List<RouteDefinition> resolved = RouteDocumenter
                .inDeclarationOrder(OperationRoutes.of(List.of(routes)),
                        bindings.orderedStream().flatMap(each -> each.routes().stream()).toList());
        SpringDocConfigProperties filters = springdoc.getIfAvailable();
        if (filters != null) {
            resolved = RouteDocumenter.matching(resolved, filters.getPathsToMatch(), filters.getPathsToExclude());
        } else {
            // With springdoc.api-docs.enabled=false springdoc defines no beans, its properties included. The startup
            // pass still honours its filters, so it reports no route the document would leave out.
            Binder binder = Binder.get(environment);
            resolved = RouteDocumenter
                    .matching(resolved,
                            binder.bind(PATHS_TO_MATCH, Bindable.listOf(String.class)).orElse(List.of()),
                            binder.bind(PATHS_TO_EXCLUDE, Bindable.listOf(String.class)).orElse(List.of()));
        }
        JsonMapper mapper = jsonMapper.getIfAvailable(() -> JsonMapper.builder().build());
        Validator unique = validator.getIfUnique();
        if (unique != null) {
            return new SynapseOpenApiCustomizer(resolved, mapper, unique, paging, statuses, correlation.header(),
                    properties.deadline().header());
        }
        // The customizer owns this factory and closes it with the context (it is AutoCloseable).
        return new SynapseOpenApiCustomizer(resolved, mapper, Validation.buildDefaultValidatorFactory(), paging,
                statuses,
                correlation.header(), properties.deadline().header());
    }
}
