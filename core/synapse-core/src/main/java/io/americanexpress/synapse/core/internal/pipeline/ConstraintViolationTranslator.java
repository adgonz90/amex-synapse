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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;

import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;

/**
 * Translates a {@link ConstraintViolationException} thrown by an operation (for example by Spring method validation
 * or by the persistence provider) into {@code VALIDATION_FAILED} with its violations, sorted by field then message as
 * the pipeline's own validation step does.
 */
@Order(0)
public final class ConstraintViolationTranslator implements ExceptionTranslator {

    @Override
    public @Nullable SynapseException translate(Throwable exception) {
        if (!(exception instanceof ConstraintViolationException constraintViolations)) {
            return null;
        }
        List<Violation> violations = new ArrayList<>();
        Set<ConstraintViolation<?>> found = constraintViolations.getConstraintViolations();
        if (found != null) {
            for (ConstraintViolation<?> violation : found) {
                violations.add(new Violation(String.valueOf(violation.getPropertyPath()), violation.getMessage()));
            }
        }
        violations.sort(Comparator.comparing(Violation::field).thenComparing(Violation::message));
        return SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, violations);
    }
}
