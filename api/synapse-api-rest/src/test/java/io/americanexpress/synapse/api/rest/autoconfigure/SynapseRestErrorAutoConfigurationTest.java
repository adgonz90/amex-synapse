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

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.function.RouterFunction;

import io.americanexpress.synapse.api.rest.internal.error.BookErrors;
import io.americanexpress.synapse.api.rest.internal.error.ProblemTitles;
import io.americanexpress.synapse.api.rest.internal.error.ProblemWriter;
import io.americanexpress.synapse.api.rest.internal.error.RestErrorStatuses;
import io.americanexpress.synapse.api.rest.internal.error.SynapseErrorController;
import io.americanexpress.synapse.api.rest.internal.error.SynapseRestExceptionResolver;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseRestErrorAutoConfigurationTest {

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
                        .hasSingleBean(SynapseRestExceptionResolver.class)
                        .hasSingleBean(SynapseErrorController.class));
    }

    @Test
    void absentInNonWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(SynapseCoreAutoConfiguration.class, SynapseRestErrorAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ProblemWriter.class)
                        .doesNotHaveBean(SynapseErrorController.class));
    }

    @Test
    void absentWithoutRouterFunctionOnTheClasspath() {
        webRunner
                .withClassLoader(new FilteredClassLoader(RouterFunction.class))
                .run(context -> assertThat(context).doesNotHaveBean(ProblemWriter.class));
    }

    @Test
    void replacesBootErrorController() {
        webRunner.run(context -> {
            assertThat(context.getBeansOfType(ErrorController.class)).hasSize(1);
            assertThat(context).hasSingleBean(SynapseErrorController.class);
        });
    }

    @Test
    void backsOffToUserResolverAndController() {
        webRunner.withUserConfiguration(UserBeans.class).run(context -> {
            assertThat(context).hasSingleBean(SynapseRestExceptionResolver.class);
            assertThat(context.getBean(SynapseRestExceptionResolver.class))
                    .isSameAs(context.getBean(UserBeans.class).resolver);
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

        SynapseRestExceptionResolver resolver = new SynapseRestExceptionResolver(
                new ProblemWriter(new RestErrorStatuses(Map.of(), Map.of()),
                        new io.americanexpress.synapse.core.error.ErrorMessages(
                                new org.springframework.context.support.StaticMessageSource()),
                        new ProblemTitles(new org.springframework.context.support.StaticMessageSource()),
                        new io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties(false, null),
                        tools.jackson.databind.json.JsonMapper.builder().build(), "X-Correlation-Id"));

        @Bean
        SynapseRestExceptionResolver userResolver() {
            return resolver;
        }

        @Bean
        UserController userController() {
            return new UserController();
        }
    }

    static class UserController implements ErrorController {
    }

    @Configuration(proxyBeanMethods = false)
    static class UserWriter {

        ProblemWriter writer = new ProblemWriter(new RestErrorStatuses(Map.of(), Map.of()),
                new io.americanexpress.synapse.core.error.ErrorMessages(
                        new org.springframework.context.support.StaticMessageSource()),
                new ProblemTitles(new org.springframework.context.support.StaticMessageSource()),
                new io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties(false, null),
                tools.jackson.databind.json.JsonMapper.builder().build(), "X-Correlation-Id");

        @Bean
        ProblemWriter userWriter() {
            return writer;
        }
    }
}
