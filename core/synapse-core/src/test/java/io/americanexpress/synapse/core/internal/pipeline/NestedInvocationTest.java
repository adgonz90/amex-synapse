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
package io.americanexpress.synapse.core.internal.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import ch.qos.logback.classic.Level;
import io.micrometer.common.KeyValue;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.InboundContext;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.BookErrors;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.CardLabel;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.GetBookOperation;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.LogCapture;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.SaveBookOperation;
import io.americanexpress.synapse.core.observability.OperationObservationContext;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.CLOCK;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.FAILURE_LOGGER;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.NOW;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.SECRET;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.VALIDATOR;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.linesContaining;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.setLevel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class NestedInvocationTest {

    /** The outer operation, named {@code get-shelf}; it calls {@code get-book} through a function. */
    static class GetShelfOperation implements QueryOperation<String, String> {

        private final Function<String, String> body;

        GetShelfOperation(Function<String, String> body) {
            this.body = body;
        }

        @Override
        public String execute(String input) {
            return body.apply(input);
        }
    }

    private final TestObservationRegistry observations = TestObservationRegistry.create();

    private final ContextBinder binder = PipelineFixtures.binder();

    @RegisterExtension
    final LogCapture logs = new LogCapture();

    private final OperationPipeline pipeline = OperationPipeline
            .builder()
            .clock(CLOCK)
            .validator(VALIDATOR)
            .observationRegistry(observations)
            .contextBinder(binder)
            .build();

    private @Nullable Level previousLevel;

    @BeforeEach
    void infoLevel() {
        previousLevel = setLevel(FAILURE_LOGGER, Level.INFO);
    }

    @AfterEach
    void restoreLevel() {
        setLevel(FAILURE_LOGGER, previousLevel);
    }

    private GetBookOperation notFoundBook(SynapseException failure) {
        return pipeline.wrapOperation(new GetBookOperation(in -> {
            throw failure;
        }));
    }

    @Test
    void nestedCallReusesContextAndCreatesChildObservation() {
        GetBookOperation inner = pipeline
                .wrapOperation(new GetBookOperation(in -> RequestContext.current().correlationId()));
        GetShelfOperation outer = pipeline
                .wrapOperation(new GetShelfOperation(in -> RequestContext.current().correlationId() + "|"
                        + inner.execute(in)));

        String[] ids = outer.execute("x").split("\\|", -1);

        assertThat(ids[1]).isEqualTo(ids[0]);
        TestObservationRegistryAssert
                .assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo("synapse.operation", 2)
                .hasHandledContextsThatSatisfy(contexts -> {
                    OperationObservationContext shelf = find(contexts, "get-shelf");
                    OperationObservationContext book = find(contexts, "get-book");
                    assertThat(shelf.getParentObservation()).isNull();
                    ObservationView parent = book.getParentObservation();
                    assertThat(parent).isNotNull();
                    assertThat(Objects.requireNonNull(parent).getContextView()).isSameAs(shelf);
                    assertThat(book.getHighCardinalityKeyValue("correlation.id"))
                            .isEqualTo(KeyValue.of("correlation.id", ids[0]));
                });
    }

    private static OperationObservationContext find(List<Observation.Context> contexts, String name) {
        return contexts
                .stream()
                .map(OperationObservationContext.class::cast)
                .filter(context -> context.operationName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void nestedFailureIsLoggedOnceByOutermost() {
        SynapseException failure = new SynapseException(BookErrors.BOOK_NOT_FOUND, "42");
        GetBookOperation inner = notFoundBook(failure);
        GetShelfOperation outer = pipeline.wrapOperation(new GetShelfOperation(inner::execute));

        try (ContextScope scope = binder.open(new InboundContext("c-3", Map.of(), null, null, null))) {
            assertThatThrownBy(() -> outer.execute("42")).isSameAs(failure);
        }

        assertThat(linesContaining(logs.text(), FAILURE_LOGGER)).isEqualTo(1);
        assertThat(logs.text())
                .contains("Operation get-shelf failed with BOOK_NOT_FOUND (NOT_FOUND)", "correlationId=\"c-3\"")
                .doesNotContain("Operation get-book failed");
        TestObservationRegistryAssert
                .assertThat(observations)
                .forAllObservationsWithNameEqualTo("synapse.operation",
                        observation -> observation
                                .hasError(failure)
                                .hasLowCardinalityKeyValue("error.code", "BOOK_NOT_FOUND"));
    }

    @Test
    void caughtNestedFailureIsNotLogged() {
        GetBookOperation inner = notFoundBook(new SynapseException(BookErrors.BOOK_NOT_FOUND, "42"));
        GetShelfOperation outer = pipeline.wrapOperation(new GetShelfOperation(in -> {
            try {
                return inner.execute(in);
            } catch (SynapseException e) {
                return "fallback";
            }
        }));

        assertThat(outer.execute("42")).isEqualTo("fallback");

        assertThat(logs.text()).doesNotContain(FAILURE_LOGGER);
    }

    @Test
    void asyncNestedCallOnOtherThreadIsOutermostThere() throws Exception {
        GetBookOperation inner = notFoundBook(new SynapseException(BookErrors.BOOK_NOT_FOUND, "42"));
        ExecutorService pool = Executors.newSingleThreadExecutor(task -> new Thread(task, "async-worker"));
        try {
            GetShelfOperation outer = pipeline.wrapOperation(new GetShelfOperation(in -> {
                Future<?> async = pool
                        .submit(ContextSnapshotFactory.builder().build().captureAll().wrap(() -> inner.execute(in)));
                try {
                    async.get();
                    return "unexpected";
                } catch (ExecutionException e) {
                    return e.getCause() instanceof SynapseException synapse ? synapse.code() : "other";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }));

            try (ContextScope scope = binder.open(new InboundContext("c-4", Map.of(), null, null, null))) {
                assertThat(outer.execute("42")).isEqualTo("BOOK_NOT_FOUND");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(linesContaining(logs.text(), FAILURE_LOGGER)).isEqualTo(1);
        // The worker's MDC has no correlation id (no-op tracer), so the line carries it as a key-value.
        assertThat(logs.text())
                .contains("[async-worker]", "Operation get-book failed with BOOK_NOT_FOUND", "correlationId=\"c-4\"");
    }

    @Test
    void depthIsRestoredOnEveryExitPath() {
        StackOverflowError error = new StackOverflowError();
        GetBookOperation erroring = pipeline.wrapOperation(new GetBookOperation(in -> {
            throw error;
        }));
        GetBookOperation ok = pipeline
                .wrapOperation(new GetBookOperation(in -> String
                        .valueOf(OperationInterceptor
                                .depth())));
        GetBookOperation failing = notFoundBook(new SynapseException(BookErrors.BOOK_NOT_FOUND, "1"));

        assertThatThrownBy(() -> erroring.execute("x")).isSameAs(error);
        assertThat(OperationInterceptor.depth()).isZero();
        assertThat(ok.execute("x")).isEqualTo("1");
        assertThat(OperationInterceptor.depth()).isZero();
        try (ContextScope scope = binder.open(new InboundContext("c-5", Map.of(), null, null, NOW))) {
            assertThatThrownBy(() -> ok.execute("x")).isInstanceOf(SynapseException.class);
        }
        assertThat(OperationInterceptor.depth()).isZero();
        assertThat(RequestContext.find()).isEmpty();

        assertThatThrownBy(() -> failing.execute("x")).isInstanceOf(SynapseException.class);
        assertThat(logs.text()).contains("Operation get-book failed with BOOK_NOT_FOUND");
    }

    @Test
    void drainingAdmitsAsyncFanOutOfAnAdmittedCall() throws Exception {
        InflightTracker tracker = new InflightTracker();
        OperationPipeline counted = OperationPipeline
                .builder()
                .validator(VALIDATOR)
                .contextBinder(binder)
                .inflight(tracker)
                .build();
        GetBookOperation inner = counted
                .wrapOperation(new GetBookOperation(in -> RequestContext.current().correlationId() + "|"
                        + tracker.inflight()));
        ExecutorService pool = Executors.newSingleThreadExecutor(task -> new Thread(task, "fan-out"));
        try {
            GetShelfOperation outer = counted.wrapOperation(new GetShelfOperation(in -> {
                tracker.startDraining();
                Future<String> async = pool
                        .submit(ContextSnapshotFactory.builder().build().captureAll().wrap(() -> inner.execute(in)));
                try {
                    return async.get();
                } catch (ExecutionException e) {
                    return "failed: " + e.getCause();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }));

            try (ContextScope scope = binder.open(new InboundContext("c-6", Map.of(), null, null, null))) {
                assertThat(outer.execute("x")).isEqualTo("c-6|2");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(tracker.inflight()).isZero();
    }

    @Test
    void nestedErrorPropagatesUnchanged() {
        StackOverflowError error = new StackOverflowError();
        List<Throwable> translated = new ArrayList<>();
        OperationPipeline translating = OperationPipeline
                .builder()
                .validator(VALIDATOR)
                .observationRegistry(observations)
                .contextBinder(binder)
                .translators(List.of(ex -> {
                    translated.add(ex);
                    return null;
                }))
                .build();
        GetBookOperation inner = translating.wrapOperation(new GetBookOperation(in -> {
            throw error;
        }));
        GetShelfOperation outer = translating.wrapOperation(new GetShelfOperation(inner::execute));

        assertThatThrownBy(() -> outer.execute("x")).isSameAs(error);

        assertThat(translated).isEmpty();
        assertThat(logs.text()).doesNotContain(FAILURE_LOGGER);
        TestObservationRegistryAssert
                .assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo("synapse.operation", 2)
                .forAllObservationsWithNameEqualTo("synapse.operation",
                        observation -> observation.hasError(error).hasLowCardinalityKeyValue("outcome", "error"));
        assertThat(OperationInterceptor.depth()).isZero();
    }

    @Test
    void nestedForeignExceptionIsLoggedOnceWithItsCause() {
        IllegalStateException cause = new IllegalStateException("disk full");
        GetBookOperation inner = pipeline.wrapOperation(new GetBookOperation(in -> {
            throw cause;
        }));
        GetShelfOperation outer = pipeline.wrapOperation(new GetShelfOperation(inner::execute));

        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> outer.execute("x"));

        assertThat(failure.details()).isEqualTo(SynapseErrors.INTERNAL);
        assertThat(failure.getCause()).isSameAs(cause);
        assertThat(linesContaining(logs.text(), "failed with")).isEqualTo(1);
        assertThat(logs.text())
                .contains(" ERROR ", "Operation get-shelf failed with INTERNAL (INTERNAL)",
                        "Caused by: java.lang.IllegalStateException: disk full")
                .doesNotContain("Operation get-book failed");
        TestObservationRegistryAssert
                .assertThat(observations)
                .forAllObservationsWithNameEqualTo("synapse.operation", observation -> observation.hasError(failure));
    }

    /** A business model the service builds itself. */
    record ShelfLabel(@NotBlank String text) {
    }

    /** A business operation named {@code label-shelf}. */
    static class LabelShelf implements BusinessOperation<ShelfLabel, String> {
        @Override
        public String execute(ShelfLabel input) {
            return input.text();
        }
    }

    record ShelfRequest(@NotBlank String id) {
    }

    /** A query named {@code count-shelf}. */
    static class CountShelfOperation implements QueryOperation<ShelfRequest, String> {

        private final Function<ShelfRequest, String> body;

        CountShelfOperation(Function<ShelfRequest, String> body) {
            this.body = body;
        }

        @Override
        public String execute(ShelfRequest input) {
            return body.apply(input);
        }
    }

    /** A write whose version is required: the component is not {@code @Nullable} in a {@code @NullMarked} package. */
    record MoveBook(String id, VersionCondition<Long> version) {
    }

    private static void assertInternalCausedBy(SynapseException failure, SynapseErrors cause) {
        assertThat(failure.details()).isEqualTo(SynapseErrors.INTERNAL);
        assertThat(failure.violations()).isEmpty();
        assertThat(failure.getCause())
                .isInstanceOfSatisfying(SynapseException.class,
                        nested -> assertThat(nested.details()).isEqualTo(cause));
    }

    @Test
    void nestedBusinessInputViolationIsInternalAtTheEdge() {
        LabelShelf label = pipeline.wrapBusiness(new LabelShelf());
        GetShelfOperation outer = pipeline
                .wrapOperation(new GetShelfOperation(in -> label.execute(new ShelfLabel(""))));

        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> outer.execute("x"));

        assertInternalCausedBy(failure, SynapseErrors.VALIDATION_FAILED);
        assertThat(linesContaining(logs.text(), "failed with")).isEqualTo(1);
        assertThat(logs.text())
                .contains(" ERROR ", "Operation get-shelf failed with INTERNAL (INTERNAL)",
                        "violations=\"text: {jakarta.validation.constraints.NotBlank.message}\"");
    }

    @Test
    void nestedOperationInputViolationIsInternal() {
        CountShelfOperation inner = pipeline.wrapOperation(new CountShelfOperation(ShelfRequest::id));
        GetShelfOperation outer = pipeline
                .wrapOperation(new GetShelfOperation(in -> inner.execute(new ShelfRequest(""))));

        assertInternalCausedBy(catchThrowableOfType(SynapseException.class, () -> outer.execute("x")),
                SynapseErrors.VALIDATION_FAILED);
        // Called first, the same operation's input is the caller's own.
        SynapseException direct = catchThrowableOfType(SynapseException.class,
                () -> inner.execute(new ShelfRequest("")));
        assertThat(direct.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
        assertThat(direct.violations()).extracting(Violation::field).containsExactly("id");
    }

    @Test
    @SuppressWarnings("NullAway") // The case under test: a service passes null for a required condition.
    void nestedMissingRequiredVersionIsInternal() {
        SaveBookOperation<MoveBook> inner = pipeline.wrapOperation(new SaveBookOperation<>(MoveBook::id));
        GetShelfOperation outer = pipeline
                .wrapOperation(new GetShelfOperation(in -> inner.execute(new MoveBook(in, null))));

        assertInternalCausedBy(catchThrowableOfType(SynapseException.class, () -> outer.execute("b1")),
                SynapseErrors.PRECONDITION_REQUIRED);
        assertThat(inner.calls).isZero();
        SynapseException direct = catchThrowableOfType(SynapseException.class,
                () -> inner.execute(new MoveBook("b1", null)));
        assertThat(direct.details()).isEqualTo(SynapseErrors.PRECONDITION_REQUIRED);
        assertThat(direct.violations()).extracting(Violation::field).containsExactly("version");
    }

    /** A group the pipeline never validates: an operation checks it by hand. */
    interface Strict {
    }

    record CheckedShelf(@Size(max = 3, groups = Strict.class) String id) {
    }

    @Test
    void nestedHandValidationOfItsInputIsInternal() {
        OperationPipeline translating = OperationPipeline
                .builder()
                .clock(CLOCK)
                .validator(VALIDATOR)
                .observationRegistry(observations)
                .contextBinder(binder)
                .translators(List.of(new ConstraintViolationTranslator()))
                .build();
        SaveBookOperation<CheckedShelf> inner = translating.wrapOperation(new SaveBookOperation<>(in -> {
            throw new ConstraintViolationException(VALIDATOR.validate(in, Strict.class));
        }));
        GetShelfOperation outer = translating
                .wrapOperation(new GetShelfOperation(in -> inner.execute(new CheckedShelf(in))));

        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> outer.execute("long"));

        assertThat(failure.details()).isEqualTo(SynapseErrors.INTERNAL);
        assertThat(failure.violations()).isEmpty();
        // Sanitized: the cause names each path and message template, never the interpolated message.
        assertThat(failure.getCause())
                .isNotInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("id: {jakarta.validation.constraints.Size.message}");
        // Called first, its hand validation is of the caller's own input.
        SynapseException direct = catchThrowableOfType(SynapseException.class,
                () -> inner.execute(new CheckedShelf("long")));
        assertThat(direct.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
        assertThat(direct.violations()).extracting(Violation::field).containsExactly("id");
    }

    /** A business operation named {@code label-card}. */
    static class LabelCard implements BusinessOperation<CardLabel, String> {
        @Override
        public String execute(CardLabel input) {
            return input.number();
        }
    }

    @Test
    void aMaskedValueOfABusinessInputNeverReachesTheErrorLine() {
        LabelCard label = pipeline.wrapBusiness(new LabelCard());
        GetShelfOperation outer = pipeline
                .wrapOperation(new GetShelfOperation(in -> label.execute(new CardLabel(in))));

        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> outer.execute(SECRET));

        assertInternalCausedBy(failure, SynapseErrors.VALIDATION_FAILED);
        assertThat(logs.text())
                .contains(" ERROR ", "Operation get-shelf failed with INTERNAL",
                        "violations=\"number: ${validatedValue} is too long\"")
                .doesNotContain(SECRET);
    }

    /** Joins the future and rethrows the {@code SynapseException} it failed with, as a caller that unwraps it does. */
    private static <T> T joinUnwrapped(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException wrapped) {
            if (wrapped.getCause() instanceof SynapseException synapse) {
                throw synapse;
            }
            throw wrapped;
        }
    }

    /** Asserts a rethrown rejection of another call: {@code INTERNAL}, caused by the rejection with its violation. */
    private static void assertRethrownRejection(SynapseException failure, SynapseErrors rejection, String field) {
        assertInternalCausedBy(failure, rejection);
        assertThat(failure.getCause())
                .isInstanceOfSatisfying(SynapseException.class,
                        cause -> assertThat(cause.violations()).extracting(Violation::field).containsExactly(field));
    }

    @Test
    void commonPoolRejectionRethrownIsInternal() {
        CountShelfOperation inner = pipeline.wrapOperation(new CountShelfOperation(ShelfRequest::id));
        GetShelfOperation outer = pipeline
                .wrapOperation(new GetShelfOperation(in -> joinUnwrapped(
                        CompletableFuture.supplyAsync(() -> inner.execute(new ShelfRequest(""))))));

        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> outer.execute("x"));

        assertRethrownRejection(failure, SynapseErrors.VALIDATION_FAILED, "id");
        assertThat(logs.text())
                .contains(" ERROR ", "Operation get-shelf failed with INTERNAL (INTERNAL)",
                        "violations=\"id: {jakarta.validation.constraints.NotBlank.message}\"");
    }

    /** Runs a body in an {@code @Async} method. */
    static class AsyncRunner {

        @Async
        public CompletableFuture<String> run(Supplier<String> body) {
            return CompletableFuture.completedFuture(body.get());
        }
    }

    /** {@code @Async} on an executor that propagates the context, as Synapse's task decorator does. */
    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class AsyncConfig {

        @Bean
        ThreadPoolTaskExecutor taskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setThreadNamePrefix("async-op-");
            executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
            return executor;
        }

        @Bean
        AsyncRunner asyncRunner() {
            return new AsyncRunner();
        }
    }

    @Test
    void asyncRejectionRethrownIsInternal() {
        CountShelfOperation inner = pipeline.wrapOperation(new CountShelfOperation(ShelfRequest::id));
        try (AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext(AsyncConfig.class)) {
            AsyncRunner async = spring.getBean(AsyncRunner.class);
            GetShelfOperation outer = pipeline
                    .wrapOperation(new GetShelfOperation(
                            in -> joinUnwrapped(async.run(() -> inner.execute(new ShelfRequest(""))))));

            SynapseException failure;
            try (ContextScope scope = binder.open(new InboundContext("c-7", Map.of(), null, null, null))) {
                failure = catchThrowableOfType(SynapseException.class, () -> outer.execute("x"));
            }

            assertRethrownRejection(failure, SynapseErrors.VALIDATION_FAILED, "id");
        }
        // The worker's own line: it ran on the executor with the caller's context (only a propagated context has c-7
        // there), and it was outermost there, so its own call was VALIDATION_FAILED.
        List<String> worker = logs
                .text()
                .lines()
                .filter(line -> line.contains("Operation count-shelf failed"))
                .toList();
        assertThat(worker)
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains("[async-op-1]", "correlationId=\"c-7\"",
                        "Operation count-shelf failed with VALIDATION_FAILED");
        assertThat(logs.text()).contains("Operation get-shelf failed with INTERNAL (INTERNAL)");
    }

    @Test
    void rawThreadRejectionRethrownIsInternal() {
        CountShelfOperation inner = pipeline.wrapOperation(new CountShelfOperation(ShelfRequest::id));
        GetShelfOperation outer = pipeline.wrapOperation(new GetShelfOperation(in -> {
            AtomicReference<@Nullable SynapseException> rejected = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    inner.execute(new ShelfRequest(""));
                } catch (SynapseException e) {
                    rejected.set(e);
                }
            }, "raw-worker");
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
            throw Objects.requireNonNull(rejected.get());
        }));

        assertRethrownRejection(catchThrowableOfType(SynapseException.class, () -> outer.execute("x")),
                SynapseErrors.VALIDATION_FAILED, "id");
    }

    @Test
    void userBuiltValidationFailedPasses() {
        SynapseException taken = SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED, List.of(new Violation("isbn", "is taken")));
        GetShelfOperation outer = pipeline.wrapOperation(new GetShelfOperation(in -> {
            throw taken;
        }));

        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> outer.execute("x"));

        assertThat(failure).isSameAs(taken);
        assertThat(failure.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
        assertThat(failure.violations()).containsExactly(new Violation("isbn", "is taken"));
    }

    @Test
    void ownRejectionStaysValidationFailed() {
        CountShelfOperation outer = pipeline.wrapOperation(new CountShelfOperation(ShelfRequest::id));

        SynapseException failure = catchThrowableOfType(SynapseException.class,
                () -> outer.execute(new ShelfRequest("")));

        assertThat(failure.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
        assertThat(failure.violations()).extracting(Violation::field).containsExactly("id");
    }

    @Test
    @SuppressWarnings("NullAway") // The case under test: a service passes null for a required condition.
    void preconditionRequiredFromAnotherThreadIsInternal() {
        SaveBookOperation<MoveBook> inner = pipeline.wrapOperation(new SaveBookOperation<>(MoveBook::id));
        GetShelfOperation outer = pipeline
                .wrapOperation(new GetShelfOperation(
                        in -> joinUnwrapped(
                                CompletableFuture.supplyAsync(() -> inner.execute(new MoveBook(in, null))))));

        assertRethrownRejection(catchThrowableOfType(SynapseException.class, () -> outer.execute("b1")),
                SynapseErrors.PRECONDITION_REQUIRED, "version");
        assertThat(inner.calls).isZero();
    }

    @Test
    void aMiddleCallAlsoSeesARethrownRejectionAsInternal() {
        CountShelfOperation inner = pipeline.wrapOperation(new CountShelfOperation(ShelfRequest::id));
        GetBookOperation middle = pipeline
                .wrapOperation(new GetBookOperation(in -> joinUnwrapped(
                        CompletableFuture.supplyAsync(() -> inner.execute(new ShelfRequest(""))))));
        GetShelfOperation outer = pipeline.wrapOperation(new GetShelfOperation(in -> {
            try {
                return middle.execute(in);
            } catch (SynapseException e) {
                return e.code();
            }
        }));

        assertThat(outer.execute("x")).isEqualTo("INTERNAL");
    }

    @Test
    void handValidatedRejectionOnAnotherThreadIsInternalWhenRethrown() {
        OperationPipeline translating = OperationPipeline
                .builder()
                .clock(CLOCK)
                .validator(VALIDATOR)
                .observationRegistry(observations)
                .contextBinder(binder)
                .translators(List.of(new ConstraintViolationTranslator()))
                .build();
        SaveBookOperation<CheckedShelf> inner = translating.wrapOperation(new SaveBookOperation<>(in -> {
            throw new ConstraintViolationException(VALIDATOR.validate(in, Strict.class));
        }));
        GetShelfOperation outer = translating
                .wrapOperation(new GetShelfOperation(in -> joinUnwrapped(
                        CompletableFuture.supplyAsync(() -> inner.execute(new CheckedShelf(in))))));

        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> outer.execute("long"));

        assertRethrownRejection(failure, SynapseErrors.VALIDATION_FAILED, "id");
        // Q11: the logged cause names the template, never the interpolated message.
        assertThat(logs.text())
                .contains(" ERROR ", "Operation get-shelf failed with INTERNAL (INTERNAL)",
                        "violations=\"id: {jakarta.validation.constraints.Size.message}\"")
                .doesNotContain("size must be between");
        // Called first, on its own, the operation's hand validation is still the caller's own rejection.
        SynapseException direct = catchThrowableOfType(SynapseException.class,
                () -> inner.execute(new CheckedShelf("long")));
        assertThat(direct.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
        assertThat(direct.violations()).containsExactly(new Violation("id", "size must be between 0 and 3"));
    }
}
