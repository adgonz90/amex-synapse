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
package io.americanexpress.synapse.core.autoconfigure;

import java.util.function.Supplier;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

/** Shared fixtures of the operation wiring tests. */
final class OperationFixtures {

    static final String FAILURE_LOGGER = "io.americanexpress.synapse.operation";

    static final String PAYLOAD_LOGGER = "io.americanexpress.synapse.payload";

    private OperationFixtures() {
    }

    /**
     * A runner with the auto-configurations a Synapse service has: Boot's AOP, validation, message source, Jackson,
     * observation and OpenTelemetry tracing, and the Synapse core ones.
     */
    static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(AopAutoConfiguration.class, ValidationAutoConfiguration.class,
                                MessageSourceAutoConfiguration.class, JacksonAutoConfiguration.class,
                                ObservationAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
                                OpenTelemetryTracingAutoConfiguration.class, NoopTracerAutoConfiguration.class,
                                MicrometerTracingAutoConfiguration.class, SynapseCoreAutoConfiguration.class,
                                SynapseContextAutoConfiguration.class, SynapseOperationAutoConfiguration.class));
    }

    record GetBookRequest(@NotBlank String id) {
    }

    record Book(String id) {
    }

    record PriceRequest(@Min(1) int quantity) {
    }

    enum TestErrors implements ErrorDetails {

        FIRST("First translation", ErrorCategory.CONFLICT),

        SECOND("Second translation", ErrorCategory.CONFLICT);

        private final String template;

        private final ErrorCategory category;

        TestErrors(String template, ErrorCategory category) {
            this.template = template;
            this.category = category;
        }

        @Override
        public String code() {
            return name();
        }

        @Override
        public String messageTemplate() {
            return template;
        }

        @Override
        public ErrorCategory category() {
            return category;
        }
    }

    /** A query named {@code get-book}. */
    static class GetBookOperation implements QueryOperation<GetBookRequest, Book> {

        @Override
        public Book execute(GetBookRequest input) {
            return new Book(input.id());
        }
    }

    /** A command named {@code save-book}. */
    static class SaveBookOperation implements CommandOperation<GetBookRequest, Book> {

        @Override
        public Book execute(GetBookRequest input) {
            return new Book(input.id());
        }
    }

    /** A command named {@code fail-book} that throws what its supplier gives. */
    static class FailBookOperation implements CommandOperation<String, String> {

        private final Supplier<RuntimeException> failure;

        FailBookOperation(Supplier<RuntimeException> failure) {
            this.failure = failure;
        }

        @Override
        public String execute(String input) {
            throw failure.get();
        }
    }

    /** A business operation named {@code price-book-business}. */
    static class PriceBookBusiness implements BusinessOperation<PriceRequest, Integer> {

        @Override
        public Integer execute(PriceRequest input) {
            return input.quantity() * 10;
        }
    }
}
