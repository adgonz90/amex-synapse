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
package io.americanexpress.synapse.samples.inventory.contract;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;

/**
 * The error codes of the inventory. Callers match them (gRPC's {@code ErrorInfo.reason}), so they are part of the
 * contract. The category decides the gRPC status: {@code NOT_FOUND}, and {@code CONFLICT} is {@code ABORTED}, unless
 * the configuration overrides it for an operation (as {@code application.yaml} does for {@code OUT_OF_STOCK} on
 * {@code adjust-stock}).
 */
public enum InventoryErrors implements ErrorDetails {

    /** No item has the id. Argument: the id. */
    ITEM_NOT_FOUND("Item {0} was not found", ErrorCategory.NOT_FOUND),

    /** The stock can't cover what the request removes. Arguments: the id, the quantity in stock. */
    OUT_OF_STOCK("Item {0} has only {1} in stock", ErrorCategory.CONFLICT);

    private final String messageTemplate;

    private final ErrorCategory category;

    InventoryErrors(String messageTemplate, ErrorCategory category) {
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
