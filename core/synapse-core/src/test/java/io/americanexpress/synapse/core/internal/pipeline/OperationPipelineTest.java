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

import java.util.List;
import jakarta.validation.constraints.NotBlank;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.SaveBookOperation;
import io.americanexpress.synapse.core.operation.BusinessOperation;

import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.CLOCK;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.VALIDATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class OperationPipelineTest {

    private static final String REQUIRED = "is required; pass the version read with the resource, or VersionCondition.any()";

    /** A write whose version is required: the component is not {@code @Nullable} in a {@code @NullMarked} package. */
    record Update(@NotBlank String id, VersionCondition<Long> version) {
    }

    /** A write whose version is optional. */
    record OptionalVersion(String id, @Nullable VersionCondition<Long> version) {
    }

    record Plain(String id) {
    }

    /** A write with two required versions. */
    record Transfer(VersionCondition<Long> from, VersionCondition<Long> to) {
    }

    /**
     * Not a record: the pipeline never reads its fields. The field is a required version (not {@code @Nullable}) set to
     * {@code null}, so a check that read the fields of a class would reject it.
     */
    static final class Holder {

        @SuppressWarnings("NullAway") // The case under test: a required version that is null.
        final VersionCondition<Long> version = null;
    }

    /** A business operation named {@code price-update}. */
    static class PriceUpdate implements BusinessOperation<Update, String> {

        int calls;

        @Override
        public String execute(Update input) {
            calls++;
            return input.id();
        }
    }

    private final OperationPipeline pipeline = OperationPipeline.builder().clock(CLOCK).validator(VALIDATOR).build();

    @Test
    @SuppressWarnings("NullAway") // The case under test: a caller passes null for a required condition.
    void requiredVersionConditionNullIsPreconditionRequired() {
        SaveBookOperation<Update> target = new SaveBookOperation<>(Update::id);
        SaveBookOperation<Update> op = pipeline.wrapOperation(target);

        SynapseException failure = catchThrowableOfType(SynapseException.class,
                () -> op.execute(new Update("b1", null)));

        assertThat(failure.details()).isEqualTo(SynapseErrors.PRECONDITION_REQUIRED);
        assertThat(failure.code()).isEqualTo("PRECONDITION_REQUIRED");
        assertThat(failure.violations()).containsExactly(new Violation("version", REQUIRED));
        assertThat(target.calls).isZero();
    }

    @Test
    @SuppressWarnings("NullAway") // The case under test: a caller passes null for two required conditions.
    void eachMissingRequiredVersionIsItsOwnViolation() {
        SaveBookOperation<Transfer> target = new SaveBookOperation<>(in -> "ran");
        SaveBookOperation<Transfer> op = pipeline.wrapOperation(target);

        SynapseException both = catchThrowableOfType(SynapseException.class,
                () -> op.execute(new Transfer(null, null)));
        SynapseException one = catchThrowableOfType(SynapseException.class,
                () -> op.execute(new Transfer(VersionCondition.any(), null)));

        assertThat(both.details()).isEqualTo(SynapseErrors.PRECONDITION_REQUIRED);
        assertThat(both.violations()).containsExactly(new Violation("from", REQUIRED), new Violation("to", REQUIRED));
        assertThat(one.violations()).containsExactly(new Violation("to", REQUIRED));
        assertThat(target.calls).isZero();
    }

    @Test
    void nullableVersionConditionNullRuns() {
        SaveBookOperation<OptionalVersion> target = new SaveBookOperation<>(OptionalVersion::id);
        SaveBookOperation<OptionalVersion> op = pipeline.wrapOperation(target);

        assertThat(op.execute(new OptionalVersion("b1", null))).isEqualTo("b1");
        assertThat(target.calls).isOne();
    }

    @Test
    void anyVersionRuns() {
        SaveBookOperation<Update> target = new SaveBookOperation<>(Update::id);
        SaveBookOperation<Update> op = pipeline.wrapOperation(target);

        assertThat(op.execute(new Update("b1", VersionCondition.any()))).isEqualTo("b1");
        assertThat(op.execute(new Update("b2", VersionCondition.of(3L)))).isEqualTo("b2");
        assertThat(target.calls).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("NullAway") // The case under test: a caller passes null for a required condition.
    void invalidInputMissingItsVersionIsValidationFailed() {
        SaveBookOperation<Update> target = new SaveBookOperation<>(Update::id);
        SaveBookOperation<Update> op = pipeline.wrapOperation(target);

        SynapseException failure = catchThrowableOfType(SynapseException.class, () -> op.execute(new Update("", null)));

        assertThat(failure.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
        assertThat(failure.violations()).containsExactly(new Violation("id", "must not be blank"));
        assertThat(target.calls).isZero();
    }

    @Test
    @SuppressWarnings("NullAway") // The case under test: a business input with null for a required condition.
    void businessInputIsNotChecked() {
        PriceUpdate target = new PriceUpdate();
        PriceUpdate business = pipeline.wrapBusiness(target);

        assertThat(business.execute(new Update("b1", null))).isEqualTo("b1");
        assertThat(target.calls).isOne();
    }

    @Test
    void nonRecordInputIsNotChecked() {
        SaveBookOperation<Holder> target = new SaveBookOperation<>(in -> "ran");
        SaveBookOperation<Holder> op = pipeline.wrapOperation(target);

        assertThat(op.execute(new Holder())).isEqualTo("ran");
        assertThat(target.calls).isOne();
        assertThat(pipeline.requiredVersionComponents(Holder.class)).isEmpty();
    }

    @Test
    void requiredComponentsAreComputedOncePerType() {
        List<?> first = pipeline.requiredVersionComponents(Update.class);

        assertThat(first).hasSize(1);
        assertThat(pipeline.requiredVersionComponents(Update.class)).isSameAs(first);
        assertThat(pipeline.requiredVersionComponents(OptionalVersion.class)).isEmpty();
        assertThat(pipeline.requiredVersionComponents(Plain.class)).isEmpty();
    }
}
