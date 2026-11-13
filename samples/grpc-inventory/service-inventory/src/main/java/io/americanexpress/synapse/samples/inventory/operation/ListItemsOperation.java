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
package io.americanexpress.synapse.samples.inventory.operation;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.samples.inventory.contract.Item;
import io.americanexpress.synapse.samples.inventory.contract.ListItemsRequest;
import io.americanexpress.synapse.samples.inventory.contract.ListItemsRequest.SortKey;
import io.americanexpress.synapse.samples.inventory.data.InMemoryItems;

/**
 * Lists the items one page at a time, optionally only those in one status. The items are in the order of the caller's
 * sort keys, then by SKU (the default order), then by id, so every item has one place.
 *
 * <p>
 * This operation pages by offset only: it returns {@link PageResult#of} without a cursor, and the gRPC edge gives the
 * caller a {@code next_page_token} that carries the next offset, bound to this operation, the status and the sort.
 * The next page then arrives here as an offset. A database would page by key set and return a cursor.
 */
@Component
public class ListItemsOperation implements QueryOperation<ListItemsRequest, PageResult<Item>> {

    private final InMemoryItems items;

    /**
     * Creates the operation.
     *
     * @param items the inventory
     */
    public ListItemsOperation(InMemoryItems items) {
        this.items = items;
    }

    @Override
    public PageResult<Item> execute(ListItemsRequest request) {
        PageQuery page = request.page();
        List<Item> matching = items
                .findAll()
                .stream()
                .filter(item -> request.status() == null || item.status() == request.status())
                .sorted(order(page.sort()))
                .toList();
        Long offset = page.offset();
        int from = (int) Math.min(offset == null ? 0 : offset, matching.size());
        int to = Math.min(from + page.size(), matching.size());
        // The total is cheap here (the whole list is in memory); a database would count only if callers need it.
        return PageResult.of(matching.subList(from, to), to < matching.size()).withTotal(matching.size());
    }

    private static Comparator<Item> order(List<SortOrder> sort) {
        // Built from the last criterion to the first: the caller's keys in their order, then SKU and id break ties.
        Comparator<Item> order = Comparator.comparing(Item::sku).thenComparing(Item::id);
        for (SortOrder key : sort.reversed()) {
            order = comparator(key).thenComparing(order);
        }
        return order;
    }

    private static Comparator<Item> comparator(SortOrder key) {
        // The edge only lets through the keys that .sortable(SortKey.class) allows, as their constant names.
        Comparator<Item> ascending = switch (SortKey.valueOf(key.property())) {
            case SKU_CODE -> Comparator.comparing(Item::sku);
            case QUANTITY -> Comparator.comparingInt(Item::quantity);
        };
        return key.direction() == SortOrder.Direction.DESC ? ascending.reversed() : ascending;
    }
}
