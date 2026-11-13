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

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.paging.PageQuery;

/**
 * Lists the items, one page at a time.
 *
 * @param status only the items in this status, or {@code null} for every item
 * @param page which page to return: an offset, the page size and the sort keys (names of {@link SortKey} constants).
 *        Over gRPC it comes from the AIP-158 fields {@code page_size}, {@code page_token} and {@code order_by}
 */
public record ListItemsRequest(@Nullable ItemStatus status, PageQuery page) {

    /**
     * What a list can be sorted by. Over gRPC the {@code order_by} keys are the constant names in lower case
     * ({@code "quantity desc, sku_code"}); the operation receives the constant names ({@code QUANTITY}).
     */
    public enum SortKey {

        /** The stock-keeping unit. */
        SKU_CODE,

        /** The units in stock. */
        QUANTITY
    }
}
