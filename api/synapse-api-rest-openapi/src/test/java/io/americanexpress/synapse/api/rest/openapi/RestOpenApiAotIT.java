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
package io.americanexpress.synapse.api.rest.openapi;

import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.aot.AotDetector;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.SpringProperties;
import org.springframework.core.test.tools.CompileWithForkedClassLoader;
import org.springframework.core.test.tools.Compiled;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.javapoet.ClassName;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestAutoConfiguration;
import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestErrorAutoConfiguration;
import io.americanexpress.synapse.api.rest.openapi.autoconfigure.SynapseRestOpenApiAutoConfiguration;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Runs Spring's AOT processing on a REST service with springdoc and the OpenAPI auto-configuration, compiles the
 * generated code and serves {@code /v3/api-docs} from it, as a native image or a JVM with
 * {@code -Dspring.aot.enabled=true} would.
 */
// Generated code calls package-private members, so it must be defined in the same class loader as the test classes.
@CompileWithForkedClassLoader
class RestOpenApiAotIT {

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
                            SpringDocWebMvcConfiguration.class, SynapseRestOpenApiAutoConfiguration.class))
            .withUserConfiguration(BooksApi.Routes.class);

    @Test
    void generatedContextServesApiDocs() {
        // Started in AOT mode, as the build left it: the switch agrees with the build, so startup passes.
        withGeneratedContext(generated -> {
            try (GenericWebApplicationContext aotContext = generated.start()) {
                MockMvc mvc = MockMvcBuilders.webAppContextSetup(aotContext).build();

                MockHttpServletResponse response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();

                assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
                JsonNode docs = JsonMapper.builder().build().readTree(response.getContentAsByteArray());
                assertThat(docs.get("openapi").asString()).startsWith("3.1");
                assertThat(docs.at("/paths/~1books~1{id}/get/operationId").asString()).startsWith("get-book");
                assertThat(docs.at("/components/schemas/Book/properties").has("authorName")).isTrue();
            }
        });
    }

    @Test
    void startedWithTheApiDocsOffInAotModeFailsNamingTheKey() {
        // The build defined springdoc's document endpoint (springdoc.api-docs.enabled unset is on). An environment
        // file that turns it off changes nothing in the generated code, so startup must say so.
        withGeneratedContext(generated -> assertThatThrownBy(
                () -> generated.start("springdoc.api-docs.enabled=false").close())
                .isInstanceOfSatisfying(SynapseConfigurationException.class,
                        failure -> assertThat(failure.description())
                                .isEqualTo("These properties are fixed at build time in a native image or AOT run,"
                                        + " and differ from the build: springdoc.api-docs.enabled is false but the"
                                        + " build used true.")));
    }

    @Test
    void builtWithTheApiDocsOffAndStartedWithThemOnInAotModeFailsNamingTheKey() {
        // The reverse: the build ran with the document endpoint off, so the generated code has no springdoc
        // endpoint. A run that leaves the property unset (on) would silently serve no document.
        withGeneratedContext(runner.withPropertyValues("springdoc.api-docs.enabled=false"),
                generated -> assertThatThrownBy(() -> generated.start().close())
                        .isInstanceOfSatisfying(SynapseConfigurationException.class,
                                failure -> assertThat(failure.description())
                                        .isEqualTo("These properties are fixed at build time in a native image or "
                                                + "AOT run, and differ from the build: springdoc.api-docs.enabled is "
                                                + "true but the build used false.")));
    }

    @Test
    void startedWithTheApiDocsOffInJvmModeIsNotChecked() {
        // Outside AOT mode springdoc's conditions run at startup, so the property decides and nothing is compared.
        runner
                .withPropertyValues("springdoc.api-docs.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** What a test does with the generated application. */
    @FunctionalInterface
    private interface GeneratedAction {

        void accept(Generated generated) throws Exception;
    }

    /**
     * Runs Spring's AOT processing of the context, compiles the generated code and hands it to the action.
     *
     * @param action what to do with the generated application
     */
    private void withGeneratedContext(GeneratedAction action) {
        withGeneratedContext(runner, action);
    }

    /**
     * Runs Spring's AOT processing of the runner's context, compiles the generated code and hands it to the action.
     *
     * @param build the context as the build processes it
     * @param action what to do with the generated application
     */
    private static void withGeneratedContext(WebApplicationContextRunner build, GeneratedAction action) {
        build.prepare(context -> {
            TestGenerationContext generationContext = new TestGenerationContext();
            ClassName initializerName = new ApplicationContextAotGenerator()
                    .processAheadOfTime((GenericApplicationContext) context.getSourceApplicationContext(),
                            generationContext);
            generationContext.writeGeneratedContent();

            TestCompiler.forSystem().with(generationContext).compile(compiled -> {
                try {
                    action.accept(new Generated(compiled, initializerName));
                } catch (Exception ex) {
                    throw new AssertionError("serving /v3/api-docs failed", ex);
                }
            });
        });
    }

    /**
     * The generated application: its compiled initializer.
     *
     * @param compiled the compiled generated code
     * @param initializer the name of the generated context initializer
     */
    private record Generated(Compiled compiled, ClassName initializer) {

        /**
         * Starts the generated context in AOT mode ({@code spring.aot.enabled}, restored afterwards), as a JVM with
         * {@code -Dspring.aot.enabled=true} would, so the AOT-only checks run.
         *
         * @param properties properties of this run
         * @return the refreshed context, which the caller closes
         */
        GenericWebApplicationContext start(String... properties) {
            GenericWebApplicationContext context = new GenericWebApplicationContext(new MockServletContext());
            TestPropertyValues.of(properties).applyTo(context);
            @SuppressWarnings("unchecked")
            ApplicationContextInitializer<GenericApplicationContext> generated = compiled
                    .getInstance(ApplicationContextInitializer.class, initializer.toString());
            generated.initialize(context);
            String previous = SpringProperties.getProperty(AotDetector.AOT_ENABLED);
            SpringProperties.setProperty(AotDetector.AOT_ENABLED, "true");
            try {
                context.refresh();
                return context;
            } catch (RuntimeException ex) {
                context.close();
                throw ex;
            } finally {
                SpringProperties.setProperty(AotDetector.AOT_ENABLED, previous);
            }
        }
    }
}
