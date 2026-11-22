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

/**
 * Names of the observations Synapse records and of their key values. These names are a stable contract: dashboards,
 * alerts and trace queries depend on them.
 *
 * <p>
 * Every operation call records an observation named {@value #OPERATION} with the low-cardinality keys
 * {@value #OPERATION_KEY}, {@value #KIND_KEY}, {@value #OUTCOME_KEY}, {@value #ERROR_CATEGORY_KEY},
 * {@value #ERROR_CODE_KEY} and {@value #ERROR_TYPE_KEY}, and the high-cardinality key {@value #CORRELATION_ID_KEY}.
 * Business operation calls record {@value #BUSINESS} with the keys {@value #BUSINESS_KEY}, {@value #OUTCOME_KEY} and
 * {@value #ERROR_TYPE_KEY}.
 */
public final class SynapseObservations {

    /** Name of the observation recorded around every operation call. */
    public static final String OPERATION = "synapse.operation";

    /** Name of the observation (or timer) recorded around every business operation call. */
    public static final String BUSINESS = "synapse.business";

    /** Low-cardinality key: the operation name, such as {@code get-book}. */
    public static final String OPERATION_KEY = "operation";

    /** Low-cardinality key: the operation kind, {@code query} or {@code command}. */
    public static final String KIND_KEY = "kind";

    /** Low-cardinality key: {@value #OUTCOME_SUCCESS} or {@value #OUTCOME_ERROR}. */
    public static final String OUTCOME_KEY = "outcome";

    /** Low-cardinality key: the error category, or {@value #NONE} when the call succeeded. */
    public static final String ERROR_CATEGORY_KEY = "error.category";

    /** Low-cardinality key: the error code, or {@value #NONE} when the call succeeded. */
    public static final String ERROR_CODE_KEY = "error.code";

    /**
     * Low-cardinality key, OpenTelemetry's standard name for the error class: on operations the same value as
     * {@value #ERROR_CODE_KEY}; on business calls the error code of a {@code SynapseException}, {@value #JVM_ERROR} for
     * a JVM {@link Error} and {@value #OTHER_ERROR} for any other exception. {@value #NONE} when the call succeeded.
     */
    public static final String ERROR_TYPE_KEY = "error.type";

    /** Low-cardinality key: the business operation name, such as {@code price-calculator}. */
    public static final String BUSINESS_KEY = "business";

    /** High-cardinality key: the correlation id of the request. */
    public static final String CORRELATION_ID_KEY = "correlation.id";

    /** Outcome of a call that returned normally. */
    public static final String OUTCOME_SUCCESS = "success";

    /** Outcome of a call that threw. */
    public static final String OUTCOME_ERROR = "error";

    /** Value of the error keys when the call succeeded. */
    public static final String NONE = "none";

    /** Value of {@value #ERROR_CODE_KEY} and {@value #ERROR_TYPE_KEY} when the call failed with a JVM {@link Error}. */
    public static final String JVM_ERROR = "ERROR";

    /**
     * Value of {@value #ERROR_TYPE_KEY} when a business call failed with an exception that is neither a
     * {@code SynapseException} nor an {@link Error}: business exceptions are not translated. It is OpenTelemetry's
     * fallback value.
     */
    public static final String OTHER_ERROR = "_OTHER";

    private SynapseObservations() {
    }
}
