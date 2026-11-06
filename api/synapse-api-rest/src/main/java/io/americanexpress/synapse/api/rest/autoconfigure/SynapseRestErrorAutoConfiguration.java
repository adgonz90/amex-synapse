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

import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.web.servlet.function.RouterFunction;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.internal.error.ProblemTitles;
import io.americanexpress.synapse.api.rest.internal.error.ProblemWriter;
import io.americanexpress.synapse.api.rest.internal.error.RestErrorStatuses;
import io.americanexpress.synapse.api.rest.internal.error.SynapseErrorController;
import io.americanexpress.synapse.api.rest.internal.error.SynapseRestExceptionResolver;
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
public class SynapseRestErrorAutoConfiguration {

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
     * Turns exceptions that reach Spring MVC into problem responses.
     *
     * @param writer the problem writer
     * @return the resolver
     */
    @Bean
    @ConditionalOnMissingBean
    SynapseRestExceptionResolver synapseRestExceptionResolver(ProblemWriter writer) {
        return new SynapseRestExceptionResolver(writer);
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
