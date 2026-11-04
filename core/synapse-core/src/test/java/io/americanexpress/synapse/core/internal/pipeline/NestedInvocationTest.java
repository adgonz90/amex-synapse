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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import ch.qos.logback.classic.Level;
import io.micrometer.common.KeyValue;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.InboundContext;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.BookErrors;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.GetBookOperation;
import io.americanexpress.synapse.core.observability.OperationObservationContext;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.CLOCK;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.FAILURE_LOGGER;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.NOW;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.VALIDATOR;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.linesContaining;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.setLevel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(OutputCaptureExtension.class)
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
    void nestedFailureIsLoggedOnceByOutermost(CapturedOutput output) {
        SynapseException failure = new SynapseException(BookErrors.BOOK_NOT_FOUND, "42");
        GetBookOperation inner = notFoundBook(failure);
        GetShelfOperation outer = pipeline.wrapOperation(new GetShelfOperation(inner::execute));

        try (ContextScope scope = binder.open(new InboundContext("c-3", Map.of(), null, null, null))) {
            assertThatThrownBy(() -> outer.execute("42")).isSameAs(failure);
        }

        assertThat(linesContaining(output.getOut(), FAILURE_LOGGER)).isEqualTo(1);
        assertThat(output.getOut())
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
    void caughtNestedFailureIsNotLogged(CapturedOutput output) {
        GetBookOperation inner = notFoundBook(new SynapseException(BookErrors.BOOK_NOT_FOUND, "42"));
        GetShelfOperation outer = pipeline.wrapOperation(new GetShelfOperation(in -> {
            try {
                return inner.execute(in);
            } catch (SynapseException e) {
                return "fallback";
            }
        }));

        assertThat(outer.execute("42")).isEqualTo("fallback");

        assertThat(output).doesNotContain(FAILURE_LOGGER);
    }

    @Test
    void asyncNestedCallOnOtherThreadIsOutermostThere(CapturedOutput output) throws Exception {
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

        assertThat(linesContaining(output.getOut(), FAILURE_LOGGER)).isEqualTo(1);
        assertThat(output.getOut())
                .contains("[async-worker]", "Operation get-book failed with BOOK_NOT_FOUND", "correlationId=\"c-4\"");
    }

    @Test
    void depthIsRestoredOnEveryExitPath(CapturedOutput output) {
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
        assertThat(output.getOut()).contains("Operation get-book failed with BOOK_NOT_FOUND");
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
    void nestedErrorPropagatesUnchanged(CapturedOutput output) {
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
        assertThat(output).doesNotContain(FAILURE_LOGGER);
        TestObservationRegistryAssert
                .assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo("synapse.operation", 2)
                .forAllObservationsWithNameEqualTo("synapse.operation",
                        observation -> observation.hasError(error).hasLowCardinalityKeyValue("outcome", "error"));
        assertThat(OperationInterceptor.depth()).isZero();
    }

    @Test
    void nestedForeignExceptionIsLoggedOnceWithItsCause(CapturedOutput output) {
        IllegalStateException cause = new IllegalStateException("disk full");
        GetBookOperation inner = pipeline.wrapOperation(new GetBookOperation(in -> {
            throw cause;
        }));
        GetShelfOperation outer = pipeline.wrapOperation(new GetShelfOperation(inner::execute));

        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> outer.execute("x"));

        assertThat(failure.details()).isEqualTo(SynapseErrors.INTERNAL);
        assertThat(failure.getCause()).isSameAs(cause);
        assertThat(linesContaining(output.getOut(), "failed with")).isEqualTo(1);
        assertThat(output.getOut())
                .contains(" ERROR ", "Operation get-shelf failed with INTERNAL (INTERNAL)",
                        "Caused by: java.lang.IllegalStateException: disk full")
                .doesNotContain("Operation get-book failed");
        TestObservationRegistryAssert
                .assertThat(observations)
                .forAllObservationsWithNameEqualTo("synapse.operation", observation -> observation.hasError(failure));
    }
}
