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
package io.americanexpress.synapse.api.rest.autoconfigure;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcObservationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RequestBinder;
import io.americanexpress.synapse.api.rest.internal.conditional.ETagValues;
import io.americanexpress.synapse.api.rest.internal.edge.ConcurrencyLimitFilter;
import io.americanexpress.synapse.api.rest.internal.edge.DeadlinePolicy;
import io.americanexpress.synapse.api.rest.internal.edge.InboundPropagationFilter;
import io.americanexpress.synapse.api.rest.internal.edge.ManagementPaths;
import io.americanexpress.synapse.api.rest.internal.edge.RequestSizeLimitFilter;
import io.americanexpress.synapse.api.rest.internal.edge.SynapseRestContextFilter;
import io.americanexpress.synapse.api.rest.internal.error.ClientErrorFilter;
import io.americanexpress.synapse.api.rest.internal.error.ProblemWriter;
import io.americanexpress.synapse.api.rest.internal.paging.AesGcmCursorCodec;
import io.americanexpress.synapse.api.rest.internal.paging.CursorKeys;
import io.americanexpress.synapse.api.rest.internal.paging.InputDigest;
import io.americanexpress.synapse.api.rest.internal.paging.PageCursors;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.api.rest.internal.route.ExposureInventory;
import io.americanexpress.synapse.api.rest.internal.route.MethodNotAllowedMapping;
import io.americanexpress.synapse.api.rest.internal.route.ResponseWriter;
import io.americanexpress.synapse.api.rest.internal.route.RouteCompiler;
import io.americanexpress.synapse.api.rest.paging.CursorCodec;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextProperties;
import io.americanexpress.synapse.core.autoconfigure.SynapseCorrelationProperties;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.internal.context.ContextBinder;

/**
 * Auto-configuration for the REST API plugin: its properties, the edge filters, and the routes compiled from the
 * {@link RestBindings} beans (with the beans that build them, present only when there are bindings). Active in servlet
 * web applications only.
 */
@AutoConfiguration(after = {WebMvcAutoConfiguration.class, WebMvcObservationAutoConfiguration.class,
        SynapseContextAutoConfiguration.class, SynapseOperationAutoConfiguration.class})
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass(RouterFunction.class)
@EnableConfigurationProperties({SynapseRestProperties.class, SynapseContextProperties.class,
        SynapseCorrelationProperties.class})
public class SynapseRestAutoConfiguration {

    private static final String REMOTE_FIELDS = "management.tracing.baggage.remote-fields";

    /**
     * The first edge filter: it hides or strips inbound propagation headers before Boot's server observation filter
     * ({@code Ordered.HIGHEST_PRECEDENCE + 1}) extracts the caller's trace and baggage. It handles REQUEST dispatches
     * only. Define a bean with the same name to replace it.
     *
     * @param properties the REST properties, for the context attribute names
     * @param context the context properties, for {@code synapse.context.inbound.trusted}
     * @param environment the environment, to read {@value #REMOTE_FIELDS} (empty when unset)
     * @return the filter registration
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseInboundPropagationFilter")
    FilterRegistrationBean<InboundPropagationFilter> synapseInboundPropagationFilter(SynapseRestProperties properties,
            SynapseContextProperties context, Environment environment) {
        Set<String> synapseFields = new LinkedHashSet<>();
        synapseFields.add(ContextBinder.CORRELATION_BAGGAGE_FIELD);
        synapseFields.addAll(properties.context().headers().keySet());
        Set<String> remoteFields = new LinkedHashSet<>(
                Binder.get(environment).bind(REMOTE_FIELDS, Bindable.listOf(String.class)).orElse(List.of()));
        FilterRegistrationBean<InboundPropagationFilter> registration = new FilterRegistrationBean<>(
                new InboundPropagationFilter(context.inbound().trusted(), synapseFields, remoteFields));
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * The policy that turns the caller's {@code Request-Timeout} into the request deadline.
     *
     * @param properties the REST properties, for {@code synapse.api.rest.deadline}
     * @param clock the application clock
     * @return the policy
     */
    @Bean
    @ConditionalOnMissingBean
    DeadlinePolicy deadlinePolicy(SynapseRestProperties properties, Clock clock) {
        SynapseRestProperties.Deadline deadline = properties.deadline();
        return new DeadlinePolicy(deadline.defaultTimeout(), deadline.max(), clock);
    }

    /**
     * The edge filter that opens the Synapse request context (correlation id, attributes, locale and deadline). It
     * runs inside the span of Boot's server observation filter ({@code Ordered.HIGHEST_PRECEDENCE + 1}), so the
     * context's baggage reaches the logs and context-propagating threads, and closes the context on the request thread
     * when the chain returns or throws. It handles REQUEST dispatches only. Define a bean with the same name to replace
     * it.
     *
     * @param binder the binder that opens the context
     * @param correlation the correlation properties, for the correlation header
     * @param properties the REST properties, for the attribute and deadline headers
     * @param deadlines the deadline policy
     * @return the filter registration
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseRestContextFilter")
    FilterRegistrationBean<SynapseRestContextFilter> synapseRestContextFilter(ContextBinder binder,
            SynapseCorrelationProperties correlation, SynapseRestProperties properties, DeadlinePolicy deadlines) {
        FilterRegistrationBean<SynapseRestContextFilter> registration = new FilterRegistrationBean<>(
                new SynapseRestContextFilter(binder, correlation.header(), properties.context().headers(),
                        properties.deadline().header(), deadlines));
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    /**
     * The edge filter that caps the requests handled at the same time ({@code synapse.api.rest.max-concurrent-requests})
     * and answers the others 503 with {@code Retry-After: 1}. It runs inside the context filter, so the 503 carries the
     * correlation id. Requests to Actuator on the application's port are never counted; their paths come from Boot's
     * {@code management.*} properties ({@link ManagementPaths}). It handles REQUEST dispatches only. Define a bean
     * with the same name to replace it.
     *
     * @param properties the REST properties, for the limit
     * @param environment the environment, for the management paths
     * @param resourceLoader the application's resource loader, whose class loader tells whether Actuator is present
     * @param writer writes the 503
     * @return the filter registration
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseConcurrencyLimitFilter")
    FilterRegistrationBean<ConcurrencyLimitFilter> synapseConcurrencyLimitFilter(SynapseRestProperties properties,
            Environment environment, ResourceLoader resourceLoader, ProblemWriter writer) {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(properties.maxConcurrentRequests(),
                ManagementPaths.excludedPrefixes(environment, resourceLoader.getClassLoader()), writer);
        FilterRegistrationBean<ConcurrencyLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(filter.getOrder());
        return registration;
    }

    /**
     * The edge filter that limits the request body to {@code synapse.api.rest.max-request-size}, or to a route's own
     * {@code .maxRequestSize(...)}. A body over the limit is answered 413 without reading the rest of it. It runs after
     * the concurrency limit and handles REQUEST dispatches only. Define a bean with the same name to replace it.
     *
     * @param properties the REST properties, for the default limit
     * @return the filter registration
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseRequestSizeLimitFilter")
    FilterRegistrationBean<RequestSizeLimitFilter> synapseRequestSizeLimitFilter(SynapseRestProperties properties) {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(properties.maxRequestSize());
        FilterRegistrationBean<RequestSizeLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(filter.getOrder());
        return registration;
    }

    /**
     * The innermost edge filter: it answers client errors (4xx) that escape the rest of the filter chain, such as a
     * form body that Boot's {@code FormContentFilter} (order -9900) can't decode, with the problem response, logged at
     * DEBUG, instead of leaving them to the container, which logs them at ERROR. Server errors are rethrown to the
     * container's error dispatch unchanged. It handles REQUEST dispatches only. Define a bean with the same name to
     * replace it.
     *
     * @param writer writes the problem response
     * @return the filter registration
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseClientErrorFilter")
    FilterRegistrationBean<ClientErrorFilter> synapseClientErrorFilter(ProblemWriter writer) {
        ClientErrorFilter filter = new ClientErrorFilter(writer);
        FilterRegistrationBean<ClientErrorFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(filter.getOrder());
        return registration;
    }

    /**
     * The functional routes of every {@link RestBindings} bean, most specific template first. The bindings are resolved
     * and checked here, so a binding mistake fails startup. Define a bean with the same name to replace it.
     *
     * @param bindings the bindings
     * @param conventions the conventions
     * @param compiler compiles the bindings
     * @return the routes
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean(name = "synapseRestRoutes")
    RouterFunction<ServerResponse> synapseRestRoutes(ObjectProvider<RestBindings> bindings,
            ObjectProvider<RestConventions> conventions, RouteCompiler compiler) {
        return compiler.compile(bindings.orderedStream().toList(), conventions.orderedStream().toList());
    }

    /**
     * The 405 and {@code OPTIONS} fallback of the routes bean's Synapse routes, as a handler mapping ordered after
     * every other mapping (annotated controllers included) and before the static resources, so an escape-hatch
     * controller on a Synapse path wins. It never handles the container's error dispatch. Define a bean with the same
     * name to replace it.
     *
     * @param routes the routes bean
     * @return the handler mapping
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean(name = "synapseRestMethodNotAllowedMapping")
    MethodNotAllowedMapping synapseRestMethodNotAllowedMapping(
            @Qualifier("synapseRestRoutes") RouterFunction<?> routes) {
        return new MethodNotAllowedMapping(routes);
    }

    /**
     * Compiles the bindings into routes.
     *
     * @param validator resolves and checks the routes
     * @param binder binds the inputs
     * @param writer writes the responses
     * @param cursors unwraps the cursor tokens, and reports a cursor key generated at startup
     * @return the compiler
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean
    RouteCompiler routeCompiler(BindingValidator validator, RequestBinder binder, ResponseWriter writer,
            PageCursors cursors) {
        return new RouteCompiler(validator, binder, writer, cursors);
    }

    /**
     * Resolves and checks the bindings at startup.
     *
     * @param paging the paging settings, for the paging parameter names
     * @return the validator
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean
    BindingValidator bindingValidator(PagingSettings paging) {
        return new BindingValidator(paging);
    }

    /**
     * The paging settings of the routes: the parameter names and offset cap from {@code synapse.api.rest.paging}, and
     * the page sizes from {@code synapse.paging}.
     *
     * @param properties the REST properties
     * @param sizes core's paging properties
     * @return the settings
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean
    PagingSettings synapseRestPagingSettings(SynapseRestProperties properties, SynapsePagingProperties sizes) {
        return PagingSettings.of(properties.paging(), sizes);
    }

    /**
     * The codec of the opaque paging cursors: AES-256-GCM with the keys and maximum age of
     * {@code synapse.api.rest.paging.cursor}. Without {@code cursor.key}, the key is generated at startup. An invalid
     * key fails startup. Define a {@link CursorCodec} bean to replace it.
     *
     * @param properties the REST properties, for the cursor settings
     * @param clock the application clock, for the issue time and the expiry check
     * @return the codec
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean(CursorCodec.class)
    CursorCodec synapseCursorCodec(SynapseRestProperties properties, Clock clock) {
        // Never SecureRandom.getInstanceStrong(), which can block.
        SecureRandom random = new SecureRandom();
        SynapseRestProperties.Paging.Cursor cursor = properties.paging().cursor();
        return new AesGcmCursorCodec(CursorKeys.from(cursor, random), clock, cursor.maxAge(), random);
    }

    /**
     * Digests the filters of a request, which its cursor tokens are bound to. It writes them with its own canonical
     * mapper, never the application's, so the application's Jackson configuration can't make two requests' filters
     * look alike.
     *
     * @return the digest
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean
    InputDigest synapseInputDigest() {
        return new InputDigest();
    }

    /**
     * Converts between the operations' raw cursors and the tokens clients see.
     *
     * @param codec the cursor codec
     * @param digest digests the request filters
     * @param paging the paging settings, for the cursor parameter name
     * @return the cursors
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean
    PageCursors synapsePageCursors(CursorCodec codec, InputDigest digest, PagingSettings paging) {
        return new PageCursors(codec, digest, paging);
    }

    /**
     * Binds requests to operation inputs.
     *
     * @param jsonMapper Boot's mapper, if there is one; otherwise a default mapper is used
     * @param conversionService Spring MVC's conversion service
     * @param paging the paging settings, for the paging parameter names and caps
     * @return the binder
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean
    RequestBinder requestBinder(ObjectProvider<JsonMapper> jsonMapper,
            @Qualifier("mvcConversionService") ConversionService conversionService, PagingSettings paging) {
        return new RequestBinder(jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()), conversionService,
                paging);
    }

    /**
     * Writes operation outputs as responses.
     *
     * @param jsonMapper Boot's mapper, if there is one; otherwise a default mapper is used
     * @param conversionService Spring MVC's conversion service, which formats output header values
     * @param cursors wraps the next cursor of a page
     * @param etags computes the {@code ETag} of a route with {@code .etag}
     * @return the writer
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean
    ResponseWriter responseWriter(ObjectProvider<JsonMapper> jsonMapper,
            @Qualifier("mvcConversionService") ConversionService conversionService, PageCursors cursors,
            ETagValues etags) {
        return new ResponseWriter(jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()), conversionService,
                cursors, etags);
    }

    /**
     * Computes the {@code ETag} of a route with {@code .etag} from its output component, formatted with Spring MVC's
     * conversion service like the output headers.
     *
     * @param conversionService Spring MVC's conversion service
     * @return the entity tag values
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean
    ETagValues synapseETagValues(@Qualifier("mvcConversionService") ConversionService conversionService) {
        return new ETagValues(conversionService);
    }

    /**
     * Logs the exposed routes when the web server is initialized.
     *
     * @param routerFunctions the application's router functions
     * @return the inventory
     */
    @Bean
    @ConditionalOnBean(RestBindings.class)
    @ConditionalOnMissingBean
    ExposureInventory exposureInventory(ObjectProvider<RouterFunction<?>> routerFunctions) {
        return new ExposureInventory(routerFunctions);
    }
}
