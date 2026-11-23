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
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.jspecify.annotations.Nullable;

/**
 * Stands in for constraint violations in a logged cause chain. It names each violation by its path and its message
 * <em>template</em>, never by its interpolated message: Hibernate Validator interpolates {@code ${validatedValue}}
 * by default, so an interpolated message can carry the rejected value, a {@code @Masked} one included. A template
 * that a custom validator built with the value, or a map or iterable key in a path, is written as it is (see core.adoc,
 * <em>Masking</em>). Its message has the form {@code path: template, path: template}, in path order, and
 * {@link #violations()} returns that list.
 */
final class SanitizedViolations extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * How deep a cause chain is searched for a {@link ConstraintViolationException}: this many exceptions, the failure
     * itself included, so the failure and its first nine causes.
     */
    static final int MAX_DEPTH = 10;

    private final String violations;

    /** The token of the call whose pipeline raised this rejection; set once, and never serialized. */
    private transient @Nullable Object call;

    private SanitizedViolations(String message, String violations, boolean stackTrace) {
        super(message, null, false, stackTrace);
        this.violations = violations;
    }

    /**
     * The pipeline's own rejection of an input: each Jakarta violation by its template, and each of the pipeline's
     * own violations (paging, required conditions) by its text, which never repeats a value. It has no stack trace:
     * the {@code SynapseException} it causes has one.
     *
     * @param constraints the Jakarta violations
     * @param fixed the pipeline's own violations, as {@code path: text}
     * @return the stand-in
     */
    static SanitizedViolations ofRejection(Collection<? extends ConstraintViolation<?>> constraints,
            List<String> fixed) {
        List<String> items = new ArrayList<>(templates(constraints));
        items.addAll(fixed);
        String description = String.join(", ", items);
        return new SanitizedViolations(description, description, false);
    }

    /**
     * Replaces a failure whose cause chain holds constraint violations. Its message names the classes of the chain,
     * outermost first, then the violations; the wrappers' own messages are dropped, since a commit or a proxy may
     * repeat the interpolated messages in them. It keeps the stack trace of the violations' exception.
     *
     * @param thrown the failure
     * @param found the constraint violations in its cause chain, or the failure itself
     * @return the stand-in
     */
    @SuppressWarnings("ReferenceEquality") // The walk stops at the found instance itself, not at an equal one.
    static SanitizedViolations replacing(Throwable thrown, ConstraintViolationException found) {
        List<String> classes = new ArrayList<>();
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            classes.add(current.getClass().getSimpleName());
            if (current == found) {
                break;
            }
        }
        Set<ConstraintViolation<?>> constraints = found.getConstraintViolations();
        String description = String.join(", ", templates(constraints == null ? List.of() : constraints));
        SanitizedViolations replacement = new SanitizedViolations(String.join(" > ", classes) + ": " + description,
                description, true);
        replacement.setStackTrace(found.getStackTrace());
        return replacement;
    }

    /**
     * Returns the first {@link ConstraintViolationException} in a cause chain, the failure itself included, searched
     * to {@value #MAX_DEPTH} deep.
     *
     * @param thrown the failure
     * @return the exception, or {@code null}
     */
    static @Nullable ConstraintViolationException find(Throwable thrown) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < MAX_DEPTH && seen.add(current); depth++) {
            if (current instanceof ConstraintViolationException violations) {
                return violations;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Returns the first stand-in in a cause chain, searched to {@value #MAX_DEPTH} deep.
     *
     * @param failure the failure
     * @return the stand-in, or {@code null}
     */
    static @Nullable SanitizedViolations in(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DEPTH && seen.add(current); depth++) {
            if (current instanceof SanitizedViolations sanitized) {
                return sanitized;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Returns the violations as {@code path: template}, joined with commas.
     *
     * @return the violations
     */
    String violations() {
        return violations;
    }

    /**
     * Returns the token of the call whose pipeline raised this rejection (see {@link #markCall(Object)}).
     *
     * @return the token, or {@code null} for a stand-in that isn't a pipeline rejection
     */
    @Nullable
    Object call() {
        return call;
    }

    /**
     * Records the call whose pipeline raised this rejection: an opaque token, compared by identity.
     *
     * @param call the call's token
     * @throws IllegalStateException if a call is already recorded
     */
    void markCall(Object call) {
        if (this.call != null) {
            throw new IllegalStateException("The rejection's call is already recorded");
        }
        this.call = call;
    }

    private static List<String> templates(Collection<? extends ConstraintViolation<?>> constraints) {
        List<String> items = new ArrayList<>();
        for (ConstraintViolation<?> violation : constraints) {
            items.add(violation.getPropertyPath() + ": " + violation.getMessageTemplate());
        }
        Collections.sort(items);
        return items;
    }
}
