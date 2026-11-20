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
import java.util.function.Function;
import jakarta.validation.constraints.Positive;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.GetBookOperation;
import io.americanexpress.synapse.core.observability.BusinessObservation;
import io.americanexpress.synapse.core.observability.OperationObservationContext;
import io.americanexpress.synapse.core.operation.BusinessOperation;

import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.CLOCK;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.VALIDATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(OutputCaptureExtension.class)
class BusinessInterceptorTest {

    record PriceRequest(@Positive int quantity) {
    }

    /** A business operation named {@code price-calculator}. */
    static class PriceCalculator implements BusinessOperation<PriceRequest, Integer> {

        private final Function<PriceRequest, Integer> body;

        int calls;

        PriceCalculator(Function<PriceRequest, Integer> body) {
            this.body = body;
        }

        @Override
        public Integer execute(PriceRequest input) {
            calls++;
            return body.apply(input);
        }
    }

    private final TestObservationRegistry observations = TestObservationRegistry.create();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final List<Throwable> translated = new ArrayList<>();

    private OperationPipeline pipeline(BusinessObservation mode) {
        return OperationPipeline
                .builder()
                .clock(CLOCK)
                .validator(VALIDATOR)
                .observationRegistry(observations)
                .meterRegistry(meters)
                .contextBinder(PipelineFixtures.binder())
                .translators(List.of(ex -> {
                    translated.add(ex);
                    return null;
                }))
                .businessObservation(mode)
                .build();
    }

    private static PriceCalculator calculator() {
        return new PriceCalculator(in -> in.quantity() * 10);
    }

    @Test
    void businessInputViolationIsInternal() {
        PriceCalculator target = calculator();
        PriceCalculator business = pipeline(BusinessObservation.SPANS).wrapBusiness(target);

        SynapseException failure = catchThrowableOfType(SynapseException.class,
                () -> business.execute(new PriceRequest(0)));

        // The service built an invalid internal model: its bug, never the caller's, so no violation reaches them.
        assertThat(failure.details()).isEqualTo(SynapseErrors.INTERNAL);
        assertThat(failure.violations()).isEmpty();
        assertThat(failure.getCause()).isInstanceOfSatisfying(SynapseException.class, cause -> {
            assertThat(cause.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
            assertThat(cause.violations()).containsExactly(new Violation("quantity", "must be greater than 0"));
        });
        assertThat(target.calls).isZero();
        assertThat(business.execute(new PriceRequest(2))).isEqualTo(20);
    }

    @Test
    void validationFailedFromTheBusinessBodyPropagatesUnchanged() {
        SynapseException own = SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED,
                        List.of(new Violation("quantity", "is too large")));
        PriceCalculator business = pipeline(BusinessObservation.OFF).wrapBusiness(new PriceCalculator(in -> {
            throw own;
        }));

        assertThatThrownBy(() -> business.execute(new PriceRequest(2))).isSameAs(own);
    }

    @Test
    void businessInputViolationIsInternalInEveryMode() {
        for (BusinessObservation mode : BusinessObservation.values()) {
            PriceCalculator business = pipeline(mode).wrapBusiness(calculator());

            assertThat(catchThrowableOfType(SynapseException.class, () -> business.execute(new PriceRequest(0)))
                    .details()).as(mode.name()).isEqualTo(SynapseErrors.INTERNAL);
        }
    }

    @Test
    void businessHasNoContextStep() {
        PriceCalculator business = pipeline(BusinessObservation.SPANS)
                .wrapBusiness(new PriceCalculator(in -> RequestContext.find().isPresent() ? 1 : 0));

        assertThat(business.execute(new PriceRequest(1))).isZero();
    }

    @Test
    void businessSpansMode() {
        PriceCalculator business = pipeline(BusinessObservation.SPANS).wrapBusiness(calculator());

        business.execute(new PriceRequest(1));

        TestObservationRegistryAssert
                .assertThat(observations)
                .hasSingleObservationThat()
                .hasNameEqualTo("synapse.business")
                .hasContextualNameEqualTo("price-calculator")
                .hasLowCardinalityKeyValue("business", "price-calculator")
                .hasLowCardinalityKeyValue("outcome", "success")
                .hasLowCardinalityKeyValue("error.type", "none")
                .doesNotHaveError()
                .hasBeenStopped();
    }

    @Test
    void callThroughTheContractRunsThePipelineOnce() {
        PriceCalculator target = calculator();
        BusinessOperation<PriceRequest, Integer> business = pipeline(BusinessObservation.SPANS).wrapBusiness(target);

        assertThat(business.execute(new PriceRequest(1))).isEqualTo(10);

        assertThat(target.calls).isEqualTo(1);
        TestObservationRegistryAssert
                .assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo("synapse.business", 1);
    }

    /** A business operation named {@code discount} with an overload that is not the contract method. */
    static class Discount implements BusinessOperation<PriceRequest, Integer> {

        @Override
        public Integer execute(PriceRequest input) {
            return input.quantity();
        }

        public Integer execute(String code) {
            return code.length();
        }
    }

    @Test
    void businessOverloadedExecuteBypassesThePipeline() {
        Discount business = pipeline(BusinessObservation.SPANS).wrapBusiness(new Discount());

        assertThat(business.execute("ABC")).isEqualTo(3);
        TestObservationRegistryAssert.assertThat(observations).doesNotHaveAnyObservation();

        assertThat(business.execute(new PriceRequest(4))).isEqualTo(4);
        TestObservationRegistryAssert
                .assertThat(observations)
                .hasSingleObservationThat()
                .hasLowCardinalityKeyValue("business", "discount");
    }

    @Test
    void businessSpansModeRecordsFailures() {
        IllegalStateException failure = new IllegalStateException("boom");
        PriceCalculator business = pipeline(BusinessObservation.SPANS).wrapBusiness(new PriceCalculator(in -> {
            throw failure;
        }));

        assertThatThrownBy(() -> business.execute(new PriceRequest(1))).isSameAs(failure);

        TestObservationRegistryAssert
                .assertThat(observations)
                .hasSingleObservationThat()
                .hasNameEqualTo("synapse.business")
                .hasError(failure)
                .hasLowCardinalityKeyValue("outcome", "error")
                .hasLowCardinalityKeyValue("error.type", "_OTHER")
                .hasBeenStopped();
    }

    @Test
    void businessErrorTypeIsTheSynapseCodeOrErrorForJvmErrors() {
        PriceCalculator business = pipeline(BusinessObservation.SPANS).wrapBusiness(new PriceCalculator(in -> {
            if (in.quantity() == 1) {
                throw new SynapseException(SynapseErrors.CONFLICT);
            }
            throw new StackOverflowError();
        }));

        assertThatThrownBy(() -> business.execute(new PriceRequest(1))).isInstanceOf(SynapseException.class);
        assertThatThrownBy(() -> business.execute(new PriceRequest(2))).isInstanceOf(StackOverflowError.class);

        TestObservationRegistryAssert
                .assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo("synapse.business", 2)
                .hasAnObservation(observation -> observation.hasLowCardinalityKeyValue("error.type", "CONFLICT"))
                .hasAnObservation(observation -> observation.hasLowCardinalityKeyValue("error.type", "ERROR"));
    }

    @Test
    void businessMetricsModeTagsTheErrorType() {
        PriceCalculator business = pipeline(BusinessObservation.METRICS).wrapBusiness(new PriceCalculator(in -> {
            if (in.quantity() == 1) {
                throw new SynapseException(SynapseErrors.CONFLICT);
            }
            throw new StackOverflowError();
        }));

        assertThatThrownBy(() -> business.execute(new PriceRequest(1))).isInstanceOf(SynapseException.class);
        assertThatThrownBy(() -> business.execute(new PriceRequest(2))).isInstanceOf(StackOverflowError.class);

        assertThat(meters.get("synapse.business").tag("error.type", "CONFLICT").timer().count()).isEqualTo(1);
        assertThat(meters.get("synapse.business").tag("error.type", "ERROR").timer().count()).isEqualTo(1);
    }

    @Test
    void businessObservationIsChildOfOperation() {
        OperationPipeline pipeline = pipeline(BusinessObservation.SPANS);
        PriceCalculator business = pipeline.wrapBusiness(calculator());
        GetBookOperation op = pipeline
                .wrapOperation(new GetBookOperation(in -> String.valueOf(business.execute(new PriceRequest(3)))));

        assertThat(op.execute("x")).isEqualTo("30");

        TestObservationRegistryAssert
                .assertThat(observations)
                .hasNumberOfObservationsEqualTo(2)
                .hasObservationWithNameEqualTo("synapse.business")
                .that()
                .hasParentObservationContextMatching(OperationObservationContext.class::isInstance);
    }

    @Test
    void businessMetricsMode() {
        PriceCalculator business = pipeline(BusinessObservation.METRICS).wrapBusiness(new PriceCalculator(in -> {
            if (in.quantity() > 5) {
                throw new IllegalStateException("too many");
            }
            return in.quantity();
        }));

        business.execute(new PriceRequest(1));
        business.execute(new PriceRequest(2));
        assertThatThrownBy(() -> business.execute(new PriceRequest(6))).isInstanceOf(IllegalStateException.class);

        TestObservationRegistryAssert.assertThat(observations).doesNotHaveAnyObservation();
        assertThat(meters
                .get("synapse.business")
                .tag("business", "price-calculator")
                .tag("outcome", "success")
                .tag("error.type", "none")
                .timer()
                .count()).isEqualTo(2);
        assertThat(meters
                .get("synapse.business")
                .tag("business", "price-calculator")
                .tag("outcome", "error")
                .tag("error.type", "_OTHER")
                .timer()
                .count()).isEqualTo(1);
    }

    @Test
    void businessMetricsModeWithoutMeterRegistryRecordsNothing() {
        PriceCalculator business = OperationPipeline
                .builder()
                .validator(VALIDATOR)
                .observationRegistry(observations)
                .meterRegistry(null)
                .businessObservation(BusinessObservation.METRICS)
                .build()
                .wrapBusiness(calculator());

        assertThat(business.execute(new PriceRequest(1))).isEqualTo(10);

        TestObservationRegistryAssert.assertThat(observations).doesNotHaveAnyObservation();
    }

    @Test
    void businessOffMode() {
        PriceCalculator business = pipeline(BusinessObservation.OFF).wrapBusiness(calculator());

        assertThat(business.execute(new PriceRequest(1))).isEqualTo(10);

        TestObservationRegistryAssert.assertThat(observations).doesNotHaveAnyObservation();
        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void businessExceptionsPropagateUnchanged(CapturedOutput output) {
        IllegalStateException failure = new IllegalStateException("boom");
        PriceCalculator business = pipeline(BusinessObservation.SPANS).wrapBusiness(new PriceCalculator(in -> {
            throw failure;
        }));

        assertThatThrownBy(() -> business.execute(new PriceRequest(1))).isSameAs(failure);

        assertThat(translated).isEmpty();
        assertThat(output).doesNotContain(PipelineFixtures.FAILURE_LOGGER);
    }

    @Test
    void businessOtherMethodsBypassThePipeline() {
        PriceCalculator business = pipeline(BusinessObservation.SPANS).wrapBusiness(calculator());

        assertThat(business.toString()).isNotBlank();

        TestObservationRegistryAssert.assertThat(observations).doesNotHaveAnyObservation();
    }
}
