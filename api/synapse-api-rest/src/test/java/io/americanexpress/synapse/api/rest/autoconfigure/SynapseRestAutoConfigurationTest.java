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
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.ConfigurableWebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RequestBinder;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetPlainBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.HiddenOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PagedBooksOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PagedBooksRequest;
import io.americanexpress.synapse.api.rest.internal.conditional.ETagValues;
import io.americanexpress.synapse.api.rest.internal.cors.SynapseCorsConfigurationSource;
import io.americanexpress.synapse.api.rest.internal.cors.SynapseCorsFilter;
import io.americanexpress.synapse.api.rest.internal.edge.ConcurrencyLimitFilter;
import io.americanexpress.synapse.api.rest.internal.edge.DeadlinePolicy;
import io.americanexpress.synapse.api.rest.internal.edge.InboundBaggage;
import io.americanexpress.synapse.api.rest.internal.edge.InboundPropagationFilter;
import io.americanexpress.synapse.api.rest.internal.edge.RequestSizeLimitFilter;
import io.americanexpress.synapse.api.rest.internal.edge.RequestTooLargeException;
import io.americanexpress.synapse.api.rest.internal.edge.SynapseRestContextFilter;
import io.americanexpress.synapse.api.rest.internal.error.ClientErrorFilter;
import io.americanexpress.synapse.api.rest.internal.error.ProblemTitles;
import io.americanexpress.synapse.api.rest.internal.error.ProblemWriter;
import io.americanexpress.synapse.api.rest.internal.error.RestErrorStatuses;
import io.americanexpress.synapse.api.rest.internal.paging.AesGcmCursorCodec;
import io.americanexpress.synapse.api.rest.internal.paging.CursorKeys;
import io.americanexpress.synapse.api.rest.internal.paging.InputDigest;
import io.americanexpress.synapse.api.rest.internal.paging.PageCursors;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.api.rest.internal.route.ExposureInventory;
import io.americanexpress.synapse.api.rest.internal.route.MethodNotAllowedHandler;
import io.americanexpress.synapse.api.rest.internal.route.MethodNotAllowedMapping;
import io.americanexpress.synapse.api.rest.internal.route.OperationHandler;
import io.americanexpress.synapse.api.rest.internal.route.ResponseWriter;
import io.americanexpress.synapse.api.rest.internal.route.RouteCompiler;
import io.americanexpress.synapse.api.rest.internal.version.SynapseApiVersioning;
import io.americanexpress.synapse.api.rest.paging.CursorCodec;
import io.americanexpress.synapse.api.rest.paging.CursorScope;
import io.americanexpress.synapse.api.rest.paging.InvalidCursorException;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class SynapseRestAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class,
                            SynapseRestAutoConfiguration.class))
            .withBean(ProblemWriter.class, SynapseRestAutoConfigurationTest::problemWriter);

    /**
     * The auto-configurations of a REST service: Spring MVC, Jackson, observation and OpenTelemetry tracing, the
     * operation pipeline and the REST plugin.
     */
    private final WebApplicationContextRunner routesRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(AopAutoConfiguration.class, ValidationAutoConfiguration.class, JacksonAutoConfiguration.class,
                            HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class,
                            ObservationAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
                            OpenTelemetryTracingAutoConfiguration.class, MicrometerTracingAutoConfiguration.class,
                            SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class,
                            SynapseOperationAutoConfiguration.class, SynapseRestErrorAutoConfiguration.class,
                            SynapseRestAutoConfiguration.class));

    @Test
    void activatesInServletWebApplication() {
        webRunner.run(context -> assertThat(context).hasSingleBean(SynapseRestProperties.class));
    }

    @Test
    void absentInNonWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(SynapseCoreAutoConfiguration.class, SynapseRestAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SynapseRestProperties.class);
                    assertThat(context).doesNotHaveBean("synapseInboundPropagationFilter");
                    assertThat(context).doesNotHaveBean("synapseRestContextFilter");
                    assertThat(context).doesNotHaveBean("synapseConcurrencyLimitFilter");
                    assertThat(context).doesNotHaveBean("synapseRequestSizeLimitFilter");
                    assertThat(context).doesNotHaveBean("synapseClientErrorFilter");
                    assertThat(context).doesNotHaveBean("synapseCorsFilter");
                    assertThat(context).doesNotHaveBean(DeadlinePolicy.class);
                });
    }

    @Test
    void absentWithoutRouterFunctionOnTheClasspath() {
        webRunner
                .withClassLoader(new FilteredClassLoader(RouterFunction.class))
                .run(context -> assertThat(context).doesNotHaveBean(SynapseRestProperties.class));
    }

    @Test
    void bindsProperties() {
        webRunner
                .withPropertyValues("synapse.api.rest.deadline.default=5s", "synapse.api.rest.deadline.max=20s",
                        "synapse.api.rest.max-concurrent-requests=50",
                        "synapse.api.rest.errors.status.[BOOK_NOT_FOUND]=423",
                        "synapse.api.rest.operations.get-book.errors.status.[BOOK_NOT_FOUND]=410")
                .run(context -> {
                    SynapseRestProperties properties = context.getBean(SynapseRestProperties.class);
                    assertThat(properties.deadline().defaultTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.deadline().max()).isEqualTo(Duration.ofSeconds(20));
                    assertThat(properties.maxConcurrentRequests()).isEqualTo(50);
                    assertThat(properties.errors().status()).isEqualTo(Map.of("BOOK_NOT_FOUND", 423));
                    assertThat(Objects.requireNonNull(properties.operations().get("get-book")).errors().status())
                            .isEqualTo(Map.of("BOOK_NOT_FOUND", 410));
                });
    }

    @Test
    void bindsPagingProperties() {
        webRunner
                .withPropertyValues("synapse.api.rest.paging.parameters.size=page_size",
                        "synapse.api.rest.paging.max-offset=500", "synapse.api.rest.paging.cursor.max-age=1d",
                        "synapse.api.rest.paging.cursor.previous-keys[0]=b2xkb2xkb2xk")
                .run(context -> {
                    SynapseRestProperties.Paging paging = context.getBean(SynapseRestProperties.class).paging();
                    assertThat(paging.parameters().size()).isEqualTo("page_size");
                    assertThat(paging.parameters().cursor()).isEqualTo("cursor");
                    assertThat(paging.maxOffset()).isEqualTo(500);
                    assertThat(paging.cursor().maxAge()).isEqualTo(Duration.ofDays(1));
                    assertThat(paging.cursor().previousKeys()).containsExactly("b2xkb2xkb2xk");
                });
    }

    @Test
    void registersInboundPropagationFilterFirstForRequestDispatchesOnly() {
        webRunner.run(context -> {
            FilterRegistrationBean<?> registration = inboundRegistration(context);
            assertThat(registration.getFilter()).isInstanceOf(InboundPropagationFilter.class);
            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            assertThat(registration.determineDispatcherTypes()).isEqualTo(EnumSet.of(DispatcherType.REQUEST));
        });
    }

    @Test
    void inboundPropagationFilterBacksOffToUserRegistration() {
        webRunner.withUserConfiguration(UserInboundFilter.class).run(context -> {
            assertThat(inboundRegistration(context).getFilter()).isInstanceOf(CharacterEncodingFilter.class);
            assertThat(context)
                    .getBeans(FilterRegistrationBean.class)
                    .hasSize(6)
                    .containsKeys("synapseInboundPropagationFilter", "synapseRestContextFilter",
                            "synapseConcurrencyLimitFilter", "synapseRequestSizeLimitFilter",
                            "synapseClientErrorFilter", "synapseCorsFilter");
        });
    }

    @Test
    void registersContextFilterInsideTheServerObservationForRequestDispatchesOnly() {
        webRunner.run(context -> {
            FilterRegistrationBean<?> registration = contextRegistration(context);
            assertThat(registration.getFilter()).isInstanceOf(SynapseRestContextFilter.class);
            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2);
            assertThat(registration.determineDispatcherTypes()).isEqualTo(EnumSet.of(DispatcherType.REQUEST));
        });
    }

    @Test
    void edgeFiltersBackOffToUserRegistrations() {
        webRunner.withUserConfiguration(UserContextFilter.class).run(context -> {
            assertThat(contextRegistration(context).getFilter()).isInstanceOf(CharacterEncodingFilter.class);
            assertThat(context)
                    .getBeans(FilterRegistrationBean.class)
                    .hasSize(6)
                    .containsKeys("synapseInboundPropagationFilter", "synapseRestContextFilter",
                            "synapseConcurrencyLimitFilter", "synapseRequestSizeLimitFilter",
                            "synapseClientErrorFilter", "synapseCorsFilter");
            assertThat(inboundRegistration(context).getFilter()).isInstanceOf(InboundPropagationFilter.class);
        });
    }

    @Test
    void registersConcurrencyLimitFilterInsideTheCorsFilterForRequestDispatchesOnly() {
        webRunner.run(context -> {
            FilterRegistrationBean<?> registration = concurrencyRegistration(context);
            assertThat(registration.getFilter()).isInstanceOf(ConcurrencyLimitFilter.class);
            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 4);
            assertThat(registration.determineDispatcherTypes()).isEqualTo(EnumSet.of(DispatcherType.REQUEST));
        });
    }

    @Test
    void registersRequestSizeLimitFilterAfterTheConcurrencyLimitForRequestDispatchesOnly() {
        webRunner.run(context -> {
            FilterRegistrationBean<?> registration = sizeRegistration(context);
            assertThat(registration.getFilter()).isInstanceOf(RequestSizeLimitFilter.class);
            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 5);
            assertThat(registration.determineDispatcherTypes()).isEqualTo(EnumSet.of(DispatcherType.REQUEST));
        });
    }

    @Test
    void limitFiltersBackOffToUserRegistrations() {
        webRunner.withUserConfiguration(UserLimitFilters.class).run(context -> {
            assertThat(concurrencyRegistration(context).getFilter()).isInstanceOf(CharacterEncodingFilter.class);
            assertThat(sizeRegistration(context).getFilter()).isInstanceOf(CharacterEncodingFilter.class);
            assertThat(context).getBeans(FilterRegistrationBean.class).hasSize(6);
        });
    }

    @Test
    void registersClientErrorFilterInsideTheOtherEdgeFiltersForRequestDispatchesOnly() {
        webRunner.run(context -> {
            FilterRegistrationBean<?> registration = clientErrorRegistration(context);
            assertThat(registration.getFilter()).isInstanceOf(ClientErrorFilter.class);
            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 6);
            assertThat(registration.determineDispatcherTypes()).isEqualTo(EnumSet.of(DispatcherType.REQUEST));
        });
    }

    @Test
    void clientErrorFilterBacksOffToUserRegistration() {
        webRunner.withUserConfiguration(UserClientErrorFilter.class).run(context -> {
            assertThat(clientErrorRegistration(context).getFilter()).isInstanceOf(CharacterEncodingFilter.class);
            assertThat(sizeRegistration(context).getFilter()).isInstanceOf(RequestSizeLimitFilter.class);
            assertThat(context).getBeans(FilterRegistrationBean.class).hasSize(6);
        });
    }

    @Test
    void concurrencyLimitFilterBindsTheLimitAndCountsManagementPathsWithoutActuator() {
        webRunner
                .withPropertyValues("synapse.api.rest.max-concurrent-requests=1",
                        "management.endpoints.web.base-path=/manage")
                .run(context -> {
                    Filter filter = Objects.requireNonNull(concurrencyRegistration(context).getFilter());
                    MockHttpServletRequest held = new MockHttpServletRequest("GET", "/books/1");
                    held.setAsyncSupported(true);
                    filter.doFilter(held, new MockHttpServletResponse(), (request, response) -> request.startAsync());

                    MockHttpServletResponse limited = new MockHttpServletResponse();
                    filter.doFilter(new MockHttpServletRequest("GET", "/books/2"), limited, (request, response) -> {
                    });
                    assertThat(limited.getStatus()).isEqualTo(503);
                    assertThat(limited.getHeader("Retry-After")).isEqualTo("1");

                    // Actuator is not on this module's classpath, so no path is served by it and none is exempt.
                    MockHttpServletResponse management = new MockHttpServletResponse();
                    filter
                            .doFilter(new MockHttpServletRequest("GET", "/manage/health"), management,
                                    (request, response) -> {
                                    });
                    assertThat(management.getStatus()).isEqualTo(503);
                });
    }

    @Test
    void requestSizeLimitFilterBindsTheLimit() {
        webRunner.withPropertyValues("synapse.api.rest.max-request-size=10B").run(context -> {
            Filter filter = Objects.requireNonNull(sizeRegistration(context).getFilter());
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/books");
            request.setContent(new byte[11]);
            AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

            filter
                    .doFilter(request, new MockHttpServletResponse(),
                            (downstream, response) -> seen.set((HttpServletRequest) downstream));

            HttpServletRequest downstream = Objects.requireNonNull(seen.get(), "the chain was not called");
            assertThatExceptionOfType(RequestTooLargeException.class)
                    .isThrownBy(downstream::getInputStream)
                    .satisfies(e -> assertThat(e.limit()).isEqualTo(10));
        });
    }

    @Test
    void deadlinePolicyBindsTheDeadlineProperties() {
        MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
        webRunner
                .withBean(Clock.class, () -> clock)
                .withPropertyValues("synapse.api.rest.deadline.default=5s", "synapse.api.rest.deadline.max=20s")
                .run(context -> {
                    DeadlinePolicy policy = context.getBean(DeadlinePolicy.class);
                    assertThat(policy.deadline(null)).isEqualTo(Instant.parse("2026-01-01T00:00:05Z"));
                    assertThat(policy.deadline("60000")).isEqualTo(Instant.parse("2026-01-01T00:00:20Z"));
                });
    }

    @Test
    void deadlinePolicyBacksOffToUserBean() {
        DeadlinePolicy user = new DeadlinePolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), Clock.systemUTC());
        webRunner
                .withBean(DeadlinePolicy.class, () -> user)
                .run(context -> assertThat(context.getBean(DeadlinePolicy.class)).isSameAs(user));
    }

    @Test
    void contextFilterReadsTheConfiguredHeaders() {
        MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
        webRunner
                .withBean(Clock.class, () -> clock)
                .withPropertyValues("synapse.correlation.header=X-Request-Id",
                        "synapse.api.rest.context.headers.tenant=X-Tenant",
                        "synapse.api.rest.deadline.header=X-Timeout")
                .run(context -> {
                    MockHttpServletRequest request = new MockHttpServletRequest();
                    request.addHeader("X-Request-Id", "r-1");
                    request.addHeader("X-Tenant", "acme");
                    request.addHeader("X-Timeout", "250");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    AtomicReference<RequestContext> seen = new AtomicReference<>();

                    Objects
                            .requireNonNull(contextRegistration(context).getFilter())
                            .doFilter(request, response, (req, res) -> seen.set(RequestContext.current()));

                    RequestContext requestContext = Objects.requireNonNull(seen.get(), "the chain was not called");
                    assertThat(requestContext.correlationId()).isEqualTo("r-1");
                    assertThat(requestContext.attributes()).containsExactly(Map.entry("tenant", "acme"));
                    assertThat(requestContext.deadline()).contains(Instant.parse("2026-01-01T00:00:00.250Z"));
                    assertThat(response.getHeader("X-Request-Id")).isEqualTo("r-1");
                });
    }

    @Test
    void inboundPropagationFilterIsTrustedByDefaultAndReadsAttributeNames() {
        webRunner.withPropertyValues("synapse.api.rest.context.headers.tenant=X-Tenant").run(context -> {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
            request.addHeader("baggage", "correlationId=c-1,tenant=t1,partner=p");

            HttpServletRequest seen = filter(context, request);

            assertThat(seen.getHeader("traceparent")).isNotNull();
            assertThat(seen.getHeader("baggage")).isEqualTo("partner=p");
            assertThat(InboundBaggage.candidates(seen))
                    .containsExactlyInAnyOrderEntriesOf(Map.of("correlationId", "c-1", "tenant", "t1"));
        });
    }

    @Test
    void inboundPropagationFilterBindsTrustAndRemoteFields() {
        webRunner
                .withPropertyValues("synapse.context.inbound.trusted=false",
                        "management.tracing.baggage.remote-fields=region,zone")
                .run(context -> {
                    MockHttpServletRequest request = new MockHttpServletRequest();
                    request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
                    request.addHeader("region", "eu");
                    request.addHeader("zone", "a");
                    request.addHeader("Accept", "text/plain");

                    HttpServletRequest seen = filter(context, request);

                    assertThat(seen.getHeader("traceparent")).isNull();
                    assertThat(seen.getHeader("region")).isNull();
                    assertThat(seen.getHeader("zone")).isNull();
                    assertThat(seen.getHeader("Accept")).isEqualTo("text/plain");
                });
    }

    @Test
    void compilesRestBindingsIntoRoutes() {
        routesRunner.withUserConfiguration(Books.class).run(context -> {
            assertThat(context)
                    .hasSingleBean(RouteCompiler.class)
                    .hasSingleBean(BindingValidator.class)
                    .hasSingleBean(PagingSettings.class)
                    .hasSingleBean(RequestBinder.class)
                    .hasSingleBean(ResponseWriter.class)
                    .hasSingleBean(InputDigest.class)
                    .hasSingleBean(ETagValues.class)
                    .hasSingleBean(PageCursors.class)
                    .hasSingleBean(ExposureInventory.class)
                    .hasBean("synapseRestRoutes");
            assertThat(context.getBean(PagingSettings.class)).isEqualTo(PagingSettings.defaults());
            HandlerFunction<?> handler = Objects.requireNonNull(handlerFor(context, "GET", "/books/" + new UUID(0, 1)));
            assertThat(handler).isInstanceOf(OperationHandler.class);
            assertThat(((OperationHandler) handler).route().operationName()).isEqualTo("get-plain-book");
            // The 405 and OPTIONS fallback is a handler mapping of its own, after annotated controllers.
            assertThat(handlerFor(context, "DELETE", "/books/1")).isNull();
            assertThat(fallbackFor(context, "DELETE", "/books/1")).isInstanceOf(MethodNotAllowedHandler.class);
            assertThat(fallbackFor(context, "OPTIONS", "/books/1")).isInstanceOf(MethodNotAllowedHandler.class);
            assertThat(fallbackFor(context, "DELETE", "/nope")).isNull();
            // HEAD is served by the GET route's handler.
            assertThat(handlerFor(context, "HEAD", "/books/" + new UUID(0, 1))).isSameAs(handler);
        });
    }

    @Test
    void routesBeanBacksOffByName() {
        routesRunner.withUserConfiguration(Books.class, UserRoutes.class).run(context -> {
            assertThat(context.getBean("synapseRestRoutes")).isSameAs(context.getBean(UserRoutes.class).routes);
            assertThat(handlerFor(context, "GET", "/books/" + new UUID(0, 1))).isNull();
            assertThat(handlerFor(context, "GET", "/user")).isNotNull();
            // The fallback covers the routes the routes bean serves, here none.
            assertThat(fallbackFor(context, "DELETE", "/books/1")).isNull();
        });
    }

    @Test
    void routeBeansAreAbsentWithoutRestBindings() {
        routesRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .doesNotHaveBean("synapseRestRoutes")
                    .doesNotHaveBean("synapseRestMethodNotAllowedMapping")
                    .doesNotHaveBean(RouteCompiler.class)
                    .doesNotHaveBean(BindingValidator.class)
                    .doesNotHaveBean(PagingSettings.class)
                    .doesNotHaveBean(RequestBinder.class)
                    .doesNotHaveBean(ResponseWriter.class)
                    .doesNotHaveBean(InputDigest.class)
                    .doesNotHaveBean(ETagValues.class)
                    .doesNotHaveBean(PageCursors.class)
                    .doesNotHaveBean(ExposureInventory.class);
        });
    }

    @Test
    void apiVersioningBeanActivatesAndBacksOff() {
        routesRunner.withUserConfiguration(Books.class).run(context -> {
            assertThat(context).hasSingleBean(SynapseApiVersioning.class).hasBean("synapseApiVersioning");
            assertThat(context.getBean("synapseApiVersioning")).isInstanceOf(SynapseApiVersioning.class);
        });
        WebMvcConfigurer user = new WebMvcConfigurer() {
        };
        routesRunner
                .withUserConfiguration(Books.class)
                .withBean("synapseApiVersioning", WebMvcConfigurer.class, () -> user)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SynapseApiVersioning.class);
                    assertThat(context.getBean("synapseApiVersioning")).isSameAs(user);
                });
        routesRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SynapseApiVersioning.class).doesNotHaveBean("synapseApiVersioning");
        });
    }

    @Test
    void corsSourceIsAlwaysPresent() {
        webRunner.run(context -> {
            assertThat(context).hasSingleBean(SynapseCorsConfigurationSource.class);
            assertThat(corsSource(context).getCorsConfiguration(new MockHttpServletRequest("GET", "/books"))).isNull();
        });
        routesRunner.withUserConfiguration(Books.class).run(context -> {
            assertThat(context.getBean("corsConfigurationSource")).isInstanceOf(SynapseCorsConfigurationSource.class);
            assertThat(corsSource(context).getCorsConfiguration(new MockHttpServletRequest("GET", "/books/1")))
                    .isNull();
        });
        // Without bindings the source is there, so an environment file can still set the origins.
        webRunner
                .withPropertyValues("synapse.api.rest.cors.allowed-origins=https://app.example.com")
                .run(context -> assertThat(Objects
                        .requireNonNull(corsSource(context)
                                .getCorsConfiguration(new MockHttpServletRequest("GET", "/books")))
                        .getAllowedOrigins()).containsExactly("https://app.example.com"));
    }

    @Test
    void corsSourceBacksOffByName() {
        // Spring MVC's HandlerMappingIntrospector is a CorsConfigurationSource too; it doesn't make the source back off.
        routesRunner.withUserConfiguration(Books.class).run(context -> {
            assertThat(context).hasSingleBean(HandlerMappingIntrospector.class);
            assertThat(context).hasSingleBean(SynapseCorsConfigurationSource.class);
        });
        CorsConfigurationSource user = request -> null;
        webRunner.withBean("corsConfigurationSource", CorsConfigurationSource.class, () -> user).run(context -> {
            assertThat(context).doesNotHaveBean(SynapseCorsConfigurationSource.class);
            assertThat(context.getBean("corsConfigurationSource")).isSameAs(user);
        });
    }

    @Test
    void bindsCorsProperties() {
        webRunner
                .withPropertyValues("synapse.api.rest.cors.allowed-origins[0]=https://app.example.com",
                        "synapse.api.rest.cors.max-age=10m", "synapse.api.rest.cors.exposed-headers=X-A",
                        "synapse.api.rest.cors.allowed-methods=GET,PATCH",
                        "synapse.api.rest.cors.allowed-headers=X-Tenant",
                        "synapse.api.rest.cors.allow-credentials=true", "synapse.correlation.header=X-Request-Id")
                .run(context -> {
                    SynapseRestProperties.Cors cors = context.getBean(SynapseRestProperties.class).cors();
                    assertThat(cors.allowedOrigins()).containsExactly("https://app.example.com");
                    assertThat(cors.maxAge()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(cors.exposedHeaders()).containsExactly("X-A");
                    assertThat(cors.allowedMethods()).containsExactly("GET", "PATCH");
                    assertThat(cors.allowedHeaders()).containsExactly("X-Tenant");
                    assertThat(cors.allowCredentials()).isTrue();
                    CorsConfiguration configuration = Objects
                            .requireNonNull(corsSource(context)
                                    .getCorsConfiguration(new MockHttpServletRequest("GET", "/books")));
                    assertThat(configuration.getMaxAge()).isEqualTo(600L);
                    assertThat(configuration.getAllowedMethods()).containsExactly("GET", "PATCH");
                    assertThat(configuration.getAllowCredentials()).isTrue();
                    assertThat(configuration.getExposedHeaders())
                            .containsExactly("X-Request-Id", "ETag", "Link", "Location", "Retry-After",
                                    "Deprecation", "Sunset", "X-A");
                });
    }

    @Test
    void wildcardWithCredentialsFailsStartup() {
        webRunner
                .withPropertyValues("synapse.api.rest.cors.allowed-origins=*",
                        "synapse.api.rest.cors.allow-credentials=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("synapse.api.rest.cors.allow-credentials can't be true while"
                                    + " synapse.api.rest.cors.allowed-origins contains *: browsers refuse credentials"
                                    + " for any origin. List the exact origins.");
                });
    }

    @Test
    void emptyCorsMethodsFailStartup() {
        webRunner.withPropertyValues("synapse.api.rest.cors.allowed-methods=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("synapse.api.rest.cors.allowed-methods must list at least one method (or *) but was"
                            + " empty");
        });
    }

    @Test
    void wildcardOriginWarnsInEveryProfile() {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            for (String profile : List.of("prod", "production", "test")) {
                lines.list.clear();
                webRunner
                        .withPropertyValues("synapse.api.rest.cors.allowed-origins=*",
                                "spring.profiles.active=" + profile)
                        .run(context -> assertThat(context).hasNotFailed());
                assertThat(lines.list)
                        .as(profile)
                        .filteredOn(line -> line.getFormattedMessage().contains("allowed-origins contains *"))
                        .singleElement()
                        .satisfies(line -> assertThat(line.getLevel()).isEqualTo(Level.WARN));
            }
        } finally {
            logger.detachAppender(lines);
        }
    }

    @Test
    void corsSourceCoversManagementPathsWithoutActuator() {
        webRunner
                .withPropertyValues("synapse.api.rest.cors.allowed-origins=https://app.example.com",
                        "management.endpoints.web.base-path=/manage")
                .run(context -> {
                    // Actuator is not on this module's classpath, so no path is served by it and none is left to Boot.
                    assertThat(corsSource(context)
                            .getCorsConfiguration(new MockHttpServletRequest("GET", "/manage/health"))).isNotNull();
                    assertThat(corsSource(context).getCorsConfiguration(new MockHttpServletRequest("GET", "/books")))
                            .isNotNull();
                });
    }

    @Test
    void registersCorsFilterBeforeTheLimitsForRequestAndErrorDispatches() {
        webRunner.run(context -> {
            FilterRegistrationBean<?> registration = corsRegistration(context);
            assertThat(registration.getFilter()).isInstanceOf(SynapseCorsFilter.class);
            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 3);
            assertThat(registration.determineDispatcherTypes())
                    .isEqualTo(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR));
        });
    }

    @Test
    void corsFilterActivatesAndBacksOff() {
        String app = "https://app.example.com";
        // With bindings, next to Spring MVC's HandlerMappingIntrospector, which is a CorsConfigurationSource too.
        routesRunner
                .withUserConfiguration(Books.class)
                .withPropertyValues("synapse.api.rest.cors.allowed-origins=" + app)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HandlerMappingIntrospector.class);
                    assertThat(corsFiltered(context, app).getHeader("Access-Control-Allow-Origin")).isEqualTo(app);
                });
        // Without bindings, so an environment file can still turn CORS on.
        webRunner.withPropertyValues("synapse.api.rest.cors.allowed-origins=" + app).run(context -> {
            assertThat(corsFiltered(context, app).getHeader("Access-Control-Allow-Origin")).isEqualTo(app);
            MockHttpServletResponse rejected = corsFiltered(context, "https://evil.example");
            assertThat(rejected.getStatus()).isEqualTo(403);
            assertThat(rejected.getContentAsString()).contains("\"code\":\"CORS_REJECTED\"");
        });
        // It applies the bean named corsConfigurationSource, a user's own included.
        CorsConfiguration userConfiguration = new CorsConfiguration();
        userConfiguration.addAllowedOrigin("https://user.example.com");
        CorsConfigurationSource user = request -> userConfiguration;
        webRunner
                .withBean("corsConfigurationSource", CorsConfigurationSource.class, () -> user)
                .run(context -> assertThat(corsFiltered(context, "https://user.example.com")
                        .getHeader("Access-Control-Allow-Origin")).isEqualTo("https://user.example.com"));
        // A user's registration with the same name replaces it.
        webRunner.withUserConfiguration(UserCorsFilter.class).run(context -> {
            assertThat(corsRegistration(context).getFilter()).isInstanceOf(CharacterEncodingFilter.class);
            assertThat(context).getBeans(FilterRegistrationBean.class).hasSize(6);
        });
        // Absent outside a servlet application.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(SynapseCoreAutoConfiguration.class, SynapseRestAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean("synapseCorsFilter"));
    }

    @Test
    void methodNotAllowedMappingRunsAfterTheOtherMappingsAndBeforeStaticResources() {
        routesRunner.withUserConfiguration(Books.class).run(context -> {
            int fallback = context
                    .getBean("synapseRestMethodNotAllowedMapping", MethodNotAllowedMapping.class)
                    .getOrder();
            Map<String, HandlerMapping> mappings = context.getBeansOfType(HandlerMapping.class);
            assertThat(mappings)
                    .containsKeys("routerFunctionMapping", "requestMappingHandlerMapping",
                            "resourceHandlerMapping");
            mappings.forEach((name, mapping) -> {
                int order = ((Ordered) mapping).getOrder();
                if (name.equals("resourceHandlerMapping")) {
                    assertThat(fallback).as(name).isLessThan(order);
                } else if (!name.equals("synapseRestMethodNotAllowedMapping")) {
                    assertThat(fallback).as(name).isGreaterThan(order);
                }
            });
        });
    }

    @Test
    void methodNotAllowedMappingBacksOffByName() {
        HandlerMapping user = request -> null;
        routesRunner
                .withUserConfiguration(Books.class)
                .withBean("synapseRestMethodNotAllowedMapping", HandlerMapping.class, () -> user)
                .run(context -> {
                    assertThat(context.getBean("synapseRestMethodNotAllowedMapping")).isSameAs(user);
                    assertThat(context).doesNotHaveBean(MethodNotAllowedMapping.class);
                });
    }

    @Test
    void routeBeansBackOffToUserBeans() {
        JsonMapper mapper = JsonMapper.builder().build();
        DefaultFormattingConversionService conversions = new DefaultFormattingConversionService();
        InputDigest digest = new InputDigest();
        PageCursors cursors = new PageCursors(new AesGcmCursorCodec(CursorKeys
                .from(
                        new SynapseRestProperties.Paging.Cursor(null, List.of(), null), new SecureRandom()),
                Clock.systemUTC(),
                null, new SecureRandom()), digest, PagingSettings.defaults());
        ETagValues etags = new ETagValues(conversions);
        ResponseWriter writer = new ResponseWriter(mapper, conversions, cursors, etags);
        BindingValidator validator = new BindingValidator(PagingSettings.defaults());
        RequestBinder binder = new RequestBinder(mapper, conversions, PagingSettings.defaults());
        RouteCompiler compiler = new RouteCompiler(validator, binder, writer, cursors);
        ExposureInventory inventory = new ExposureInventory(new StaticListableBeanFactory()
                .getBeanProvider(
                        ResolvableType.forClassWithGenerics(RouterFunction.class, ServerResponse.class)));
        routesRunner
                .withUserConfiguration(Books.class)
                .withBean(ResponseWriter.class, () -> writer)
                .withBean(InputDigest.class, () -> digest)
                .withBean(ETagValues.class, () -> etags)
                .withBean(PageCursors.class, () -> cursors)
                .withBean(BindingValidator.class, () -> validator)
                .withBean(RequestBinder.class, () -> binder)
                .withBean(RouteCompiler.class, () -> compiler)
                .withBean(ExposureInventory.class, () -> inventory)
                .run(context -> {
                    assertThat(context.getBean(ResponseWriter.class)).isSameAs(writer);
                    assertThat(context.getBean(InputDigest.class)).isSameAs(digest);
                    assertThat(context.getBean(ETagValues.class)).isSameAs(etags);
                    assertThat(context.getBean(PageCursors.class)).isSameAs(cursors);
                    assertThat(context.getBean(BindingValidator.class)).isSameAs(validator);
                    assertThat(context.getBean(RequestBinder.class)).isSameAs(binder);
                    assertThat(context.getBean(RouteCompiler.class)).isSameAs(compiler);
                    assertThat(context.getBean(ExposureInventory.class)).isSameAs(inventory);
                    assertThat(context).hasBean("synapseRestRoutes");
                });
    }

    @Test
    void etagValuesFormatWithTheMvcConversionService() {
        routesRunner.withUserConfiguration(VersionedBooks.class).run(context -> {
            MockMvc mvc = MockMvcBuilders
                    .webAppContextSetup(context.getSourceApplicationContext(ConfigurableWebApplicationContext.class))
                    .build();

            MockHttpServletResponse response = mvc.perform(get("/versioned/b1")).andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("ETag")).isEqualTo("\"v3\"");
            assertThat(mvc
                    .perform(get("/versioned/b1").header("If-None-Match", "\"v3\""))
                    .andReturn()
                    .getResponse()
                    .getStatus()).isEqualTo(304);
        });
    }

    @Test
    void pagingSettingsBindTheRestAndCorePagingProperties() {
        routesRunner
                .withUserConfiguration(Books.class)
                .withPropertyValues("synapse.api.rest.paging.parameters.size=page_size",
                        "synapse.api.rest.paging.parameters.cursor=after", "synapse.api.rest.paging.max-offset=500",
                        "synapse.paging.default-size=10", "synapse.paging.max-size=50")
                .run(context -> assertThat(context.getBean(PagingSettings.class))
                        .isEqualTo(new PagingSettings("after", "offset", "page_size", "sort", 500, 10, 50)));
    }

    @Test
    void requestBinderReadsThePagingParametersOfTheSettingsBean() {
        routesRunner
                .withUserConfiguration(PagedBooks.class)
                .withPropertyValues("synapse.api.rest.paging.parameters.size=page_size", "synapse.paging.max-size=50")
                .run(context -> {
                    OperationHandler handler = (OperationHandler) Objects
                            .requireNonNull(handlerFor(context, "GET", "/books"));
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/books");
                    request.setQueryString("page_size=51&size=500");
                    request.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of());
                    RequestBinder binder = context.getBean(RequestBinder.class);
                    assertThatExceptionOfType(SynapseException.class)
                            .isThrownBy(() -> binder.bind(handler.route(), ServerRequest.create(request, List.of())))
                            .satisfies(e -> assertThat(e.violations())
                                    .containsExactly(new Violation("page_size", "must be less than or equal to 50")));
                });
    }

    @Test
    void pagingSettingsBackOffToAUserBeanThatTheValidatorUses() {
        PagingSettings user = new PagingSettings("cursor", "offset", "author", "sort", 1000, 20, 100);
        routesRunner
                .withUserConfiguration(PagedBooks.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PagingSettings.class));
        routesRunner
                .withUserConfiguration(Books.class)
                .withBean(PagingSettings.class, () -> user)
                .run(context -> assertThat(context.getBean(PagingSettings.class)).isSameAs(user));
        // The validator checks query names against the user's names: author is now a paging parameter.
        routesRunner
                .withUserConfiguration(PagedBooks.class)
                .withBean(PagingSettings.class, () -> user)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(SynapseConfigurationException.class)
                            .hasMessageContaining("query parameter author")
                            .hasMessageContaining("paging parameter");
                });
    }

    @Test
    void declaredHeadRouteBeatsTheImplicitOne() {
        routesRunner.withUserConfiguration(BooksWithHead.class).run(context -> {
            HandlerFunction<?> head = Objects.requireNonNull(handlerFor(context, "HEAD", "/books/" + new UUID(0, 1)));
            assertThat(((OperationHandler) head).route().operationName()).isEqualTo("get-book");
            HandlerFunction<?> get = Objects.requireNonNull(handlerFor(context, "GET", "/books/" + new UUID(0, 1)));
            assertThat(((OperationHandler) get).route().operationName()).isEqualTo("get-plain-book");
        });
    }

    @Test
    void bindingJacksonCannotReadFailsStartup() {
        routesRunner.withUserConfiguration(HiddenBindings.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(SynapseConfigurationException.class)
                    .hasMessageContaining("REST route POST /hidden (hidden): component HiddenRequest.internal is bound "
                            + "from the body but Jackson ignores it");
        });
    }

    @Test
    void invalidBindingFailsStartup() {
        routesRunner.withUserConfiguration(UnboundVariable.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(SynapseConfigurationException.class)
                    .hasMessageContaining("REST route GET /books/{bookId} (get-plain-book): the path variable "
                            + "{bookId} is not bound");
        });
    }

    @Test
    void operationRunsInsideThePipeline() {
        Logger failures = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.operation");
        Logger rest = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");
        ListAppender<ILoggingEvent> failureLines = new ListAppender<>();
        ListAppender<ILoggingEvent> restLines = new ListAppender<>();
        failureLines.start();
        restLines.start();
        failures.addAppender(failureLines);
        rest.addAppender(restLines);
        try {
            routesRunner.withUserConfiguration(Boom.class).run(context -> {
                MockMvc mvc = MockMvcBuilders
                        .webAppContextSetup(
                                context.getSourceApplicationContext(ConfigurableWebApplicationContext.class))
                        .build();

                MockHttpServletResponse response = mvc.perform(get("/boom")).andReturn().getResponse();

                assertThat(response.getStatus()).isEqualTo(500);
                assertThat(response.getContentType()).startsWith("application/problem+json");
                assertThat(JsonMapper.builder().build().readTree(response.getContentAsString()).get("code").asString())
                        .isEqualTo("INTERNAL");
                assertThat(response.getContentAsString()).doesNotContain("secret internals");
            });
        } finally {
            failures.detachAppender(failureLines);
            rest.detachAppender(restLines);
        }
        assertThat(failureLines.list)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).startsWith("Operation boom failed with INTERNAL");
                });
        assertThat(restLines.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
    }

    @Test
    void cursorCodecActivatesWithBindings() {
        routesRunner.withUserConfiguration(Books.class).run(context -> {
            assertThat(context).hasSingleBean(CursorCodec.class).hasBean("synapseCursorCodec");
            CursorCodec codec = context.getBean(CursorCodec.class);
            assertThat(codec).isInstanceOf(AesGcmCursorCodec.class);
            assertThat(((AesGcmCursorCodec) codec).keyGenerated()).isTrue();
            CursorScope scope = new CursorScope("get-plain-book", List.of(), "digest");
            assertThat(codec.decode(codec.encode("raw-7", scope), scope)).isEqualTo("raw-7");
        });
        routesRunner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CursorCodec.class));
    }

    @Test
    void cursorCodecBindsTheCursorProperties() {
        String oldKey = Base64.getEncoder().encodeToString(new byte[32]);
        byte[] newBytes = new byte[32];
        Arrays.fill(newBytes, (byte) 5);
        String newKey = Base64.getUrlEncoder().withoutPadding().encodeToString(newBytes);
        MutableClock clock = MutableClock.at("2026-11-04T00:00:00Z");
        CursorScope scope = new CursorScope("get-plain-book", List.of(), "digest");
        AtomicReference<String> token = new AtomicReference<>();
        routesRunner
                .withUserConfiguration(Books.class)
                .withBean(Clock.class, () -> clock)
                .withPropertyValues("synapse.api.rest.paging.cursor.key=" + oldKey)
                .run(context -> {
                    AesGcmCursorCodec codec = context.getBean(AesGcmCursorCodec.class);
                    assertThat(codec.keyGenerated()).isFalse();
                    token.set(codec.encode("raw-7", scope));
                });
        routesRunner
                .withUserConfiguration(Books.class)
                .withBean(Clock.class, () -> clock)
                .withPropertyValues("synapse.api.rest.paging.cursor.key=" + newKey,
                        "synapse.api.rest.paging.cursor.previous-keys[0]=" + oldKey,
                        "synapse.api.rest.paging.cursor.max-age=1h")
                .run(context -> {
                    CursorCodec codec = context.getBean(CursorCodec.class);
                    // The previous key still reads the token, until it is older than the maximum age.
                    assertThat(codec.decode(Objects.requireNonNull(token.get()), scope)).isEqualTo("raw-7");
                    clock.advance(Duration.ofHours(2));
                    assertThatExceptionOfType(InvalidCursorException.class)
                            .isThrownBy(() -> codec.decode(Objects.requireNonNull(token.get()), scope));
                });
    }

    @Test
    void cursorCodecBacksOffToUserBean() {
        CursorCodec user = new CursorCodec() {
            @Override
            public String encode(String cursor, CursorScope scope) {
                return cursor;
            }

            @Override
            public String decode(String token, CursorScope scope) {
                return token;
            }
        };
        routesRunner
                .withUserConfiguration(Books.class)
                .withBean(CursorCodec.class, () -> user)
                .run(context -> {
                    assertThat(context).hasSingleBean(CursorCodec.class).doesNotHaveBean("synapseCursorCodec");
                    assertThat(context.getBean(CursorCodec.class)).isSameAs(user);
                });
    }

    @Test
    void pageCursorsUseTheCodecBeanAndTheCursorParameterName() {
        CursorCodec rejecting = new CursorCodec() {
            @Override
            public String encode(String cursor, CursorScope scope) {
                return "token-for-" + scope.operation();
            }

            @Override
            public String decode(String token, CursorScope scope) {
                throw new InvalidCursorException("test");
            }
        };
        routesRunner
                .withUserConfiguration(PagedBooks.class)
                .withBean(CursorCodec.class, () -> rejecting)
                .withPropertyValues("synapse.api.rest.paging.parameters.cursor=after")
                .run(context -> {
                    PageCursors cursors = context.getBean(PageCursors.class);
                    OperationHandler handler = (OperationHandler) Objects
                            .requireNonNull(handlerFor(context, "GET", "/books"));
                    PagedBooksRequest first = new PagedBooksRequest(null, PageQuery.first(20));
                    assertThat(cursors.wrap(handler.route(), first, "raw")).isEqualTo("token-for-paged-books");
                    PagedBooksRequest next = new PagedBooksRequest(null, PageQuery.ofCursor("t", 20, List.of()));
                    assertThatExceptionOfType(SynapseException.class)
                            .isThrownBy(() -> cursors.unwrap(handler.route(), next))
                            .satisfies(e -> assertThat(e.violations())
                                    .containsExactly(new Violation("after", "is not valid for this request")));
                });
    }

    @Test
    void generatedCursorKeyIsWarnedAtStartupOnlyWithPagedRoutes() {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            routesRunner.withUserConfiguration(Books.class).run(context -> assertThat(context).hasNotFailed());
            assertThat(lines.list).noneMatch(line -> line.getFormattedMessage().contains("cursor.key is not set"));

            routesRunner.withUserConfiguration(PagedBooks.class).run(context -> assertThat(context).hasNotFailed());
            assertThat(lines.list)
                    .filteredOn(line -> line.getFormattedMessage().contains("cursor.key is not set"))
                    .singleElement()
                    .satisfies(line -> assertThat(line.getLevel()).isEqualTo(Level.WARN));
        } finally {
            logger.detachAppender(lines);
        }
    }

    @Test
    void invalidCursorKeyFailsStartup() {
        routesRunner
                .withUserConfiguration(Books.class)
                .withPropertyValues("synapse.api.rest.paging.cursor.key=short")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                                // The failure analyzer prints the description and the action.
                                assertThat(failure.description())
                                        .contains("synapse.api.rest.paging.cursor.key")
                                        .doesNotContain("short");
                                assertThat(failure.action())
                                        .contains("synapse.api.rest.paging.cursor.key")
                                        .doesNotContain("short");
                            });
                });
    }

    private static @Nullable HandlerFunction<?> handlerFor(AssertableWebApplicationContext context, String method,
            String path) {
        @SuppressWarnings("unchecked")
        RouterFunction<ServerResponse> routes = context.getBean("synapseRestRoutes", RouterFunction.class);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        return routes.route(ServerRequest.create(request, List.of())).orElse(null);
    }

    private static @Nullable Object fallbackFor(AssertableWebApplicationContext context, String method, String path)
            throws Exception {
        HandlerMapping mapping = context.getBean("synapseRestMethodNotAllowedMapping", HandlerMapping.class);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        ServletRequestPathUtils.parseAndCache(request);
        HandlerExecutionChain chain = mapping.getHandler(request);
        return chain == null ? null : chain.getHandler();
    }

    /** The error auto-configuration's writer, which the concurrency limit filter answers with. */
    private static ProblemWriter problemWriter() {
        StaticMessageSource messages = new StaticMessageSource();
        return new ProblemWriter(new RestErrorStatuses(Map.of(), Map.of()), new ErrorMessages(messages),
                new ProblemTitles(messages), new SynapseErrorProperties(false, null), JsonMapper.builder().build(),
                "X-Correlation-Id");
    }

    private static CorsConfigurationSource corsSource(AssertableWebApplicationContext context) {
        return context.getBean("corsConfigurationSource", CorsConfigurationSource.class);
    }

    private static FilterRegistrationBean<?> inboundRegistration(AssertableWebApplicationContext context) {
        return context.getBean("synapseInboundPropagationFilter", FilterRegistrationBean.class);
    }

    private static FilterRegistrationBean<?> contextRegistration(AssertableWebApplicationContext context) {
        return context.getBean("synapseRestContextFilter", FilterRegistrationBean.class);
    }

    private static FilterRegistrationBean<?> concurrencyRegistration(AssertableWebApplicationContext context) {
        return context.getBean("synapseConcurrencyLimitFilter", FilterRegistrationBean.class);
    }

    private static FilterRegistrationBean<?> sizeRegistration(AssertableWebApplicationContext context) {
        return context.getBean("synapseRequestSizeLimitFilter", FilterRegistrationBean.class);
    }

    private static FilterRegistrationBean<?> clientErrorRegistration(AssertableWebApplicationContext context) {
        return context.getBean("synapseClientErrorFilter", FilterRegistrationBean.class);
    }

    private static FilterRegistrationBean<?> corsRegistration(AssertableWebApplicationContext context) {
        return context.getBean("synapseCorsFilter", FilterRegistrationBean.class);
    }

    /** Sends {@code GET /books/1} from the origin through the CORS filter, to a chain that does nothing. */
    private static MockHttpServletResponse corsFiltered(AssertableWebApplicationContext context, String origin)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/books/1");
        request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Objects.requireNonNull(corsRegistration(context).getFilter()).doFilter(request, response, (in, out) -> {
        });
        return response;
    }

    private static HttpServletRequest filter(AssertableWebApplicationContext context, MockHttpServletRequest request)
            throws Exception {
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        Objects
                .requireNonNull(inboundRegistration(context).getFilter())
                .doFilter(request, new MockHttpServletResponse(),
                        (downstream, response) -> seen.set((HttpServletRequest) downstream));
        return Objects.requireNonNull(seen.get(), "the chain was not called");
    }

    @Configuration(proxyBeanMethods = false)
    static class Books {

        @Bean
        GetPlainBookOperation getPlainBookOperation() {
            return new GetPlainBookOperation();
        }

        @Bean
        RestBindings books(GetPlainBookOperation getBook) {
            return RestBindings.builder().get("/books/{id}", getBook).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PagedBooks {

        @Bean
        PagedBooksOperation pagedBooksOperation() {
            return new PagedBooksOperation();
        }

        @Bean
        RestBindings pagedBooks(PagedBooksOperation pagedBooks) {
            return RestBindings.builder().get("/books", pagedBooks).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BooksWithHead {

        @Bean
        GetPlainBookOperation getPlainBookOperation() {
            return new GetPlainBookOperation();
        }

        @Bean
        GetBookOperation getBookOperation() {
            return new GetBookOperation();
        }

        @Bean
        RestBindings books(GetPlainBookOperation getPlainBook, GetBookOperation getBook) {
            return RestBindings.builder().get("/books/{id}", getPlainBook).head("/books/{id}", getBook).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserRoutes {

        final RouterFunction<ServerResponse> routes = RouterFunctions
                .route()
                .GET("/user", request -> ServerResponse.ok().build())
                .build();

        @Bean
        RouterFunction<ServerResponse> synapseRestRoutes() {
            return routes;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HiddenBindings {

        @Bean
        HiddenOperation hiddenOperation() {
            return new HiddenOperation();
        }

        @Bean
        RestBindings hidden(HiddenOperation hidden) {
            return RestBindings.builder().post("/hidden", hidden).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnboundVariable {

        @Bean
        GetPlainBookOperation getPlainBookOperation() {
            return new GetPlainBookOperation();
        }

        @Bean
        RestBindings books(GetPlainBookOperation getBook) {
            return RestBindings.builder().get("/books/{bookId}", getBook).build();
        }
    }

    public static class BoomOperation implements QueryOperation<Void, String> {
        @Override
        public String execute(Void input) {
            throw new IllegalStateException("secret internals");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Boom {

        @Bean
        BoomOperation boomOperation() {
            return new BoomOperation();
        }

        @Bean
        RestBindings boom(BoomOperation boom) {
            return RestBindings.builder().get("/boom", boom).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserInboundFilter {

        @Bean
        FilterRegistrationBean<CharacterEncodingFilter> synapseInboundPropagationFilter() {
            return new FilterRegistrationBean<>(new CharacterEncodingFilter());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserContextFilter {

        @Bean
        FilterRegistrationBean<CharacterEncodingFilter> synapseRestContextFilter() {
            return new FilterRegistrationBean<>(new CharacterEncodingFilter());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserLimitFilters {

        @Bean
        FilterRegistrationBean<CharacterEncodingFilter> synapseConcurrencyLimitFilter() {
            return new FilterRegistrationBean<>(new CharacterEncodingFilter());
        }

        @Bean
        FilterRegistrationBean<CharacterEncodingFilter> synapseRequestSizeLimitFilter() {
            return new FilterRegistrationBean<>(new CharacterEncodingFilter());
        }
    }

    /** A route with {@code .etag}, and a Spring MVC formatter that prints its version with a prefix. */
    @Configuration(proxyBeanMethods = false)
    static class VersionedBooks implements WebMvcConfigurer {

        @Override
        public void addFormatters(FormatterRegistry registry) {
            registry.addConverter(Long.class, String.class, version -> "v" + version);
        }

        @Bean
        GetVersionedBookOperation getVersionedBookOperation() {
            return new GetVersionedBookOperation();
        }

        @Bean
        RestBindings versionedBooks(GetVersionedBookOperation getBook) {
            return RestBindings.builder().get("/versioned/{id}", getBook).etag(VersionedBook::version).build();
        }
    }

    record VersionedBookId(String id) {
    }

    record VersionedBook(String id, long version) {
    }

    static class GetVersionedBookOperation implements QueryOperation<VersionedBookId, VersionedBook> {
        @Override
        public VersionedBook execute(VersionedBookId input) {
            return new VersionedBook(input.id(), 3);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserCorsFilter {

        @Bean
        FilterRegistrationBean<CharacterEncodingFilter> synapseCorsFilter() {
            return new FilterRegistrationBean<>(new CharacterEncodingFilter());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserClientErrorFilter {

        @Bean
        FilterRegistrationBean<CharacterEncodingFilter> synapseClientErrorFilter() {
            return new FilterRegistrationBean<>(new CharacterEncodingFilter());
        }
    }
}
