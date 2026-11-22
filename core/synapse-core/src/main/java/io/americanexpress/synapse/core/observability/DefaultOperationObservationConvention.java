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
package io.americanexpress.synapse.core.observability;

import io.micrometer.common.KeyValues;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

/**
 * The default naming of operation observations: name {@value SynapseObservations#OPERATION}, contextual (span) name
 * the operation name, and the low-cardinality keys {@code operation}, {@code kind}, {@code outcome},
 * {@code error.category}, {@code error.code} and {@code error.type} (OpenTelemetry's name, with the same value as
 * {@code error.code}).
 *
 * <p>
 * The error keys are {@value SynapseObservations#NONE} on success. A call that failed with a JVM {@link Error} (which
 * the pipeline never translates) is tagged {@link ErrorCategory#INTERNAL} with the code
 * {@value SynapseObservations#JVM_ERROR}, so the error keys stay bounded and a JVM error is not mistaken for a
 * translated {@link SynapseErrors#INTERNAL}. Subclass it to add key values.
 */
public class DefaultOperationObservationConvention implements OperationObservationConvention {

    /**
     * Creates the convention.
     */
    public DefaultOperationObservationConvention() {
        // public for subclassing and bean registration
    }

    @Override
    public String getName() {
        return SynapseObservations.OPERATION;
    }

    @Override
    public String getContextualName(OperationObservationContext context) {
        return context.operationName();
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(OperationObservationContext context) {
        String outcome = SynapseObservations.OUTCOME_SUCCESS;
        String category = SynapseObservations.NONE;
        String code = SynapseObservations.NONE;
        Throwable failure = context.getError();
        if (failure != null) {
            outcome = SynapseObservations.OUTCOME_ERROR;
            if (failure instanceof SynapseException error) {
                category = error.category().name();
                code = error.code();
            } else {
                category = ErrorCategory.INTERNAL.name();
                code = failure instanceof Error ? SynapseObservations.JVM_ERROR : SynapseErrors.INTERNAL.code();
            }
        }
        return KeyValues
                .of(SynapseObservations.OPERATION_KEY, context.operationName(), SynapseObservations.KIND_KEY,
                        context.kind().tagValue(), SynapseObservations.OUTCOME_KEY, outcome,
                        SynapseObservations.ERROR_CATEGORY_KEY, category, SynapseObservations.ERROR_CODE_KEY, code,
                        SynapseObservations.ERROR_TYPE_KEY, code);
    }
}
