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
package io.americanexpress.synapse.samples.bookstore;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.autoconfigure.SynapseGrpcAutoConfiguration;
import io.americanexpress.synapse.api.grpc.autoconfigure.SynapseGrpcErrorAutoConfiguration;
import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestAutoConfiguration;
import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestErrorAutoConfiguration;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;
import io.americanexpress.synapse.samples.bookstore.api.grpc.BookstoreGrpcConfiguration;
import io.americanexpress.synapse.samples.bookstore.api.rest.BookstoreRestConfiguration;
import io.americanexpress.synapse.samples.bookstore.contract.Book;
import io.americanexpress.synapse.samples.bookstore.contract.SearchBooksRequest;
import io.americanexpress.synapse.samples.bookstore.contract.proto.BookServiceGrpc;
import io.americanexpress.synapse.samples.bookstore.data.books.BookRepository;
import io.americanexpress.synapse.samples.bookstore.operation.CreateBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.DeleteBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.GetBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.SearchBooksOperation;
import io.americanexpress.synapse.samples.bookstore.operation.UpdateBookOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup fails when the REST and gRPC bindings of one operation declare it differently: the service's own bindings,
 * which declare every operation alike, start; a gRPC binding that drops {@code .notFound} from {@code GetBook} fails.
 * Both plugins compile their bindings in one context, with the auto-configurations a service has and the operations
 * on a mocked repository; no server, database or port is used.
 */
class BindingParityTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(AopAutoConfiguration.class, ValidationAutoConfiguration.class, JacksonAutoConfiguration.class,
                            HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class,
                            ObservationAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
                            OpenTelemetryTracingAutoConfiguration.class, MicrometerTracingAutoConfiguration.class,
                            SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class,
                            SynapseOperationAutoConfiguration.class, SynapseRestErrorAutoConfiguration.class,
                            SynapseRestAutoConfiguration.class, SynapseGrpcErrorAutoConfiguration.class,
                            SynapseGrpcAutoConfiguration.class))
            .withBean(BookRepository.class, () -> Mockito.mock(BookRepository.class))
            .withBean(CreateBookOperation.class)
            .withBean(GetBookOperation.class)
            .withBean(SearchBooksOperation.class)
            .withBean(UpdateBookOperation.class)
            .withBean(DeleteBookOperation.class);

    @Test
    void theServicesBindingsDeclaredAlikeStart() {
        runner
                .withUserConfiguration(BookstoreRestConfiguration.class, BookstoreGrpcConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aNotFoundThatDiffersFailsStartup() {
        runner
                .withUserConfiguration(BookstoreRestConfiguration.class, GrpcWithoutNotFound.class)
                .run(BindingParityTest::assertNotFoundMismatch);
    }

    @Test
    void theServicesBindingsDeclaredAlikeStartUnderLazyInitialization() {
        lazy(runner)
                .withUserConfiguration(BookstoreRestConfiguration.class, BookstoreGrpcConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aNotFoundThatDiffersFailsStartupUnderLazyInitialization() {
        // Both plugins keep their bindings eager, so the check still sees them once every singleton exists.
        lazy(runner)
                .withUserConfiguration(BookstoreRestConfiguration.class, GrpcWithoutNotFound.class)
                .run(BindingParityTest::assertNotFoundMismatch);
    }

    /** What {@code spring.main.lazy-initialization=true} does: every bean that no filter excludes becomes lazy. */
    private static WebApplicationContextRunner lazy(WebApplicationContextRunner runner) {
        return runner
                .withInitializer(context -> context
                        .addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
    }

    private static void assertNotFoundMismatch(AssertableWebApplicationContext context) {
        assertThat(context)
                .hasFailed()
                .getFailure()
                .isNotNull()
                .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                    assertThat(failure.description())
                            .isEqualTo("Operation 'get-book' is bound differently over REST (GET /books/{id}) and "
                                    + "gRPC (synapse.samples.bookstore.v1.BookService/GetBook): notFound is "
                                    + "BOOK_NOT_FOUND over REST and NOT_FOUND over gRPC.");
                    assertThat(failure.action())
                            .isEqualTo("Declare .notFound(...) the same way on both bindings: protocols must not "
                                    + "differ in contract.");
                });
    }

    /** The service's gRPC bindings, with {@code GetBook} bound without {@code .notFound(BOOK_NOT_FOUND)}. */
    @Configuration(proxyBeanMethods = false)
    static class GrpcWithoutNotFound {

        @Bean
        GrpcBindings grpcBindings(CreateBookOperation createBook, GetBookOperation getBook,
                SearchBooksOperation searchBooks, UpdateBookOperation updateBook, DeleteBookOperation deleteBook) {
            return GrpcBindings
                    .service(BookServiceGrpc.getServiceDescriptor())
                    .unary(BookServiceGrpc.getCreateBookMethod(), createBook)
                    .etag(Book::version)
                    .unary(BookServiceGrpc.getGetBookMethod(), getBook)
                    .etag(Book::version)
                    .unary(BookServiceGrpc.getSearchBooksMethod(), searchBooks)
                    .sortable(SearchBooksRequest.SortKey.class)
                    .unary(BookServiceGrpc.getUpdateBookMethod(), updateBook)
                    .unary(BookServiceGrpc.getDeleteBookMethod(), deleteBook)
                    .build();
        }
    }
}
