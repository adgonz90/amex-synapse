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
package io.americanexpress.synapse.test.operation;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.test.time.MutableClock;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynapseOperationsTest {

    record CreateBookRequest(@NotBlank String title) {
    }

    static class CreateBookOperation implements CommandOperation<CreateBookRequest, String> {
        @Override
        public String execute(CreateBookRequest input) {
            return "created " + input.title();
        }
    }

    static class TitleRule implements BusinessOperation<CreateBookRequest, String> {
        @Override
        public String execute(CreateBookRequest input) {
            return input.title().toUpperCase(Locale.ROOT);
        }
    }

    static class EchoCorrelationOperation implements CommandOperation<String, String> {
        @Override
        public String execute(String input) {
            return RequestContext.current().correlationId();
        }
    }

    static class BoomOperation implements CommandOperation<String, String> {
        @Override
        public String execute(String input) {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void invokeRunsTheRealPipeline() {
        assertThatSynapseException(
                () -> SynapseOperations.invoke(new CreateBookOperation(), new CreateBookRequest("")))
                .hasCode(SynapseErrors.VALIDATION_FAILED)
                .hasViolation("title");
        assertThat(SynapseOperations.invoke(new CreateBookOperation(), new CreateBookRequest("Dune")))
                .isEqualTo("created Dune");
    }

    @Test
    void invokeWorksForBusinessOperations() {
        // A business input is built by the service, so its violation is INTERNAL; the cause keeps the violations.
        assertThatSynapseException(() -> SynapseOperations.invoke(new TitleRule(), new CreateBookRequest(" ")))
                .hasCode("INTERNAL")
                .satisfies(failure -> assertThat(failure.getCause())
                        .isInstanceOfSatisfying(SynapseException.class, cause -> {
                            assertThat(cause.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                            assertThat(cause.violations()).extracting(Violation::field).containsExactly("title");
                        }));
        assertThat(SynapseOperations.invoke(new TitleRule(), new CreateBookRequest("Dune"))).isEqualTo("DUNE");
    }

    @Test
    void invokeUsesGivenContext() {
        String id = RequestContexts
                .with()
                .correlationId("c-9")
                .call(() -> SynapseOperations.invoke(new EchoCorrelationOperation(), "x"));

        assertThat(id).isEqualTo("c-9");
    }

    @Test
    void invokeGeneratesACorrelationIdWithoutContext() {
        assertThat(SynapseOperations.invoke(new EchoCorrelationOperation(), "x")).isNotBlank();
    }

    @Test
    void invokerUsesConfiguredClock() {
        MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
        OperationInvoker invoker = SynapseOperations.with().clock(clock).invoker();
        Instant deadline = Instant.parse("2026-01-01T00:00:01Z");

        String first = RequestContexts
                .with()
                .deadline(deadline)
                .call(() -> invoker.invoke(new EchoCorrelationOperation(), "x"));
        clock.advance(Duration.ofSeconds(2));

        assertThat(first).isNotBlank();
        assertThatSynapseException(() -> RequestContexts
                .with()
                .deadline(deadline)
                .run(() -> invoker.invoke(new EchoCorrelationOperation(), "x")))
                .hasCode(SynapseErrors.DEADLINE_EXCEEDED);
    }

    @Test
    void invokerAppliesTranslatorsInOrder() {
        OperationInvoker invoker = SynapseOperations
                .with()
                .translator(failure -> null)
                .translator(failure -> failure instanceof IllegalStateException
                        ? new SynapseException(SynapseErrors.UNAVAILABLE)
                        : null)
                .invoker();

        assertThatSynapseException(() -> invoker.invoke(new BoomOperation(), "x"))
                .hasCode(SynapseErrors.UNAVAILABLE)
                .hasCategory(ErrorCategory.UNAVAILABLE);
    }

    /**
     * Translators are ordered as the application orders their beans: an {@code Ordered} translator (as Synapse's data
     * translators are) is tried before one that declares no order, even when that one was added first.
     */
    @Test
    void invokerOrdersTranslatorsAsTheApplicationDoes() {
        OperationInvoker invoker = SynapseOperations
                .with()
                .translator(failure -> new SynapseException(SynapseErrors.CONFLICT))
                .translator(new OrderedTranslator())
                .invoker();

        assertThatSynapseException(() -> invoker.invoke(new BoomOperation(), "x")).hasCode(SynapseErrors.UNAVAILABLE);
    }

    /** Translates everything into {@code UNAVAILABLE}, at the data translator's order. */
    static class OrderedTranslator implements ExceptionTranslator, Ordered {

        @Override
        public SynapseException translate(Throwable exception) {
            return new SynapseException(SynapseErrors.UNAVAILABLE, exception);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE - 100;
        }
    }

    /** Fails the way a collaborator does with method validation: a {@code ConstraintViolationException}. */
    static class RejectedByCollaboratorOperation implements CommandOperation<String, String> {
        @Override
        public String execute(String input) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                throw new ConstraintViolationException(factory.getValidator().validate(new CreateBookRequest("")));
            }
        }
    }

    /** A group the pipeline never validates: the operation checks it by hand. */
    interface Strict {
    }

    record CodeRequest(@Size(max = 3, groups = Strict.class) String code) {
    }

    /** Validates its own input by hand, for a rule the pipeline doesn't run. */
    static class CheckCodeOperation implements CommandOperation<CodeRequest, String> {
        @Override
        public String execute(CodeRequest input) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                throw new ConstraintViolationException(factory.getValidator().validate(input, Strict.class));
            }
        }
    }

    @Test
    void invokeTranslatesConstraintViolationsAsTheApplicationDoes() {
        assertThatSynapseException(() -> SynapseOperations.invoke(new CheckCodeOperation(), new CodeRequest("abcd")))
                .hasCode(SynapseErrors.VALIDATION_FAILED)
                .hasViolation("code");
        // A collaborator's violations are not the caller's input: INTERNAL, with none of them.
        assertThatSynapseException(() -> SynapseOperations.invoke(new RejectedByCollaboratorOperation(), "x"))
                .hasCode("INTERNAL")
                .satisfies(failure -> {
                    assertThat(failure.violations()).isEmpty();
                    assertThat(failure.getCause())
                            .isNotInstanceOf(ConstraintViolationException.class)
                            .hasMessageContaining("title: {jakarta.validation.constraints.NotBlank.message}");
                });
    }

    @Test
    void constraintViolationsAreTranslatedBeforeTheGivenTranslators() {
        OperationInvoker invoker = SynapseOperations
                .with()
                .translator(failure -> new SynapseException(SynapseErrors.UNAVAILABLE))
                .invoker();

        assertThatSynapseException(() -> invoker.invoke(new CheckCodeOperation(), new CodeRequest("abcd")))
                .hasCode(SynapseErrors.VALIDATION_FAILED);
        assertThatSynapseException(() -> invoker.invoke(new BoomOperation(), "x"))
                .hasCode(SynapseErrors.UNAVAILABLE);
    }

    static class ListBooksOperation implements QueryOperation<PageQuery, String> {
        @Override
        public String execute(PageQuery input) {
            return "page of " + input.size();
        }
    }

    static final class FinalOperation implements CommandOperation<String, String> {
        @Override
        public String execute(String input) {
            return input;
        }
    }

    @Test
    void invokerObservesOperationsWithTheGivenRegistry() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        OperationInvoker invoker = SynapseOperations.with().observationRegistry(registry).invoker();

        assertThat(invoker.invoke(new CreateBookOperation(), new CreateBookRequest("Emma")))
                .isEqualTo("created Emma");

        TestObservationRegistryAssert
                .assertThat(registry)
                .hasObservationWithNameEqualTo("synapse.operation")
                .that()
                .hasBeenStopped();
        assertThat(SynapseOperations
                .with()
                .invoker()
                .invoke(new CreateBookOperation(),
                        new CreateBookRequest("Emma")))
                .isEqualTo("created Emma");
    }

    @Test
    void invokerEnforcesTheConfiguredMaxPageSize() {
        OperationInvoker invoker = SynapseOperations.with().maxPageSize(5).invoker();

        assertThat(invoker.invoke(new ListBooksOperation(), PageQuery.first(5))).isEqualTo("page of 5");
        assertThatSynapseException(() -> invoker.invoke(new ListBooksOperation(), PageQuery.first(6)))
                .hasCode(SynapseErrors.VALIDATION_FAILED);
        assertThat(SynapseOperations.invoke(new ListBooksOperation(), PageQuery.first(6))).isEqualTo("page of 6");
    }

    @Test
    void lambdaOperationsAreRejectedWithTheStartupReason() {
        CommandOperation<String, String> lambda = input -> input;

        assertThatThrownBy(() -> SynapseOperations.invoke(lambda, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is a lambda, which Synapse cannot proxy by class.")
                .hasMessageContaining("named, non-final");
        BusinessOperation<String, String> businessLambda = input -> input;
        assertThatThrownBy(() -> SynapseOperations.invoke(businessLambda, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is a lambda");
    }

    @Test
    void finalOperationsAreRejectedWithTheStartupReason() {
        assertThatThrownBy(() -> SynapseOperations.invoke(new FinalOperation(), "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is a final class, so Synapse cannot proxy it by class.");
    }
}
