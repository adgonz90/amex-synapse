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
package io.americanexpress.synapse.api.grpc.internal.aot;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.aot.AotDetector;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.ReflectionHintsPredicates;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerObservationAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerServicesAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.SpringProperties;
import org.springframework.core.test.tools.CompileWithForkedClassLoader;
import org.springframework.core.test.tools.Compiled;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.javapoet.ClassName;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.autoconfigure.SynapseGrpcAutoConfiguration;
import io.americanexpress.synapse.api.grpc.autoconfigure.SynapseGrpcErrorAutoConfiguration;
import io.americanexpress.synapse.api.grpc.fixture.proto.Author;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs Spring's AOT processing on an application with the core and gRPC auto-configurations and one
 * {@link GrpcBindings} bean, compiles the generated code and starts it, as a native image or a JVM with
 * {@code -Dspring.aot.enabled=true} would, then calls the bound method on the in-process server.
 */
// Generated code calls package-private members, so it must be defined in the same class loader as the test classes.
@CompileWithForkedClassLoader
class GrpcAotProcessingTest {

    private static final String SERVER = "synapse-grpc-aot-processing";

    private static final String[] PROPERTIES = {"spring.grpc.server.inprocess.name=" + SERVER,
            "spring.grpc.server.port=0"};

    private static final Metadata.Key<String> CORRELATION = Metadata.Key
            .of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    private static final ReflectionHintsPredicates REFLECTION = RuntimeHintsPredicates.reflection();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(AopAutoConfiguration.class, ValidationAutoConfiguration.class,
                            MessageSourceAutoConfiguration.class, SslAutoConfiguration.class,
                            ObservationAutoConfiguration.class,
                            OpenTelemetrySdkAutoConfiguration.class, OpenTelemetryTracingAutoConfiguration.class,
                            MicrometerTracingAutoConfiguration.class, SynapseCoreAutoConfiguration.class,
                            SynapseContextAutoConfiguration.class, SynapseOperationAutoConfiguration.class,
                            SynapseGrpcAutoConfiguration.class, SynapseGrpcErrorAutoConfiguration.class,
                            GrpcServerServicesAutoConfiguration.class, GrpcServerObservationAutoConfiguration.class,
                            GrpcServerAutoConfiguration.class))
            .withPropertyValues(PROPERTIES)
            .withUserConfiguration(Bindings.class);

    @Test
    void generatedContextServesTheMethod() {
        generate(generated -> {
            RuntimeHints hints = generated.hints();
            // The gRPC plugin's own hints.
            assertThat(hints.reflection().lambdaHints())
                    .anySatisfy(lambda -> assertThat(lambda.getDeclaringClass())
                            .isEqualTo(TypeReference.of(Bindings.class)));
            // The mapping builds records with their canonical constructors and reads them with their accessors. The
            // core's operation type hints reach the input, the output, the record nested in the output and the
            // component a .map path names.
            assertThat(REFLECTION.onConstructorInvocation(ItemKey.class.getDeclaredConstructor(String.class)))
                    .accepts(hints);
            assertThat(REFLECTION.onMethodInvocation(ItemKey.class, "id")).accepts(hints);
            assertThat(REFLECTION
                    .onConstructorInvocation(
                            ItemView.class.getDeclaredConstructor(String.class, String.class, AuthorView.class)))
                    .accepts(hints);
            assertThat(REFLECTION.onMethodInvocation(ItemView.class, "title")).accepts(hints);
            assertThat(REFLECTION.onMethodInvocation(ItemView.class, "author")).accepts(hints);
            assertThat(REFLECTION
                    .onConstructorInvocation(AuthorView.class.getDeclaredConstructor(String.class, String.class)))
                    .accepts(hints);
            assertThat(REFLECTION.onMethodInvocation(AuthorView.class, "name")).accepts(hints);
            assertThat(REFLECTION.onMethodInvocation(AuthorView.class, "countryCode")).accepts(hints);

            // The build ran with reflection on (unset), and so does this run: the reflection check passes.
            try (GenericApplicationContext aotContext = generated.start()) {
                assertThat(aotContext.containsBean("grpcServerReflectionService")).isTrue();
                AtomicReference<@Nullable Metadata> headers = new AtomicReference<>();
                AtomicReference<@Nullable Metadata> trailers = new AtomicReference<>();
                Item item = getItem("1", "c-aot", headers, trailers);

                assertThat(item.getId()).isEqualTo("1");
                assertThat(item.getName()).isEqualTo("Dune");
                assertThat(item.getAuthor())
                        .isEqualTo(Author.newBuilder().setName("Frank Herbert").setCountryCode("US").build());
                assertThat(Objects.requireNonNull(headers.get(), "response headers").get(CORRELATION))
                        .isEqualTo("c-aot");
            }
        });
    }

    @Test
    void generatedContextFailsWhenReflectionDiffersFromTheBuild() {
        generate(generated -> assertThatThrownBy(() -> generated.start("spring.grpc.server.reflection.enabled=false"))
                .isInstanceOf(SynapseConfigurationException.class)
                .hasMessage("These properties are fixed at build time in a native image or AOT run, and differ from the"
                        + " build: spring.grpc.server.reflection.enabled is false but the build used true."));
    }

    /**
     * Runs AOT processing on the application, writes and compiles the generated code, and hands the result to the
     * action.
     */
    private void generate(GeneratedAction action) {
        runner.prepare(context -> {
            TestGenerationContext generationContext = new TestGenerationContext();
            ClassName initializerName = new ApplicationContextAotGenerator()
                    .processAheadOfTime((GenericApplicationContext) context.getSourceApplicationContext(),
                            generationContext);
            generationContext.writeGeneratedContent();
            RuntimeHints hints = generationContext.getRuntimeHints();
            TestCompiler.forSystem().with(generationContext).compile(compiled -> {
                try {
                    action.accept(new Generated(hints, compiled, initializerName));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            });
        });
    }

    /** What a test does with the generated application. */
    @FunctionalInterface
    interface GeneratedAction {

        void accept(Generated generated) throws Exception;
    }

    /**
     * The generated application: its runtime hints and its compiled initializer.
     *
     * @param hints the runtime hints AOT processing registered
     * @param compiled the compiled generated code
     * @param initializer the name of the generated context initializer
     */
    record Generated(RuntimeHints hints, Compiled compiled, ClassName initializer) {

        /**
         * Starts the generated context in AOT mode ({@code spring.aot.enabled}, restored afterwards), as a JVM with
         * {@code -Dspring.aot.enabled=true} would, so the AOT-only checks run.
         *
         * @param properties properties of this run, besides the in-process server's
         * @return the refreshed context, which the caller closes
         */
        GenericApplicationContext start(String... properties) {
            GenericApplicationContext context = new GenericApplicationContext();
            TestPropertyValues.of(PROPERTIES).and(properties).applyTo(context);
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

    /** Calls {@code GetItem} on the in-process server with a correlation id, capturing the response metadata. */
    private static Item getItem(String id, String correlationId, AtomicReference<@Nullable Metadata> headers,
            AtomicReference<@Nullable Metadata> trailers) throws InterruptedException {
        Metadata metadata = new Metadata();
        metadata.put(CORRELATION, correlationId);
        ManagedChannel channel = InProcessChannelBuilder.forName(SERVER).directExecutor().build();
        try {
            Channel intercepted = ClientInterceptors
                    .intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata),
                            MetadataUtils.newCaptureMetadataInterceptor(headers, trailers));
            return CatalogServiceGrpc
                    .newBlockingStub(intercepted)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getItem(GetItemRequest.newBuilder().setId(id).build());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** The input of {@code GetItem}. */
    record ItemKey(String id) {
    }

    /** An item's author, nested in {@link ItemView}: the {@code author} message field. */
    record AuthorView(String name, String countryCode) {
    }

    /** The output of {@code GetItem}; {@code title} is the {@code name} field through {@code .map}. */
    record ItemView(String id, String title, AuthorView author) {
    }

    /** Finds every item. */
    static class GetItemOperation implements QueryOperation<ItemKey, ItemView> {

        @Override
        public ItemView execute(ItemKey input) {
            return new ItemView(input.id(), "Dune", new AuthorView("Frank Herbert", "US"));
        }
    }

    /** The input of {@code CreateItem}: only what the request carries, so it binds by name. */
    record CreateItemInput(BindingFixtures.ItemRecord item) {
    }

    /** Named {@code create-item}. */
    static class CreateItemOperation implements CommandOperation<CreateItemInput, BindingFixtures.ItemRecord> {

        @Override
        public BindingFixtures.ItemRecord execute(CreateItemInput input) {
            return input.item();
        }
    }

    /** {@code GetItem} with a {@code .map} declaration; every other catalog method bound by name. */
    @Configuration(proxyBeanMethods = false)
    static class Bindings {

        @Bean
        GetItemOperation getItemOperation() {
            return new GetItemOperation();
        }

        @Bean
        CreateItemOperation createItemOperation() {
            return new CreateItemOperation();
        }

        @Bean
        BindingFixtures.DeleteItemOperation deleteItemOperation() {
            return new BindingFixtures.DeleteItemOperation();
        }

        @Bean
        BindingFixtures.PingOperation pingOperation() {
            return new BindingFixtures.PingOperation();
        }

        @Bean
        BindingFixtures.ListTagsOperation listTagsOperation() {
            return new BindingFixtures.ListTagsOperation();
        }

        @Bean
        BindingFixtures.FailOperation failOperation() {
            return new BindingFixtures.FailOperation();
        }

        @Bean
        GrpcBindings catalogBindings(GetItemOperation getItem) {
            return GrpcBindings
                    .service(CatalogServiceGrpc.getServiceDescriptor())
                    .unary(CatalogServiceGrpc.getGetItemMethod(), getItem)
                    .map("name", ItemView::title)
                    .bindAllByName()
                    .build();
        }
    }
}
