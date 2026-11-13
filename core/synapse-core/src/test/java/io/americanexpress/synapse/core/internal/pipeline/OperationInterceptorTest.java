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
import java.util.UUID;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;

import ch.qos.logback.classic.Level;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.InboundContext;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.BookErrors;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.GetBookOperation;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.SaveBookOperation;
import io.americanexpress.synapse.core.observability.DefaultOperationObservationConvention;
import io.americanexpress.synapse.core.observability.OperationObservationContext;
import io.americanexpress.synapse.core.operation.OperationKind;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;
import io.americanexpress.synapse.core.paging.PageQuery;

import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.CLOCK;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.FAILURE_LOGGER;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.NOW;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.VALIDATOR;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.linesContaining;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.setLevel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class OperationInterceptorTest {

    record CreateBookRequest(@NotBlank String title) {
    }

    record Plain(String a) {
    }

    record Search(@Nullable PageQuery page, String text) {
    }

    private final TestObservationRegistry observations = TestObservationRegistry.create();

    private final ContextBinder binder = PipelineFixtures.binder();

    private @Nullable Level previousLevel;

    @BeforeEach
    void infoLevel() {
        previousLevel = setLevel(FAILURE_LOGGER, Level.INFO);
    }

    @AfterEach
    void restoreLevel() {
        setLevel(FAILURE_LOGGER, previousLevel);
    }

    private OperationPipeline.Builder pipeline() {
        return OperationPipeline
                .builder()
                .clock(CLOCK)
                .validator(VALIDATOR)
                .observationRegistry(observations)
                .contextBinder(binder);
    }

    private static GetBookOperation failing(RuntimeException failure) {
        return new GetBookOperation(in -> {
            throw failure;
        });
    }

    private static SynapseException bookNotFound() {
        return new SynapseException(BookErrors.BOOK_NOT_FOUND, "42");
    }

    private ContextScope open(String correlationId) {
        return binder.open(new InboundContext(correlationId, Map.of(), null, null, null));
    }

    @Test
    void createsContextWhenNoneExists() {
        SaveBookOperation<String> op = pipeline().build().wrapOperation(new SaveBookOperation<>(in -> {
            RequestContext context = RequestContext.current();
            return context.correlationId() + "|" + context.principal().isPresent();
        }));

        String[] seen = op.execute("x").split("\\|", -1);

        assertThat(UUID.fromString(seen[0])).hasToString(seen[0]);
        assertThat(seen[1]).isEqualTo("false");
        assertThat(RequestContext.find()).isEmpty();
    }

    @Test
    void reusesExistingContext() {
        GetBookOperation op = pipeline()
                .build()
                .wrapOperation(new GetBookOperation(in -> RequestContext.current().correlationId()));

        try (ContextScope scope = open("c-1")) {
            assertThat(op.execute("x")).isEqualTo("c-1");
            assertThat(RequestContext.current()).isSameAs(scope.context());
        }
    }

    @Test
    void expiredDeadlineFailsWithoutExecuting() {
        GetBookOperation target = new GetBookOperation(in -> "ok");
        GetBookOperation op = pipeline().build().wrapOperation(target);

        try (ContextScope scope = binder.open(new InboundContext("c-1", Map.of(), null, null, NOW))) {
            SynapseException failure = catchThrowableOfType(SynapseException.class, () -> op.execute("x"));
            assertThat(failure.code()).isEqualTo("DEADLINE_EXCEEDED");
            assertThat(failure.category()).isEqualTo(ErrorCategory.TIMEOUT);
        }
        assertThat(target.calls).isZero();

        try (ContextScope scope = binder.open(new InboundContext("c-2", Map.of(), null, null, NOW.plusMillis(1)))) {
            assertThat(op.execute("x")).isEqualTo("ok");
        }
        assertThat(target.calls).isEqualTo(1);
    }

    @Test
    void validatesConstrainedInput() {
        SaveBookOperation<CreateBookRequest> target = new SaveBookOperation<>(CreateBookRequest::title);
        SaveBookOperation<CreateBookRequest> op = pipeline().build().wrapOperation(target);

        SynapseException failure = catchThrowableOfType(SynapseException.class,
                () -> op.execute(new CreateBookRequest("")));

        assertThat(failure.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
        assertThat(failure.violations()).containsExactly(new Violation("title", "must not be blank"));
        assertThat(target.calls).isZero();
        assertThat(op.execute(new CreateBookRequest("Dune"))).isEqualTo("Dune");
    }

    @Test
    void skipsValidationForUnconstrainedTypes() {
        Validator validator = spy(VALIDATOR);
        SaveBookOperation<Object> op = pipeline()
                .validator(validator)
                .build()
                .wrapOperation(new SaveBookOperation<>(String::valueOf));

        op.execute(new Plain("a"));
        op.execute(new Plain("b"));
        op.execute(new CreateBookRequest("Dune"));
        op.execute(new CreateBookRequest("Emma"));

        verify(validator, times(1)).getConstraintsForClass(Plain.class);
        verify(validator, times(1)).getConstraintsForClass(CreateBookRequest.class);
        verify(validator, never()).validate(any(Plain.class), any(Class[].class));
        verify(validator, times(2)).validate(any(CreateBookRequest.class), any(Class[].class));
    }

    @Test
    void pageSizeAboveCapIsInvalidInput() {
        SaveBookOperation<Object> target = new SaveBookOperation<>(in -> "ok");
        SaveBookOperation<Object> op = pipeline().maxPageSize(100).build().wrapOperation(target);

        SynapseException failure = catchThrowableOfType(SynapseException.class,
                () -> op.execute(new Search(PageQuery.first(101), "q")));
        assertThat(failure.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
        assertThat(failure.violations())
                .containsExactly(new Violation("page.size", "must be less than or equal to 100"));

        SynapseException direct = catchThrowableOfType(SynapseException.class, () -> op.execute(PageQuery.first(0)));
        assertThat(direct.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
        assertThat(direct.violations()).containsExactly(new Violation("size", "must be greater than or equal to 1"));

        assertThat(target.calls).isZero();
        assertThat(op.execute(new Search(PageQuery.first(100), "q"))).isEqualTo("ok");
        assertThat(op.execute(new Search(null, "q"))).isEqualTo("ok");
        assertThat(op.execute(PageQuery.first(100))).isEqualTo("ok");
    }

    @Test
    void pageSizeCapDefaultsToOneHundred() {
        SaveBookOperation<Object> op = OperationPipeline
                .builder()
                .build()
                .wrapOperation(new SaveBookOperation<>(in -> "ok"));

        assertThat(op.execute(PageQuery.first(100))).isEqualTo("ok");
        SynapseException failure = catchThrowableOfType(SynapseException.class,
                () -> op.execute(PageQuery.first(101)));
        assertThat(failure.violations()).containsExactly(new Violation("size", "must be less than or equal to 100"));
    }

    @Test
    void synapseExceptionPassesThroughUnchanged() {
        SynapseException thrown = bookNotFound();
        GetBookOperation op = pipeline().build().wrapOperation(failing(thrown));

        assertThatThrownBy(() -> op.execute("42")).isSameAs(thrown);
    }

    @Test
    void translatorTurnsForeignExceptionIntoSynapse() {
        List<String> called = new ArrayList<>();
        ExceptionTranslator none = ex -> {
            called.add("none");
            return null;
        };
        ExceptionTranslator conflict = ex -> {
            called.add("conflict");
            return ex instanceof IllegalStateException ? new SynapseException(SynapseErrors.CONFLICT, ex) : null;
        };
        ExceptionTranslator precondition = ex -> {
            called.add("precondition");
            return new SynapseException(SynapseErrors.PRECONDITION_FAILED, ex);
        };
        IllegalStateException original = new IllegalStateException("busy");
        GetBookOperation op = pipeline()
                .translators(List.of(none, conflict, precondition))
                .build()
                .wrapOperation(failing(original));

        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> op.execute("x"));

        assertThat(failure.details()).isEqualTo(SynapseErrors.CONFLICT);
        assertThat(failure.getCause()).isSameAs(original);
        assertThat(called).containsExactly("none", "conflict");
    }

    @Test
    void throwingTranslatorIsSkipped() {
        ExceptionTranslator broken = ex -> {
            throw new IllegalArgumentException("translator bug");
        };
        ExceptionTranslator conflict = ex -> new SynapseException(SynapseErrors.CONFLICT, ex);

        GetBookOperation translated = pipeline()
                .translators(List.of(broken, conflict))
                .build()
                .wrapOperation(failing(new IllegalStateException("busy")));
        assertThat(catchThrowableOfType(SynapseException.class, () -> translated.execute("x")).details())
                .isEqualTo(SynapseErrors.CONFLICT);

        GetBookOperation untranslated = pipeline()
                .translators(List.of(broken))
                .build()
                .wrapOperation(failing(new IllegalStateException("busy")));
        assertThat(catchThrowableOfType(SynapseException.class, () -> untranslated.execute("x")).details())
                .isEqualTo(SynapseErrors.INTERNAL);
    }

    @Test
    void untranslatedExceptionBecomesInternalWithCause() {
        RuntimeException original = new RuntimeException("db password=x");
        GetBookOperation op = pipeline().build().wrapOperation(failing(original));

        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> op.execute("x"));

        assertThat(failure.code()).isEqualTo("INTERNAL");
        assertThat(failure.category()).isEqualTo(ErrorCategory.INTERNAL);
        assertThat(failure.getCause()).isSameAs(original);
        assertThat(failure.getMessage()).isEqualTo("An internal error occurred").doesNotContain("password");
    }

    @Test
    void errorsAreNotTranslated(CapturedOutput output) {
        StackOverflowError error = new StackOverflowError();
        List<Throwable> translated = new ArrayList<>();
        GetBookOperation op = pipeline().translators(List.of(ex -> {
            translated.add(ex);
            return null;
        })).build().wrapOperation(new GetBookOperation(in -> {
            throw error;
        }));

        assertThatThrownBy(() -> op.execute("x")).isSameAs(error);

        assertThat(translated).isEmpty();
        assertThat(RequestContext.find()).isEmpty();
        TestObservationRegistryAssert
                .assertThat(observations)
                .hasSingleObservationThat()
                .hasNameEqualTo("synapse.operation")
                .hasError(error)
                .hasLowCardinalityKeyValue("outcome", "error")
                .hasLowCardinalityKeyValue("error.category", "INTERNAL")
                .hasLowCardinalityKeyValue("error.code", "ERROR")
                .hasLowCardinalityKeyValue("error.type", "ERROR")
                .hasBeenStopped();
        assertThat(output).doesNotContain("failed with");
    }

    @Test
    void observesSuccess() {
        GetBookOperation op = pipeline()
                .build()
                .wrapOperation(new GetBookOperation(in -> RequestContext.current().correlationId()));

        String correlationId = op.execute("x");

        TestObservationRegistryAssert
                .assertThat(observations)
                .hasSingleObservationThat()
                .hasNameEqualTo("synapse.operation")
                .hasContextualNameEqualTo("get-book")
                .hasLowCardinalityKeyValue("operation", "get-book")
                .hasLowCardinalityKeyValue("kind", "query")
                .hasLowCardinalityKeyValue("outcome", "success")
                .hasLowCardinalityKeyValue("error.category", "none")
                .hasLowCardinalityKeyValue("error.code", "none")
                .hasLowCardinalityKeyValue("error.type", "none")
                .hasHighCardinalityKeyValue("correlation.id", correlationId)
                .doesNotHaveError()
                .hasBeenStopped();
    }

    @Test
    void callThroughTheContractRunsThePipelineOnce() {
        GetBookOperation target = new GetBookOperation(in -> in);
        ServiceOperation<String, String> op = pipeline().build().wrapOperation(target);

        assertThat(op.execute("x")).isEqualTo("x");

        assertThat(target.calls).isEqualTo(1);
        TestObservationRegistryAssert
                .assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo("synapse.operation", 1);
    }

    @Test
    void observesCommandKind() {
        SaveBookOperation<String> op = pipeline().build().wrapOperation(new SaveBookOperation<>(in -> in));

        op.execute("x");

        TestObservationRegistryAssert
                .assertThat(observations)
                .hasSingleObservationThat()
                .hasLowCardinalityKeyValue("operation", "save-book")
                .hasLowCardinalityKeyValue("kind", "command");
    }

    @Test
    void observesFailureTags() {
        SynapseException thrown = bookNotFound();
        GetBookOperation op = pipeline().build().wrapOperation(failing(thrown));

        assertThatThrownBy(() -> op.execute("42")).isSameAs(thrown);

        TestObservationRegistryAssert
                .assertThat(observations)
                .hasSingleObservationThat()
                .hasNameEqualTo("synapse.operation")
                .hasError(thrown)
                .hasLowCardinalityKeyValue("outcome", "error")
                .hasLowCardinalityKeyValue("error.category", "NOT_FOUND")
                .hasLowCardinalityKeyValue("error.code", "BOOK_NOT_FOUND")
                .hasLowCardinalityKeyValue("error.type", "BOOK_NOT_FOUND")
                .hasBeenStopped();
        TestObservationRegistryAssert
                .assertThat(observations)
                .hasHandledContextsThatSatisfy(contexts -> assertThat(contexts)
                        .singleElement()
                        .isInstanceOfSatisfying(OperationObservationContext.class, context -> {
                            assertThat(context.error()).isSameAs(thrown);
                            assertThat(context.operationName()).isEqualTo("get-book");
                        }));
    }

    @Test
    void defaultConventionTagsAnUntranslatedExceptionAsInternal() {
        OperationObservationContext context = new OperationObservationContext("get-book", OperationKind.QUERY);
        context.setError(new IllegalStateException("recorded by a custom caller"));

        assertThat(new DefaultOperationObservationConvention().getLowCardinalityKeyValues(context))
                .extracting(KeyValue::getKey, KeyValue::getValue)
                .contains(tuple("outcome", "error"), tuple("error.category", "INTERNAL"),
                        tuple("error.code", "INTERNAL"), tuple("error.type", "INTERNAL"));
    }

    @Test
    void customConventionReplacesTheDefault() {
        GetBookOperation op = pipeline().observationConvention(new DefaultOperationObservationConvention() {

            @Override
            public String getName() {
                return "custom.operation";
            }
        }).build().wrapOperation(new GetBookOperation(in -> "ok"));

        op.execute("x");

        TestObservationRegistryAssert
                .assertThat(observations)
                .hasSingleObservationThat()
                .hasNameEqualTo("custom.operation")
                .hasLowCardinalityKeyValue("operation", "get-book");
    }

    @Test
    void logsClientErrorAtWarnWithoutStackTrace(CapturedOutput output) {
        GetBookOperation op = pipeline().build().wrapOperation(failing(bookNotFound()));

        try (ContextScope scope = open("c-7")) {
            assertThatThrownBy(() -> op.execute("42")).isInstanceOf(SynapseException.class);
        }

        assertThat(linesContaining(output.getOut(), FAILURE_LOGGER)).isEqualTo(1);
        String line = output
                .getOut()
                .lines()
                .filter(text -> text.contains(FAILURE_LOGGER))
                .findFirst()
                .orElseThrow();
        assertThat(line)
                .contains(" WARN ", "Operation get-book failed with BOOK_NOT_FOUND (NOT_FOUND): Book 42 was not found",
                        "operation=\"get-book\"", "errorCode=\"BOOK_NOT_FOUND\"", "errorCategory=\"NOT_FOUND\"",
                        "correlationId=\"c-7\"");
        assertThat(output).doesNotContain("\tat ", "Exception:");
    }

    @Test
    void logsInternalAtErrorWithStackTrace(CapturedOutput output) {
        GetBookOperation op = pipeline().build().wrapOperation(failing(new IllegalStateException("db password=x")));

        try (ContextScope scope = open("c-8")) {
            assertThatThrownBy(() -> op.execute("42")).isInstanceOf(SynapseException.class);
        }

        assertThat(output.getOut())
                .contains(" ERROR ", "Operation get-book failed with INTERNAL (INTERNAL): An internal error occurred",
                        "correlationId=\"c-8\"", "errorCode=\"INTERNAL\"", "errorCategory=\"INTERNAL\"",
                        "Caused by: java.lang.IllegalStateException: db password=x",
                        "\tat io.americanexpress.synapse.core.internal.pipeline.");
        assertThat(output.getOut()).doesNotContain(" WARN ");
    }

    @Test
    void leavesTheCorrelationIdToTheMdcWhenItHoldsIt(CapturedOutput output) {
        GetBookOperation op = pipeline().build().wrapOperation(failing(bookNotFound()));

        // As Boot's correlation fields do with tracing on: a key-value of the same name would make Boot's Logback
        // structured formats drop the line.
        try (ContextScope scope = open("c-11"); MDC.MDCCloseable mdc = MDC.putCloseable("correlationId", "c-11")) {
            assertThatThrownBy(() -> op.execute("42")).isInstanceOf(SynapseException.class);
        }

        assertThat(output.getOut()).contains("Operation get-book failed with BOOK_NOT_FOUND", "operation=\"get-book\"");
        assertThat(output.getOut()).doesNotContain("correlationId=");
    }

    @Test
    void translatedInternalIsLoggedAtError(CapturedOutput output) {
        GetBookOperation op = pipeline()
                .translators(List.of(ex -> new SynapseException(SynapseErrors.INTERNAL, ex)))
                .build()
                .wrapOperation(failing(new IllegalStateException("boom")));

        assertThatThrownBy(() -> op.execute("42")).isInstanceOf(SynapseException.class);

        assertThat(output.getOut())
                .contains(" ERROR ", "failed with INTERNAL", "Caused by: java.lang.IllegalStateException: boom");
    }

    @ParameterizedTest
    @EnumSource(value = SynapseErrors.class, names = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
    void unavailableAndTimeoutLogTheirCauseAtWarn(SynapseErrors error, CapturedOutput output) {
        GetBookOperation op = pipeline()
                .translators(List.of(ex -> new SynapseException(error, ex)))
                .build()
                .wrapOperation(failing(new IllegalStateException("connection refused")));

        assertThatThrownBy(() -> op.execute("42")).isInstanceOf(SynapseException.class);

        assertThat(output.getOut())
                .contains(" WARN ", "failed with " + error.code() + " (" + error.category() + ")",
                        "java.lang.IllegalStateException: connection refused",
                        "\tat io.americanexpress.synapse.core.internal.pipeline.")
                .doesNotContain(" ERROR ", "SynapseException");
    }

    @Test
    void unavailableWithoutACauseIsOneLine(CapturedOutput output) {
        GetBookOperation op = pipeline()
                .build()
                .wrapOperation(failing(new SynapseException(SynapseErrors.UNAVAILABLE)));

        assertThatThrownBy(() -> op.execute("42")).isInstanceOf(SynapseException.class);

        assertThat(output.getOut()).contains(" WARN ", "failed with UNAVAILABLE (UNAVAILABLE)").doesNotContain("\tat ");
    }

    @Test
    void clientCategoriesNeverLogTheirCause(CapturedOutput output) {
        GetBookOperation op = pipeline()
                .translators(List.of(ex -> new SynapseException(SynapseErrors.CONFLICT, ex)))
                .build()
                .wrapOperation(failing(new IllegalStateException("version mismatch")));

        assertThatThrownBy(() -> op.execute("42")).isInstanceOf(SynapseException.class);

        assertThat(output.getOut())
                .contains(" WARN ", "failed with CONFLICT (CONFLICT)")
                .doesNotContain("version mismatch", "\tat ");
    }

    @Test
    void successLogsNothing(CapturedOutput output) {
        setLevel(FAILURE_LOGGER, Level.TRACE);
        GetBookOperation op = pipeline().build().wrapOperation(new GetBookOperation(in -> "ok"));

        assertThat(op.execute("x")).isEqualTo("ok");

        assertThat(output).doesNotContain(FAILURE_LOGGER);
    }

    @Test
    void countsInflightAndRejectsWhileDraining(CapturedOutput output) {
        InflightTracker tracker = new InflightTracker();
        OperationPipeline pipeline = pipeline().inflight(tracker).build();
        GetBookOperation inner = pipeline
                .wrapOperation(new GetBookOperation(in -> String.valueOf(tracker.inflight())));
        GetBookOperation outerTarget = new GetBookOperation(in -> tracker.inflight() + "," + inner.execute(in));
        GetBookOperation outer = pipeline.wrapOperation(outerTarget);

        assertThat(outer.execute("x")).isEqualTo("1,1");
        assertThat(tracker.inflight()).isZero();

        tracker.startDraining();
        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> outer.execute("x"));

        assertThat(failure.details()).isEqualTo(SynapseErrors.UNAVAILABLE);
        assertThat(outerTarget.calls).isEqualTo(1);
        assertThat(tracker.inflight()).isZero();
        assertThat(RequestContext.find()).isEmpty();
        assertThat(output).contains(" WARN ", "failed with UNAVAILABLE (UNAVAILABLE)").doesNotContain("\tat ");
    }

    @Test
    void failingCallsLeaveNoContextOrInflightBehind() {
        InflightTracker tracker = new InflightTracker();
        GetBookOperation op = pipeline()
                .inflight(tracker)
                .build()
                .wrapOperation(failing(new IllegalStateException("boom")));

        assertThatThrownBy(() -> op.execute("x")).isInstanceOf(SynapseException.class);

        assertThat(tracker.inflight()).isZero();
        assertThat(RequestContext.find()).isEmpty();
        assertThat(OperationInterceptor.depth()).isZero();
    }

    @Test
    void otherMethodsBypassThePipeline() {
        GetBookOperation op = pipeline().build().wrapOperation(new GetBookOperation(in -> "ok"));

        assertThat(op.toString()).isNotBlank();
        assertThat(op.equals(op)).isTrue();

        TestObservationRegistryAssert.assertThat(observations).doesNotHaveAnyObservation();
    }

    @Test
    void wrapRejectsWrongTypes() {
        OperationPipeline pipeline = pipeline().build();

        assertThatThrownBy(() -> pipeline.wrapOperation(new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ServiceOperation");
        assertThatThrownBy(() -> pipeline.wrapBusiness(new GetBookOperation(in -> in)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BusinessOperation");
    }

    @Test
    void maxPageSizeBelowOneIsRejected() {
        OperationPipeline.Builder builder = OperationPipeline.builder();

        assertThatThrownBy(() -> builder.maxPageSize(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPageSize");
        assertThat(builder.maxPageSize(1)).isSameAs(builder);
    }

    @Test
    void overloadedExecuteBypassesThePipeline() {
        LookupBookOperation target = new LookupBookOperation();
        LookupBookOperation op = pipeline().build().wrapOperation(target);

        assertThat(op.execute(7)).isEqualTo("id 7");
        TestObservationRegistryAssert.assertThat(observations).doesNotHaveAnyObservation();

        assertThat(op.execute("dune")).isEqualTo("book dune");
        ServiceOperation<String, String> contract = op;
        assertThat(contract.execute("emma")).isEqualTo("book emma");
        TestObservationRegistryAssert
                .assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo("synapse.operation", 2);
    }

    @Test
    void drainingRejectsCallsWithoutContext() {
        InflightTracker tracker = new InflightTracker();
        GetBookOperation target = new GetBookOperation(in -> "ok");
        GetBookOperation op = pipeline().inflight(tracker).build().wrapOperation(target);
        tracker.startDraining();

        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> op.execute("x"));

        assertThat(failure.details()).isEqualTo(SynapseErrors.UNAVAILABLE);
        assertThat(target.calls).isZero();
        assertThat(tracker.inflight()).isZero();
    }

    @Test
    void drainingAdmitsCallsInsideAnExistingContext() {
        InflightTracker tracker = new InflightTracker();
        GetBookOperation op = pipeline()
                .inflight(tracker)
                .build()
                .wrapOperation(new GetBookOperation(in -> String.valueOf(tracker.inflight())));
        tracker.startDraining();

        try (ContextScope scope = open("c-10")) {
            assertThat(op.execute("x")).isEqualTo("1");
        }

        assertThat(tracker.inflight()).isZero();
    }

    @Test
    void throwingTranslatorLogsItsFailure(CapturedOutput output) {
        GetBookOperation op = pipeline().translators(List.of(ex -> {
            throw new IllegalArgumentException("translator bug");
        })).build().wrapOperation(failing(new IllegalStateException("busy")));

        assertThatThrownBy(() -> op.execute("x")).isInstanceOf(SynapseException.class);

        String line = output
                .getOut()
                .lines()
                .filter(text -> text.contains("failed; trying the next one"))
                .findFirst()
                .orElseThrow();
        assertThat(line).contains(" WARN ", OperationInterceptor.class.getName(), "Exception translator");
        assertThat(output.getOut()).contains("java.lang.IllegalArgumentException: translator bug");
    }

    @Test
    void closeFailuresKeepTheResult(CapturedOutput output) {
        ObservationRegistry registry = failingScopeAndStopRegistry();
        InflightTracker tracker = new InflightTracker();
        GetBookOperation op = pipeline()
                .observationRegistry(registry)
                .contextBinder(failingCloseBinder())
                .inflight(tracker)
                .build()
                .wrapOperation(new GetBookOperation(in -> "ok"));

        try {
            assertThat(op.execute("x")).isEqualTo("ok");
        } finally {
            registry.setCurrentObservationScope(null);
        }

        assertThat(tracker.inflight()).isZero();
        assertThat(RequestContext.find()).isEmpty();
        assertThat(OperationInterceptor.depth()).isZero();
        assertThat(linesContaining(output.getOut(), "Operation get-book could not release")).isEqualTo(3);
        assertThat(output.getOut())
                .contains("could not release its context scope", "could not release its observation scope",
                        "could not release its observation")
                .doesNotContain("failed with");
    }

    @Test
    void closeFailuresKeepTheFailure(CapturedOutput output) {
        ObservationRegistry registry = failingScopeAndStopRegistry();
        SynapseException thrown = bookNotFound();
        GetBookOperation op = pipeline()
                .observationRegistry(registry)
                .contextBinder(failingCloseBinder())
                .build()
                .wrapOperation(failing(thrown));

        try {
            assertThatThrownBy(() -> op.execute("42")).isSameAs(thrown);
        } finally {
            registry.setCurrentObservationScope(null);
        }

        assertThat(RequestContext.find()).isEmpty();
        assertThat(linesContaining(output.getOut(), "Operation get-book could not release")).isEqualTo(3);
        assertThat(linesContaining(output.getOut(), "Operation get-book failed with BOOK_NOT_FOUND")).isEqualTo(1);
    }

    /** A registry whose handler throws when a scope closes and when an observation stops. */
    private static ObservationRegistry failingScopeAndStopRegistry() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onScopeClosed(Observation.Context context) {
                throw new IllegalStateException("scope close failed");
            }

            @Override
            public void onStop(Observation.Context context) {
                throw new IllegalStateException("stop failed");
            }
        });
        return registry;
    }

    /** A binder whose scopes restore the thread and then throw on close. */
    private ContextBinder failingCloseBinder() {
        ContextBinder failing = mock(ContextBinder.class);
        when(failing.open(any())).thenAnswer(call -> {
            ContextScope real = binder.open(call.getArgument(0));
            return new ContextScope() {

                @Override
                public RequestContext context() {
                    return real.context();
                }

                @Override
                public void close() {
                    real.close();
                    throw new IllegalStateException("context close failed");
                }
            };
        });
        return failing;
    }

    /** A query named {@code lookup-book} with an overload of {@code execute} that is not the contract method. */
    static class LookupBookOperation implements QueryOperation<String, String> {

        @Override
        public String execute(String input) {
            return "book " + input;
        }

        public String execute(Integer id) {
            return "id " + id;
        }
    }
}
