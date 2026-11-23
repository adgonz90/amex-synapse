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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetPlainBookOperation;
import io.americanexpress.synapse.api.rest.internal.error.BookErrors;
import io.americanexpress.synapse.api.rest.internal.error.ProblemTitles;
import io.americanexpress.synapse.api.rest.internal.error.ProblemWriter;
import io.americanexpress.synapse.api.rest.internal.error.RestErrorStatuses;
import io.americanexpress.synapse.api.rest.internal.error.SynapseErrorController;
import io.americanexpress.synapse.api.rest.internal.error.SynapseRestExceptionResolver;
import io.americanexpress.synapse.api.rest.internal.error.SynapseRestExceptionResolverConfigurer;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseRestErrorAutoConfigurationTest {

    private static final String CONFIGURER = "synapseRestExceptionResolverConfigurer";

    private static final String PROBLEM_DETAILS_WARNING = "spring.mvc.problemdetails.enabled is true: Spring Boot's "
            + "ProblemDetailsExceptionHandler answers Spring MVC's exceptions from your controllers, and the 404 of an "
            + "unknown path that no Synapse route claims (one under a route's literal path prefix, or a route's own "
            + "template when it has no prefix), in Spring's problem shape instead of Synapse's (no code or "
            + "correlationId). Synapse routes, and the unknown paths they claim, under any version for a versioned "
            + "route, are not affected.";

    /**
     * The auto-configurations of a REST service, as {@code SynapseRestAutoConfigurationTest} wires them: Spring MVC
     * with its message converters, Jackson, observation and OpenTelemetry tracing, the operation pipeline and both
     * REST auto-configurations.
     */
    private final WebApplicationContextRunner routesRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(AopAutoConfiguration.class, ValidationAutoConfiguration.class, JacksonAutoConfiguration.class,
                            HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class,
                            ObservationAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
                            OpenTelemetryTracingAutoConfiguration.class, MicrometerTracingAutoConfiguration.class,
                            SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class,
                            SynapseOperationAutoConfiguration.class, SynapseRestErrorAutoConfiguration.class,
                            SynapseRestAutoConfiguration.class, ErrorMvcAutoConfiguration.class,
                            DispatcherServletAutoConfiguration.class));

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(AopAutoConfiguration.class, ValidationAutoConfiguration.class,
                            MessageSourceAutoConfiguration.class, JacksonAutoConfiguration.class,
                            ObservationAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
                            OpenTelemetryTracingAutoConfiguration.class, NoopTracerAutoConfiguration.class,
                            MicrometerTracingAutoConfiguration.class, SynapseCoreAutoConfiguration.class,
                            SynapseContextAutoConfiguration.class, SynapseOperationAutoConfiguration.class,
                            SynapseRestAutoConfiguration.class, SynapseRestErrorAutoConfiguration.class,
                            ErrorMvcAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
                            WebMvcAutoConfiguration.class));

    @Test
    void activatesInServletWebApplication() {
        webRunner
                .run(context -> assertThat(context)
                        .hasSingleBean(ProblemTitles.class)
                        .hasSingleBean(RestErrorStatuses.class)
                        .hasSingleBean(ProblemWriter.class)
                        .hasSingleBean(SynapseRestExceptionResolverConfigurer.class)
                        .hasBean(CONFIGURER)
                        .hasSingleBean(SynapseErrorController.class));
    }

    @Test
    void absentInNonWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(SynapseCoreAutoConfiguration.class, SynapseRestErrorAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ProblemWriter.class)
                        .doesNotHaveBean(CONFIGURER)
                        .doesNotHaveBean(SynapseErrorController.class));
    }

    @Test
    void absentInReactiveWebApplication() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(SynapseCoreAutoConfiguration.class, SynapseRestErrorAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ProblemWriter.class)
                        .doesNotHaveBean(CONFIGURER));
    }

    @Test
    void absentWithoutRouterFunctionOnTheClasspath() {
        webRunner
                .withClassLoader(new FilteredClassLoader(RouterFunction.class))
                .run(context -> assertThat(context).doesNotHaveBean(ProblemWriter.class).doesNotHaveBean(CONFIGURER));
    }

    /**
     * Spring MVC's composite holds both Synapse instances: the one for Synapse paths first, the main one right after
     * the {@code ExceptionHandlerExceptionResolver}. Boot's {@code DefaultErrorAttributes} runs before the composite.
     * Neither instance is a bean of its own.
     */
    @Test
    void resolversRunInTheDecidedOrder() {
        webRunner.withUserConfiguration(Books.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(SynapseRestExceptionResolver.class)).isEmpty();
            List<HandlerExceptionResolver> beans = new ArrayList<>(
                    context.getBeansOfType(HandlerExceptionResolver.class).values());
            AnnotationAwareOrderComparator.sort(beans);
            assertThat(beans).hasSize(2);
            assertThat(beans.get(0)).isInstanceOf(DefaultErrorAttributes.class);
            assertThat(beans.get(1)).isSameAs(context.getBean("handlerExceptionResolver"));

            List<HandlerExceptionResolver> composite = composite(context);
            assertThat(composite).hasSize(5);
            assertThat(isPathsInstance(composite.get(0))).isTrue();
            assertThat(composite.get(1)).isInstanceOf(ExceptionHandlerExceptionResolver.class);
            assertThat(isMainInstance(composite.get(2))).isTrue();
            assertThat(composite.get(3)).isInstanceOf(ResponseStatusExceptionResolver.class);
            assertThat(composite.get(4)).isInstanceOf(DefaultHandlerExceptionResolver.class);
        });
    }

    /**
     * A bean named {@code synapseRestExceptionResolverConfigurer} replaces Synapse's, whatever its type; a
     * configurer of the same type under another name doesn't.
     */
    @Test
    void configurerBacksOffByName() {
        webRunner.withBean(CONFIGURER, WebMvcConfigurer.class, () -> new WebMvcConfigurer() {
        }).run(context -> {
            assertThat(context).doesNotHaveBean(SynapseRestExceptionResolverConfigurer.class);
            assertThat(composite(context)).noneMatch(SynapseRestExceptionResolver.class::isInstance);
        });
        webRunner
                .withBean("userConfigurer", SynapseRestExceptionResolverConfigurer.class,
                        () -> new SynapseRestExceptionResolverConfigurer(UserWriter.writer(), emptyRoutes()))
                .run(context -> {
                    assertThat(context.getBeansOfType(SynapseRestExceptionResolverConfigurer.class))
                            .containsOnlyKeys(CONFIGURER, "userConfigurer");
                });
    }

    @Test
    void withoutRoutesThePathsInstanceMatchesNothing() {
        webRunner.run(context -> {
            assertThat(context).doesNotHaveBean("synapseRestRoutes");
            HandlerExceptionResolver paths = composite(context).getFirst();
            for (String path : List.of("/", "/books/7", "/error")) {
                assertThat(paths
                        .resolveException(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), null,
                                new IllegalStateException("x")))
                        .as(path)
                        .isNull();
            }
        });
    }

    /**
     * The configurer is a {@code WebMvcConfigurer}, which Spring MVC's configuration injects while it is created, and
     * the routes need that configuration's conversion service. Reading the routes lazily keeps the context free of a
     * bean cycle (review N1).
     */
    @Test
    void contextWithRealMvcAndARouteStarts() {
        routesRunner.withUserConfiguration(Books.class).run(context -> {
            assertThat(context).hasNotFailed().hasBean("synapseRestRoutes");
            HandlerExceptionResolver paths = composite(context).getFirst();
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(paths
                    .resolveException(new MockHttpServletRequest("DELETE", "/books/7"), response, null,
                            new IllegalStateException("x")))
                    .isNotNull();
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(paths
                    .resolveException(new MockHttpServletRequest("GET", "/shelves/7"), new MockHttpServletResponse(),
                            null, new IllegalStateException("x")))
                    .isNull();
        });
    }

    /** Only the bean named {@code synapseRestRoutes} is read: another router function of the application is ignored. */
    @Test
    void pathsComeFromTheRoutesBeanOnly() {
        routesRunner
                .withUserConfiguration(Books.class)
                .withBean("userRoutes", RouterFunction.class,
                        () -> RouterFunctions.route().GET("/user", request -> ServerResponse.ok().build()).build())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HandlerExceptionResolver paths = composite(context).getFirst();
                    assertThat(paths
                            .resolveException(new MockHttpServletRequest("GET", "/books/7"),
                                    new MockHttpServletResponse(), null, new IllegalStateException("x")))
                            .isNotNull();
                    assertThat(paths
                            .resolveException(new MockHttpServletRequest("GET", "/user"), new MockHttpServletResponse(),
                                    null, new IllegalStateException("x")))
                            .isNull();
                });
    }

    @Test
    void problemDetailsEnabledWarns() {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            webRunner
                    .withPropertyValues("spring.mvc.problemdetails.enabled=true")
                    .run(context -> assertThat(context).hasNotFailed());
            assertThat(lines.list)
                    .filteredOn(line -> line.getFormattedMessage().startsWith("spring.mvc.problemdetails.enabled"))
                    .singleElement()
                    .satisfies(line -> {
                        assertThat(line.getLevel()).isEqualTo(Level.WARN);
                        assertThat(line.getFormattedMessage()).isEqualTo(PROBLEM_DETAILS_WARNING);
                    });

            lines.list.clear();
            webRunner.run(context -> assertThat(context).hasNotFailed());
            webRunner
                    .withPropertyValues("spring.mvc.problemdetails.enabled=false")
                    .run(context -> assertThat(context).hasNotFailed());
            assertThat(lines.list)
                    .noneMatch(line -> line.getFormattedMessage().startsWith("spring.mvc.problemdetails.enabled"));
        } finally {
            logger.detachAppender(lines);
        }
    }

    @Test
    void replacesBootErrorController() {
        webRunner.run(context -> {
            assertThat(context.getBeansOfType(ErrorController.class)).hasSize(1);
            assertThat(context).hasSingleBean(SynapseErrorController.class);
        });
    }

    @Test
    void backsOffToUserController() {
        webRunner.withUserConfiguration(UserBeans.class).run(context -> {
            assertThat(context).doesNotHaveBean(SynapseErrorController.class);
            assertThat(context.getBeansOfType(ErrorController.class)).hasSize(1);
        });
    }

    @Test
    void statusOverridesComeFromProperties() {
        webRunner
                .withPropertyValues("synapse.api.rest.errors.status.[BOOK_NOT_FOUND]=423",
                        "synapse.api.rest.operations.get-book.errors.status.[BOOK_NOT_FOUND]=410")
                .run(context -> {
                    RestErrorStatuses statuses = context.getBean(RestErrorStatuses.class);
                    assertThat(statuses.statusFor(BookErrors.BOOK_NOT_FOUND, "get-book")).isEqualTo(410);
                    assertThat(statuses.statusFor(BookErrors.BOOK_NOT_FOUND, "other")).isEqualTo(423);
                });
    }

    @Test
    void invalidStatusOverrideFailsStartup() {
        webRunner
                .withPropertyValues("synapse.api.rest.errors.status.[BOOK_NOT_FOUND]=200")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(SynapseConfigurationException.class));
    }

    @Test
    void backsOffToUserWriter() {
        webRunner
                .withUserConfiguration(UserWriter.class)
                .run(context -> assertThat(context.getBean(ProblemWriter.class))
                        .isSameAs(context.getBean(UserWriter.class).writer));
    }

    /**
     * An application aspect on every {@code @Controller} matches the error controller too: Spring proxies it by
     * subclassing (Boot's {@code spring.aop.proxy-target-class=true}), and the proxy still answers the error dispatch.
     */
    @Test
    void aControllerAspectDoesNotBreakStartup() {
        webRunner
                .withUserConfiguration(ControllerAspect.class)
                .withPropertyValues("spring.aop.proxy-target-class=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SynapseErrorController.class);
                    SynapseErrorController controller = context.getBean(SynapseErrorController.class);
                    assertThat(AopUtils.isCglibProxy(controller)).isTrue();
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
                    request.setDispatcherType(DispatcherType.ERROR);
                    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
                    MockHttpServletResponse response = new MockHttpServletResponse();

                    controller.error(request, response);

                    assertThat(context.getBean(ControllerAspect.class).advised).hasValue(1);
                    assertThat(response.getStatus()).isEqualTo(404);
                    assertThat(response.getContentType()).startsWith("application/problem+json");
                    assertThat(response.getContentAsString()).contains("\"code\":\"ROUTE_NOT_FOUND\"");
                });
    }

    /** Spring MVC maps the error path to the proxied controller: an error dispatch goes through the aspect. */
    @Test
    void theErrorDispatchReachesTheProxiedController() {
        webRunner
                .withUserConfiguration(ControllerAspect.class)
                .withPropertyValues("spring.aop.proxy-target-class=true")
                .run(context -> {
                    assertThat(AopUtils.isCglibProxy(context.getBean(SynapseErrorController.class))).isTrue();
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

                    MockHttpServletResponse response = mvc
                            .perform(MockMvcRequestBuilders.get("/error").with(request -> {
                                request.setDispatcherType(DispatcherType.ERROR);
                                request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
                                return request;
                            }))
                            .andReturn()
                            .getResponse();

                    assertThat(context.getBean(ControllerAspect.class).advised).hasValue(1);
                    assertThat(response.getStatus()).isEqualTo(404);
                    assertThat(response.getContentType()).startsWith("application/problem+json");
                    assertThat(response.getContentAsString()).contains("\"code\":\"ROUTE_NOT_FOUND\"");
                });
    }

    /**
     * Counts the calls to every {@code @Controller}, as an application's {@code @Around("@within(Controller)")} aspect
     * would: an advisor on the class annotation, applied by Boot's auto-proxy creator.
     */
    @Configuration(proxyBeanMethods = false)
    static class ControllerAspect {

        final AtomicInteger advised = new AtomicInteger();

        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        Advisor countingControllerAdvisor() {
            return new DefaultPointcutAdvisor(AnnotationMatchingPointcut.forClassAnnotation(Controller.class),
                    (MethodInterceptor) call -> {
                        advised.incrementAndGet();
                        return call.proceed();
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserBeans {

        @Bean
        UserController userController() {
            return new UserController();
        }
    }

    static class UserController implements ErrorController {
    }

    @Configuration(proxyBeanMethods = false)
    static class UserWriter {

        ProblemWriter writer = writer();

        static ProblemWriter writer() {
            return new ProblemWriter(new RestErrorStatuses(Map.of(), Map.of()),
                    new io.americanexpress.synapse.core.error.ErrorMessages(
                            new org.springframework.context.support.StaticMessageSource()),
                    new ProblemTitles(new org.springframework.context.support.StaticMessageSource()),
                    new io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties(false, null),
                    tools.jackson.databind.json.JsonMapper.builder().build(), "X-Correlation-Id");
        }

        @Bean
        ProblemWriter userWriter() {
            return writer;
        }
    }

    /** One route, {@code GET /books/{id}}. */
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

    private static List<HandlerExceptionResolver> composite(AssertableWebApplicationContext context) {
        return context
                .getBean("handlerExceptionResolver", HandlerExceptionResolverComposite.class)
                .getExceptionResolvers();
    }

    /** Answers a pre-handler error on the route's path, and ignores a controller's exception there. */
    private static boolean isPathsInstance(HandlerExceptionResolver resolver) {
        return resolver instanceof SynapseRestExceptionResolver
                && resolver
                        .resolveException(new MockHttpServletRequest("GET", "/books/7"),
                                new MockHttpServletResponse(), null, new IllegalStateException("x")) != null
                && resolver
                        .resolveException(new MockHttpServletRequest("GET", "/books/7"),
                                new MockHttpServletResponse(), controller(), new IllegalStateException("x")) == null;
    }

    /** Answers a controller's exception on any path. */
    private static boolean isMainInstance(HandlerExceptionResolver resolver) {
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
    private static ObjectProvider<RouterFunction<?>> emptyRoutes() {
        return (ObjectProvider<RouterFunction<?>>) (ObjectProvider<?>) new StaticListableBeanFactory()
                .getBeanProvider(ResolvableType.forClass(RouterFunction.class));
    }
}
