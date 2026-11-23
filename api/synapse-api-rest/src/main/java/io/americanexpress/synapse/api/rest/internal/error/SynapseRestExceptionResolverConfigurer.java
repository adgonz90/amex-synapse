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
package io.americanexpress.synapse.api.rest.internal.error;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

/**
 * Adds Synapse's two {@link SynapseRestExceptionResolver} instances to Spring MVC's composite resolver, so the order
 * is: the Synapse-paths instance, Spring's {@code ExceptionHandlerExceptionResolver} (the application's
 * {@code @ExceptionHandler} methods and {@code @ControllerAdvice} classes), the main instance, then
 * {@code ResponseStatusExceptionResolver} and {@code DefaultHandlerExceptionResolver}. Boot's
 * {@code DefaultErrorAttributes}, a resolver bean of its own, runs before the composite.
 */
public final class SynapseRestExceptionResolverConfigurer implements WebMvcConfigurer {

    private final ProblemWriter writer;

    private final ObjectProvider<RouterFunction<?>> routes;

    /**
     * Creates the configurer. It takes the routes lazily: Spring MVC's configuration class gets every
     * {@code WebMvcConfigurer} injected while it is created, and {@code synapseRestRoutes} needs its
     * {@code mvcConversionService} (through the route compiler's request binder), so a constructor argument built from
     * the routes would close a bean cycle.
     *
     * @param writer writes the problem responses
     * @param routes the {@code synapseRestRoutes} bean, read when the resolvers are extended; absent without
     *     {@code RestBindings}
     */
    public SynapseRestExceptionResolverConfigurer(ProblemWriter writer,
            @Qualifier("synapseRestRoutes") ObjectProvider<RouterFunction<?>> routes) {
        this.writer = writer;
        this.routes = routes;
    }

    /**
     * Inserts the Synapse-paths instance at index 0, built from the routes ({@link SynapsePaths#none()} without them),
     * and the main instance right after the {@code ExceptionHandlerExceptionResolver}, or at index 1 when an
     * application removed that one.
     *
     * @param resolvers Spring MVC's resolvers, in order
     */
    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        RouterFunction<?> served = routes.getIfAvailable();
        SynapsePaths paths = served == null ? SynapsePaths.none() : SynapsePaths.of(served);
        resolvers.add(0, SynapseRestExceptionResolver.forSynapsePaths(writer, paths));
        int handlers = -1;
        for (int i = 0; i < resolvers.size(); i++) {
            if (resolvers.get(i) instanceof ExceptionHandlerExceptionResolver) {
                handlers = i;
                break;
            }
        }
        resolvers.add(handlers < 0 ? 1 : handlers + 1, new SynapseRestExceptionResolver(writer));
    }
}
