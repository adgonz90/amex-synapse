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

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.function.RouterFunction;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.internal.error.ProblemTitles;
import io.americanexpress.synapse.api.rest.internal.error.ProblemWriter;
import io.americanexpress.synapse.api.rest.internal.error.RestErrorStatuses;
import io.americanexpress.synapse.api.rest.internal.error.SynapseErrorController;
import io.americanexpress.synapse.api.rest.internal.error.SynapseRestExceptionResolverConfigurer;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCorrelationProperties;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;
import io.americanexpress.synapse.core.error.ErrorMessages;

/**
 * Auto-configuration for the REST error handling. It is separate from {@link SynapseRestAutoConfiguration} because
 * Boot orders {@link ErrorMvcAutoConfiguration} before {@code WebMvcAutoConfiguration}, and the Synapse error
 * controller must be defined before Boot's.
 */
@AutoConfiguration(before = ErrorMvcAutoConfiguration.class, after = {SynapseCoreAutoConfiguration.class,
        SynapseContextAutoConfiguration.class,
        SynapseOperationAutoConfiguration.class}, afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass(RouterFunction.class)
@EnableConfigurationProperties(SynapseRestProperties.class)
public final class SynapseRestErrorAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    /** Boot's property that turns its {@code ProblemDetailsExceptionHandler} on. */
    private static final String PROBLEM_DETAILS = "spring.mvc.problemdetails.enabled";

    /**
     * Resolves the problem titles of the error categories.
     *
     * @param messageSource the application's message source
     * @return the titles
     */
    @Bean
    @ConditionalOnMissingBean
    ProblemTitles problemTitles(MessageSource messageSource) {
        return new ProblemTitles(messageSource);
    }

    /**
     * Decides the HTTP status of each error from {@code synapse.api.rest.errors.status} and the per-operation
     * overrides.
     *
     * @param properties the REST properties
     * @return the status table
     */
    @Bean
    @ConditionalOnMissingBean
    RestErrorStatuses restErrorStatuses(SynapseRestProperties properties) {
        Map<String, Map<String, Integer>> perOperation = new HashMap<>();
        properties.operations().forEach((name, settings) -> perOperation.put(name, settings.errors().status()));
        return new RestErrorStatuses(properties.errors().status(), perOperation);
    }

    /**
     * Writes errors as problem responses.
     *
     * @param statuses the status table
     * @param messages the error messages
     * @param titles the category titles
     * @param errorProperties the error settings
     * @param jsonMapper Boot's mapper, if there is one; otherwise a default mapper is used
     * @param correlation the correlation settings, for the header that echoes the id
     * @return the writer
     */
    @Bean
    @ConditionalOnMissingBean
    ProblemWriter problemWriter(RestErrorStatuses statuses, ErrorMessages messages, ProblemTitles titles,
            SynapseErrorProperties errorProperties, ObjectProvider<JsonMapper> jsonMapper,
            SynapseCorrelationProperties correlation) {
        return new ProblemWriter(statuses, messages, titles, errorProperties,
                jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()), correlation.header());
    }

    /**
     * Adds Synapse's exception resolver to Spring MVC's composite resolver, twice: first, for exceptions raised on a
     * Synapse route's path before a handler was found or by a Synapse handler, and for the 404 of an unknown path a
     * Synapse route claims (under its literal path prefix, or its own template without one, under any version for a
     * versioned route), and right after Spring's {@code ExceptionHandlerExceptionResolver}, for every other exception,
     * so the application's {@code @ExceptionHandler} methods and {@code @ControllerAdvice} classes answer first for its
     * controllers. While {@value #PROBLEM_DETAILS} is {@code true}, it logs one WARN: Boot's
     * {@code ProblemDetailsExceptionHandler} then answers Spring MVC's exceptions from controllers, and the 404 of an
     * unknown path that no Synapse route claims, in Spring's problem shape. Internal: the bean backs off by name only.
     *
     * @param writer the problem writer
     * @param routes the {@code synapseRestRoutes} bean, read when Spring MVC builds its resolvers, not here
     * @param environment the environment, for {@value #PROBLEM_DETAILS}
     * @return the configurer
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseRestExceptionResolverConfigurer")
    SynapseRestExceptionResolverConfigurer synapseRestExceptionResolverConfigurer(ProblemWriter writer,
            @Qualifier("synapseRestRoutes") ObjectProvider<RouterFunction<?>> routes, Environment environment) {
        if (environment.getProperty(PROBLEM_DETAILS, Boolean.class, false)) {
            LOG
                    .warn(PROBLEM_DETAILS
                            + " is true: Spring Boot's ProblemDetailsExceptionHandler answers Spring MVC's "
                            + "exceptions from your controllers, and the 404 of an unknown path that no Synapse route "
                            + "claims (one under a route's literal path prefix, or a route's own template when it has "
                            + "no prefix), in Spring's problem shape instead of Synapse's (no code or correlationId). "
                            + "Synapse routes, and the unknown paths they claim, under any version for a versioned "
                            + "route, are not affected.");
        }
        return new SynapseRestExceptionResolverConfigurer(writer, routes);
    }

    /**
     * Answers the servlet container's error dispatch with a problem response. Boot's {@code BasicErrorController}
     * backs off because this is an {@link ErrorController}.
     *
     * @param writer the problem writer
     * @return the error controller
     */
    @Bean
    @ConditionalOnMissingBean(ErrorController.class)
    SynapseErrorController synapseErrorController(ProblemWriter writer) {
        return new SynapseErrorController(writer);
    }
}
