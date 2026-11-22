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

import io.micrometer.observation.Observation;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.OperationKind;

/**
 * The observation context of one operation call ({@value SynapseObservations#OPERATION}). An
 * {@link OperationObservationConvention} reads it to name the observation and derive its key values.
 */
public class OperationObservationContext extends Observation.Context {

    private final String operationName;

    private final OperationKind kind;

    /**
     * Creates the context of one call.
     *
     * @param operationName the operation name, such as {@code get-book}
     * @param kind the operation kind
     */
    public OperationObservationContext(String operationName, OperationKind kind) {
        this.operationName = operationName;
        this.kind = kind;
    }

    /**
     * Returns the operation name.
     *
     * @return the name, such as {@code get-book}
     */
    public String operationName() {
        return operationName;
    }

    /**
     * Returns the operation kind.
     *
     * @return the kind
     */
    public OperationKind kind() {
        return kind;
    }

    /**
     * Returns the failure of the call as a Synapse error. The pipeline records every failure except a JVM
     * {@link Error} as a {@link SynapseException} (translated if needed); {@link #getError()} holds the raw throwable
     * in all cases.
     *
     * @return the error, or {@code null} when the call succeeded or failed with an {@link Error}
     */
    public @Nullable SynapseException error() {
        return getError() instanceof SynapseException synapse ? synapse : null;
    }
}
