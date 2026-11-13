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

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.internal.pipeline.ConstraintViolationTranslator;
import io.americanexpress.synapse.core.internal.pipeline.OperationPipeline;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Runs operations in plain unit tests through the real Synapse pipeline, without a Spring context.
 *
 * <pre>{@code
 * assertThatSynapseException(() -> SynapseOperations.invoke(new CreateBook(), new CreateBookRequest("")))
 *         .hasCode(SynapseErrors.VALIDATION_FAILED);
 * }</pre>
 */
public final class SynapseOperations {

    private static final OperationInvoker DEFAULT = with().invoker();

    private SynapseOperations() {
    }

    /**
     * Invokes a service operation with the default settings.
     *
     * @param <I> the input type
     * @param <O> the output type
     * @param operation the operation
     * @param input the input
     * @return the operation's output
     */
    public static <I, O> O invoke(ServiceOperation<I, O> operation, I input) {
        return DEFAULT.invoke(operation, input);
    }

    /**
     * Invokes a business operation with the default settings.
     *
     * @param <I> the input type
     * @param <O> the output type
     * @param operation the business operation
     * @param input the input
     * @return the operation's output
     */
    public static <I, O> O invoke(BusinessOperation<I, O> operation, I input) {
        return DEFAULT.invoke(operation, input);
    }

    /**
     * Starts configuring an invoker.
     *
     * @return a builder
     */
    public static Builder with() {
        return new Builder();
    }

    /**
     * Builds an {@link OperationInvoker}.
     */
    public static final class Builder {

        private final OperationPipeline.Builder pipeline = OperationPipeline.builder();

        private final List<ExceptionTranslator> translators = new ArrayList<>();

        private Builder() {
        }

        /**
         * Sets the clock deadlines are checked against. Default: the system clock.
         *
         * @param clock the clock, typically a {@code MutableClock}
         * @return this builder
         */
        public Builder clock(Clock clock) {
            pipeline.clock(clock);
            return this;
        }

        /**
         * Adds an exception translator. Translators are tried in the order the application tries their beans: by
         * {@code @Order} or {@link org.springframework.core.Ordered} (Synapse's data translators declare one), then those
         * that declare no order, in the order added. As in the application, where its
         * bean has {@code @Order(0)}, Synapse's translator of {@code ConstraintViolationException} into
         * {@code VALIDATION_FAILED} is tried first, unless a translator declares a lower order (such as
         * {@code @Order(-1)}).
         *
         * @param translator the translator
         * @return this builder
         */
        public Builder translator(ExceptionTranslator translator) {
            translators.add(Objects.requireNonNull(translator, "translator"));
            return this;
        }

        /**
         * Sets the observation registry. Default: no observations.
         *
         * @param observationRegistry the registry
         * @return this builder
         */
        public Builder observationRegistry(ObservationRegistry observationRegistry) {
            pipeline.observationRegistry(observationRegistry);
            return this;
        }

        /**
         * Sets the largest page size a page query may ask for. Default: 100.
         *
         * @param maxPageSize the largest page size, at least 1
         * @return this builder
         */
        public Builder maxPageSize(int maxPageSize) {
            pipeline.maxPageSize(maxPageSize);
            return this;
        }

        /**
         * Builds the invoker.
         *
         * @return the invoker
         */
        public OperationInvoker invoker() {
            List<ExceptionTranslator> all = new ArrayList<>();
            all.add(new ConstraintViolationTranslator());
            all.addAll(translators);
            // As the application orders the translator beans (ObjectProvider.orderedStream()): by @Order or Ordered, the
            // constraint translator first (@Order(0)), and translators that declare no order last, in the order added.
            AnnotationAwareOrderComparator.sort(all);
            return new OperationInvoker(pipeline.translators(all).build());
        }
    }
}
