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
package io.americanexpress.synapse.data;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;

/**
 * Errors the data modules report besides the framework's own {@code SynapseErrors}, which their translators also map
 * data access failures to ({@code STALE_VERSION}, {@code ALREADY_EXISTS}, {@code DEADLINE_EXCEEDED},
 * {@code UNAVAILABLE}).
 */
public enum DataErrors implements ErrorDetails {

    /**
     * A store's connection was lost while its transaction committed, so whether the transaction committed is unknown:
     * the database may have applied the commit and lost only its reply. The category is {@code INTERNAL} (REST 500,
     * gRPC {@code INTERNAL}), which callers don't retry, because a retry could apply the write twice. A client that
     * knows this code checks whether the write was applied (by the resource's id, for example) before it sends it
     * again.
     */
    COMMIT_OUTCOME_UNKNOWN("The outcome of the request is unknown", ErrorCategory.INTERNAL);

    private final String messageTemplate;

    private final ErrorCategory category;

    DataErrors(String messageTemplate, ErrorCategory category) {
        this.messageTemplate = messageTemplate;
        this.category = category;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String messageTemplate() {
        return messageTemplate;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }
}
