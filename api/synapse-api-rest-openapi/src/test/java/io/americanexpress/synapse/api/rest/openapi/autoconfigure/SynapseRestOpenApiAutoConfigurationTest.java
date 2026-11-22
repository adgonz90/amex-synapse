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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.api.OpenApiActuatorResource;
import org.springdoc.webmvc.api.OpenApiResource;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestAutoConfiguration;
import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestErrorAutoConfiguration;
import io.americanexpress.synapse.api.rest.openapi.internal.SynapseOpenApiCustomizer;
import io.americanexpress.synapse.api.rest.openapi.internal.aot.OpenApiAotCheck;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;
import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitch;
import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitch.Mismatch;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@ExtendWith(OutputCaptureExtension.class)
class SynapseRestOpenApiAutoConfigurationTest {

    private static final String CUSTOMIZER = "synapseRestOpenApiCustomizer";

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /** A REST service with springdoc and the OpenAPI auto-configuration. */
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(AopAutoConfiguration.class, ValidationAutoConfiguration.class, JacksonAutoConfiguration.class,
                            HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class,
                            ObservationAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
                            OpenTelemetryTracingAutoConfiguration.class, MicrometerTracingAutoConfiguration.class,
                            SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class,
                            SynapseOperationAutoConfiguration.class, SynapseRestErrorAutoConfiguration.class,
                            SynapseRestAutoConfiguration.class, SpringDocConfiguration.class,
                            SpringDocConfigProperties.class,
                            SpringDocWebMvcConfiguration.class, SynapseRestOpenApiAutoConfiguration.class));

    @Test
    void customizerActivatesWithBindingsAndSpringdoc() {
        runner
                .withUserConfiguration(Books.class)
                .withPropertyValues("synapse.correlation.header=X-Trace-Id",
                        "synapse.api.rest.deadline.header=X-Budget")
                .run(context -> {
                    assertThat(context).hasSingleBean(SynapseOpenApiCustomizer.class);
                    assertThat(context.getBean(CUSTOMIZER)).isInstanceOf(OpenApiCustomizer.class);

                    OpenAPI openApi = new OpenAPI();
                    context.getBean(SynapseOpenApiCustomizer.class).customise(openApi);

                    Operation get = Objects
                            .requireNonNull(Objects.requireNonNull(openApi.getPaths().get("/books/{id}")).getGet());
                    assertThat(get.getOperationId()).isEqualTo("get-book");
                    // The bound properties: the correlation and deadline headers.
                    assertThat(get.getParameters())
                            .extracting(Parameter::getName)
                            .containsExactly("id", "X-Trace-Id", "X-Budget");
                    assertThat(openApi.getComponents().getHeaders()).containsOnlyKeys("X-Trace-Id");
                    assertThat(openApi.getComponents().getSchemas()).containsKeys("Book", "SynapseProblem");
                });
    }

    @Test
    void customizerWithoutAValidatorBeanBuildsItsOwnAndClosesWithTheContext() {
        // Every default factory built while the context starts is a mock this test owns: the customizer's, and the
        // operation pipeline's (which the pipeline keeps).
        List<ValidatorFactory> built = new ArrayList<>();
        AtomicReference<@Nullable ValidatorFactory> owned = new AtomicReference<>();
        try (MockedStatic<Validation> validation = mockStatic(Validation.class, CALLS_REAL_METHODS)) {
            validation.when(Validation::buildDefaultValidatorFactory).thenAnswer(invocation -> {
                ValidatorFactory factory = mock(ValidatorFactory.class);
                when(factory.getValidator()).thenReturn(VALIDATOR);
                built.add(factory);
                return factory;
            });
            fallbackRunner().withUserConfiguration(Books.class).run(context -> {
                assertThat(context).doesNotHaveBean(Validator.class);
                SynapseOpenApiCustomizer customizer = context.getBean(SynapseOpenApiCustomizer.class);
                OpenAPI openApi = new OpenAPI();
                customizer.customise(openApi);
                assertThat(openApi.getPaths()).containsKey("/books/{id}");

                ValidatorFactory factory = (ValidatorFactory) Objects
                        .requireNonNull(ReflectionTestUtils.getField(customizer, "ownedValidators"));
                assertThat(built).contains(factory);
                owned.set(factory);
                // Open while the context runs.
                verify(factory, never()).close();
            });
        }

        // The context is closed: Spring closed the customizer, and the customizer its factory, once.
        ValidatorFactory factory = Objects.requireNonNull(owned.get());
        verify(factory).close();
        // A factory the customizer didn't build is not its to close.
        // Mockito mocks keep Object's identity equals, so this compares identity without Error Prone's
        // ReferenceEquality warning.
        assertThat(built)
                .filteredOn(other -> !other.equals(factory))
                .allSatisfy(other -> verify(other, never()).close());
    }

    @Test
    void aValidatorBeanIsNeverClosedBySynapse() {
        // The application's validator is also a factory, as Spring's LocalValidatorFactoryBean is. The test owns it and
        // tells Spring not to close it (an empty destroy method), so a close() could only come from Synapse.
        ValidatorFactory applications = mock(ValidatorFactory.class, withSettings().extraInterfaces(Validator.class));
        Validator asValidator = (Validator) applications;
        when(asValidator.getConstraintsForClass(any()))
                .thenAnswer(invocation -> VALIDATOR.getConstraintsForClass(invocation.getArgument(0)));

        fallbackRunner()
                .withUserConfiguration(Books.class)
                .withBean("validator", Validator.class, () -> asValidator,
                        definition -> definition.setDestroyMethodName(""))
                .run(context -> {
                    SynapseOpenApiCustomizer customizer = context.getBean(SynapseOpenApiCustomizer.class);
                    assertThat(ReflectionTestUtils.getField(customizer, "ownedValidators")).isNull();
                    customizer.customise(new OpenAPI());
                });

        // The customizer documented the routes with it, and the context closed without closing it.
        verify(asValidator, atLeastOnce()).getConstraintsForClass(any());
        verify(applications, never()).close();
    }

    /** A REST service with the OpenAPI auto-configuration, without Boot's validation auto-configuration. */
    private static WebApplicationContextRunner fallbackRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(AopAutoConfiguration.class, JacksonAutoConfiguration.class,
                                HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class,
                                ObservationAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
                                OpenTelemetryTracingAutoConfiguration.class, MicrometerTracingAutoConfiguration.class,
                                SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class,
                                SynapseOperationAutoConfiguration.class, SynapseRestErrorAutoConfiguration.class,
                                SynapseRestAutoConfiguration.class, SynapseRestOpenApiAutoConfiguration.class));
    }

    @Test
    void customizerBacksOffToAUserBean() {
        runner.withUserConfiguration(Books.class, OwnCustomizer.class).run(context -> {
            assertThat(context).doesNotHaveBean(SynapseOpenApiCustomizer.class);
            assertThat(context.getBean(CUSTOMIZER)).isSameAs(OwnCustomizer.CUSTOMIZER);
        });
    }

    @Test
    void absentWithoutBindingsOrSpringdoc() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CUSTOMIZER);
        });
        // A filtering class loader can't proxy the operation beans, so this context holds the bindings alone: the
        // customizer would fail without the routes bean, so only the missing class keeps it out.
        WebApplicationContextRunner bindingsOnly = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SynapseRestOpenApiAutoConfiguration.class))
                .withBean(RestBindings.class,
                        () -> RestBindings.builder().get("/books/{id}", new GetBookOperation()).build());
        bindingsOnly.run(context -> assertThat(context).hasFailed());
        bindingsOnly.withClassLoader(new FilteredClassLoader(OpenApiCustomizer.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CUSTOMIZER);
        });
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SynapseRestOpenApiAutoConfiguration.class))
                .withUserConfiguration(Books.class)
                .run(context -> assertThat(context).doesNotHaveBean(CUSTOMIZER));
    }

    @Test
    void openApi30FailsStartup() {
        runner
                .withUserConfiguration(Books.class)
                .withPropertyValues("springdoc.api-docs.version=openapi_3_0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    while (failure != null && !(failure instanceof SynapseConfigurationException)) {
                        failure = failure.getCause();
                    }
                    assertThat(failure).isInstanceOf(SynapseConfigurationException.class);
                    SynapseConfigurationException exception = (SynapseConfigurationException) Objects
                            .requireNonNull(failure);
                    assertThat(exception.description())
                            .isEqualTo("springdoc.api-docs.version is openapi_3_0, but Synapse documents its REST "
                                    + "routes in OpenAPI 3.1 (JSON Schema 2020-12)");
                    assertThat(exception.action())
                            .isEqualTo("Remove springdoc.api-docs.version, or set it to openapi_3_1.");
                });
        runner
                .withUserConfiguration(Books.class)
                .withPropertyValues("springdoc.api-docs.version=openapi_3_1")
                .run(context -> assertThat(context).hasSingleBean(SynapseOpenApiCustomizer.class));
    }

    @Test
    void undescribedTypesWarnOnceAtStartup(CapturedOutput output) {
        runner.withUserConfiguration(Opaque.class).run(context -> {
            assertThat(context).hasNotFailed();
            // springdoc documents again on every cache miss; only startup warns.
            context.getBean(SynapseOpenApiCustomizer.class).customise(new OpenAPI());
            context.getBean(SynapseOpenApiCustomizer.class).customise(new OpenAPI());
        });

        String warning = "OpenAPI: no schema for java.lang.Object (GET /opaque (get-opaque)); documented as {}";
        assertThat(output.getAll().lines().filter(line -> line.contains(warning)))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("WARN"));
    }

    @Test
    void withTheDocumentOffTheRoutesAreStillDocumentedAtStartup(CapturedOutput output) {
        // Synapse's default: springdoc's own beans, its properties included, are then absent.
        runner
                .withUserConfiguration(Opaque.class)
                .withPropertyValues("springdoc.api-docs.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SynapseOpenApiCustomizer.class);
                    assertThat(context).doesNotHaveBean(SpringDocConfigProperties.class);
                });

        assertThat(output.getAll().lines().filter(line -> line.contains(OPAQUE_WARNING))).hasSize(1);
    }

    @Test
    void withTheDocumentOffThePathFiltersStillApply(CapturedOutput output) {
        // Read from the environment, as springdoc would bind them: an excluded route is neither documented nor
        // reported.
        runner
                .withUserConfiguration(Opaque.class)
                .withPropertyValues("springdoc.api-docs.enabled=false", "springdoc.paths-to-exclude=/opaque")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(SynapseOpenApiCustomizer.class));
        runner
                .withUserConfiguration(Opaque.class)
                .withPropertyValues("springdoc.api-docs.enabled=false", "springdoc.paths-to-match[0]=/books/**")
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(output.getAll()).doesNotContain(OPAQUE_WARNING);
    }

    private static final String OPAQUE_WARNING = "OpenAPI: no schema for java.lang.Object (GET /opaque (get-opaque))";

    record GetBook(UUID id) {
    }

    record Book(UUID id, String title) {
    }

    record Holder(Object value) {
    }

    static class GetBookOperation implements QueryOperation<GetBook, Book> {

        @Override
        public Book execute(GetBook input) {
            return new Book(input.id(), "Dune");
        }
    }

    static class GetOpaqueOperation implements QueryOperation<Void, Holder> {

        @Override
        public Holder execute(Void input) {
            return new Holder("anything");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Books {

        @Bean
        GetBookOperation getBookOperation() {
            return new GetBookOperation();
        }

        @Bean
        RestBindings books(GetBookOperation getBook) {
            return RestBindings.builder().get("/books/{id}", getBook).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Opaque {

        @Bean
        GetOpaqueOperation getOpaqueOperation() {
            return new GetOpaqueOperation();
        }

        @Bean
        RestBindings opaque(GetOpaqueOperation getOpaque) {
            return RestBindings.builder().get("/opaque", getOpaque).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnCustomizer {

        static final OpenApiCustomizer CUSTOMIZER = openApi -> openApi.addExtension("x-own", true);

        @Bean
        OpenApiCustomizer synapseRestOpenApiCustomizer() {
            return CUSTOMIZER;
        }
    }

    @Test
    void aotSwitchIsDefinedWithSpringdocAndBacksOffToAUserBean() {
        String aotCheck = "synapseOpenApiAotCheck";
        runner
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .getBean(aotCheck)
                        .isInstanceOf(OpenApiAotCheck.class));
        // Defined with the document off too: it is the one place that compares the switch with the build.
        runner
                .withPropertyValues("springdoc.api-docs.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().hasBean(aotCheck));
        BuildTimeSwitch user = () -> null;
        runner.withBean(aotCheck, BuildTimeSwitch.class, () -> user).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(aotCheck)).isSameAs(user);
        });
        runner
                .withClassLoader(new FilteredClassLoader(OpenApiCustomizer.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(aotCheck));
    }
    /**
     * The switch as an AOT run sees it: the bean definitions are the build's (the context), and the property is the
     * run's.
     */
    private static BuildTimeSwitch switchAtRun(ConfigurableApplicationContext build, String apiDocsEnabled) {
        MockEnvironment run = new MockEnvironment().withProperty("springdoc.api-docs.enabled", apiDocsEnabled);
        return new OpenApiAotCheck(run, build.getBeanFactory());
    }

    /**
     * springdoc serves the document on the management port: its actuator endpoint, not its default one. The actuator
     * endpoint needs springdoc's package-private server-ports auto-configuration, loaded by name as its
     * {@code AutoConfiguration.imports} lists it.
     */
    private WebApplicationContextRunner onTheManagementPort() throws ClassNotFoundException {
        Class<?> serverPorts = Class
                .forName(
                        "org.springdoc.core.configuration.SpringDocWebServerConfiguration$EmbeddedWebServerConfiguration");
        return runner
                .withConfiguration(AutoConfigurations.of(serverPorts))
                .withPropertyValues("springdoc.use-management-port=true", "management.server.port=9090");
    }

    @Test
    void aotSwitchSeesNoMismatchWithTheDocumentOnTheManagementPortInBuildAndRun() throws Exception {
        onTheManagementPort().withPropertyValues("springdoc.api-docs.enabled=true").run(build -> {
            assertThat(build)
                    .hasNotFailed()
                    .hasSingleBean(OpenApiActuatorResource.class)
                    .doesNotHaveBean(OpenApiWebMvcResource.class);
            assertThat(switchAtRun(build, "true").mismatch()).isNull();
        });
    }

    @Test
    void aotSwitchReportsTheDocumentOnInTheBuildAndOffInTheRunOnTheManagementPort() throws Exception {
        onTheManagementPort().withPropertyValues("springdoc.api-docs.enabled=true").run(build -> {
            assertThat(build).hasNotFailed().hasSingleBean(OpenApiActuatorResource.class);
            assertThat(switchAtRun(build, "false").mismatch())
                    .isEqualTo(new Mismatch("springdoc.api-docs.enabled", "false", "true"));
        });
    }

    @Test
    void aotSwitchReportsTheDocumentOffInTheBuildAndOnInTheRunOnTheManagementPort() throws Exception {
        onTheManagementPort().withPropertyValues("springdoc.api-docs.enabled=false").run(build -> {
            assertThat(build).hasNotFailed().doesNotHaveBean(OpenApiResource.class);
            assertThat(switchAtRun(build, "true").mismatch())
                    .isEqualTo(new Mismatch("springdoc.api-docs.enabled", "true", "false"));
        });
    }

    @Test
    void aotSwitchSeesNoMismatchWithTheDefaultEndpointOffInBuildAndRun() {
        // springdoc.enable-default-api-docs=false drops springdoc's default endpoint, not the document's switch.
        runner
                .withPropertyValues("springdoc.api-docs.enabled=true", "springdoc.enable-default-api-docs=false")
                .run(build -> {
                    assertThat(build).hasNotFailed().doesNotHaveBean(OpenApiWebMvcResource.class);
                    assertThat(switchAtRun(build, "true").mismatch()).isNull();
                });
    }
}
