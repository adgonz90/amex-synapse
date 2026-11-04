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
package io.americanexpress.synapse.core.error;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The exception an operation throws to report a failure. It carries an {@link ErrorDetails}, the template arguments
 * and, for validation failures, the violations. The arguments appear only in {@link #getMessage()}; callers never see
 * the message unless error detail is enabled.
 */
public class SynapseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorDetails details;

    private final transient List<@Nullable Object> args;

    private final transient List<Violation> violations;

    /**
     * Creates the exception.
     *
     * @param details the error
     * @param args the arguments of the message template
     */
    public SynapseException(ErrorDetails details, @Nullable Object... args) {
        super(format(details, args));
        this.details = details;
        this.args = copyArgs(args);
        this.violations = List.of();
    }

    /**
     * Creates the exception with a cause.
     *
     * @param details the error
     * @param cause the cause
     * @param args the arguments of the message template
     */
    public SynapseException(ErrorDetails details, Throwable cause, @Nullable Object... args) {
        super(format(details, args), cause);
        this.details = details;
        this.args = copyArgs(args);
        this.violations = List.of();
    }

    private SynapseException(ErrorDetails details, List<Violation> violations) {
        super(format(details, new Object[0]));
        this.details = details;
        this.args = List.of();
        this.violations = List.copyOf(violations);
    }

    /**
     * Creates an exception that carries validation violations.
     *
     * @param details the error, usually {@link SynapseErrors#VALIDATION_FAILED}
     * @param violations the violations, kept in order
     * @return the exception
     */
    public static SynapseException ofViolations(ErrorDetails details, List<Violation> violations) {
        return new SynapseException(details, violations);
    }

    /**
     * Returns the error.
     *
     * @return the error
     */
    public ErrorDetails details() {
        return details;
    }

    /**
     * Returns the error code.
     *
     * @return the code
     */
    public String code() {
        return details.code();
    }

    /**
     * Returns the error category.
     *
     * @return the category
     */
    public ErrorCategory category() {
        return details.category();
    }

    /**
     * Returns a copy of the template arguments.
     *
     * @return the arguments
     */
    public List<@Nullable Object> args() {
        return new ArrayList<>(args);
    }

    /**
     * Returns the violations.
     *
     * @return an immutable list, empty when there are none
     */
    public List<Violation> violations() {
        return violations;
    }

    private static List<@Nullable Object> copyArgs(@Nullable Object[] args) {
        List<@Nullable Object> copy = new ArrayList<>(args.length);
        copy.addAll(Arrays.asList(args));
        return Collections.unmodifiableList(copy);
    }

    private static String format(ErrorDetails details, @Nullable Object[] args) {
        String template = details.messageTemplate();
        String[] text = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            text[i] = String.valueOf(args[i]);
        }
        try {
            return new MessageFormat(template, Locale.ROOT).format(text);
        } catch (IllegalArgumentException e) {
            return template;
        }
    }
}
