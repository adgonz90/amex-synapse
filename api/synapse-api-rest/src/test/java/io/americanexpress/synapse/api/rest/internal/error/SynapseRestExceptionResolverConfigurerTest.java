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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SynapseRestExceptionResolverConfigurerTest {

    private final ProblemWriter writer = writer();

    @Test
    void extendsSpringsDefaultsInTheDecidedOrder() {
        ExceptionHandlerExceptionResolver handlers = new ExceptionHandlerExceptionResolver();
        ResponseStatusExceptionResolver statuses = new ResponseStatusExceptionResolver();
        DefaultHandlerExceptionResolver defaults = new DefaultHandlerExceptionResolver();
        List<HandlerExceptionResolver> resolvers = new ArrayList<>(List.of(handlers, statuses, defaults));

        new SynapseRestExceptionResolverConfigurer(writer, routes(SynapsePathsTest.booksRoutes()))
                .extendHandlerExceptionResolvers(resolvers);

        assertThat(resolvers).hasSize(5);
        assertThat(isPathsInstance(resolvers.get(0))).isTrue();
        assertThat(resolvers.get(1)).isSameAs(handlers);
        assertThat(isMainInstance(resolvers.get(2))).isTrue();
        assertThat(resolvers.get(3)).isSameAs(statuses);
        assertThat(resolvers.get(4)).isSameAs(defaults);
    }

    @Test
    void withoutExceptionHandlerExceptionResolverTheMainInstanceIsSecond() {
        ResponseStatusExceptionResolver statuses = new ResponseStatusExceptionResolver();
        List<HandlerExceptionResolver> resolvers = new ArrayList<>(List.of(statuses));

        new SynapseRestExceptionResolverConfigurer(writer, routes(SynapsePathsTest.booksRoutes()))
                .extendHandlerExceptionResolvers(resolvers);

        assertThat(resolvers).hasSize(3);
        assertThat(isPathsInstance(resolvers.get(0))).isTrue();
        assertThat(isMainInstance(resolvers.get(1))).isTrue();
        assertThat(resolvers.get(2)).isSameAs(statuses);

        List<HandlerExceptionResolver> none = new ArrayList<>();
        new SynapseRestExceptionResolverConfigurer(writer, routes(null)).extendHandlerExceptionResolvers(none);
        assertThat(none).hasSize(2);
        assertThat(isMainInstance(none.get(1))).isTrue();
    }

    /**
     * The routes are read only when Spring MVC builds its resolvers: the configurer is created while Spring MVC's
     * configuration collects its {@code WebMvcConfigurer}s, and the routes need that configuration's conversion service.
     */
    @Test
    void routesAreReadWhenTheResolversAreExtended() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RouterFunction<?>> routes = mock(ObjectProvider.class);
        when(routes.getIfAvailable()).thenReturn(null);

        SynapseRestExceptionResolverConfigurer configurer = new SynapseRestExceptionResolverConfigurer(writer, routes);
        verifyNoInteractions(routes);

        configurer.extendHandlerExceptionResolvers(new ArrayList<>());
        verify(routes).getIfAvailable();
    }

    @Test
    void withoutRoutesThePathsInstanceMatchesNothing() {
        List<HandlerExceptionResolver> resolvers = new ArrayList<>();

        new SynapseRestExceptionResolverConfigurer(writer, routes(null)).extendHandlerExceptionResolvers(resolvers);

        for (String path : List.of("/", "/books/7", "/error")) {
            assertThat(resolvers
                    .getFirst()
                    .resolveException(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), null,
                            new IllegalStateException("x")))
                    .as(path)
                    .isNull();
        }
    }

    /** The paths instance answers a pre-handler error on a Synapse path, and ignores a controller's. */
    static boolean isPathsInstance(HandlerExceptionResolver resolver) {
        return resolver instanceof SynapseRestExceptionResolver
                && resolver
                        .resolveException(new MockHttpServletRequest("GET", "/books/7"), new MockHttpServletResponse(),
                                controller(), new IllegalStateException("x")) == null;
    }

    /** The main instance answers a controller's exception on any path. */
    static boolean isMainInstance(HandlerExceptionResolver resolver) {
        return resolver instanceof SynapseRestExceptionResolver
                && resolver
                        .resolveException(new MockHttpServletRequest("GET", "/shelves/7"),
                                new MockHttpServletResponse(), controller(), new IllegalStateException("x")) != null;
    }

    private static HandlerMethod controller() {
        try {
            return new HandlerMethod(new Object(), Object.class.getMethod("toString"));
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RouterFunction<?>> routes(@Nullable RouterFunction<?> routes) {
        ObjectProvider<RouterFunction<?>> provider = mock(ObjectProvider.class);
        doReturn(routes).when(provider).getIfAvailable();
        return provider;
    }

    private static ProblemWriter writer() {
        StaticMessageSource messages = new StaticMessageSource();
        return new ProblemWriter(new RestErrorStatuses(Map.of(), Map.of()), new ErrorMessages(messages),
                new ProblemTitles(messages), new SynapseErrorProperties(false, null), JsonMapper.builder().build(),
                "X-Correlation-Id");
    }
}
