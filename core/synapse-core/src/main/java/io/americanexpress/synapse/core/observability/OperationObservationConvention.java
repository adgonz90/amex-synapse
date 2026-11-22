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
import io.micrometer.observation.ObservationConvention;

/**
 * Names operation observations and derives their key values. Define a bean of this type to replace or extend
 * {@link DefaultOperationObservationConvention}; keep the keys of {@link SynapseObservations} so the documented metric
 * and span contract still holds.
 */
public interface OperationObservationConvention extends ObservationConvention<OperationObservationContext> {

    @Override
    default boolean supportsContext(Observation.Context context) {
        return context instanceof OperationObservationContext;
    }
}
