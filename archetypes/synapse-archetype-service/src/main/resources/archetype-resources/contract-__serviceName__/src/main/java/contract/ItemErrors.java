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
package ${package}.contract;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;

/**
 * The service's own error codes. Callers match them (REST's problem {@code code}, gRPC's {@code ErrorInfo.reason}), so
 * they are part of the contract. The category decides the status on each protocol.
 *
 * <p>
 * The other failures a caller can match are Synapse's codes, the same on both protocols: {@code VALIDATION_FAILED}
 * for a request that breaks a constraint, and {@code ALREADY_EXISTS} for a name another item has (the table's unique
 * constraint).
 */
public enum ItemErrors implements ErrorDetails {

    /** No item has the id. Argument: the id. */
    ITEM_NOT_FOUND("Item {0} was not found", ErrorCategory.NOT_FOUND);

    private final String messageTemplate;

    private final ErrorCategory category;

    ItemErrors(String messageTemplate, ErrorCategory category) {
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
