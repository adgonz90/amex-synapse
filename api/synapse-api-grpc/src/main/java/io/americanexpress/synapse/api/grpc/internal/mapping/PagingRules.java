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
package io.americanexpress.synapse.api.grpc.internal.mapping;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;

import io.americanexpress.synapse.api.grpc.internal.binding.SortKeys;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;

/**
 * The paging rules a method's request reader applies to its {@code PageQuery}: core's page sizes and offset cap
 * ({@code synapse.paging.*}, the same as REST's) and the method's sort allow-list.
 *
 * @param defaultSize the page size of a request whose {@code page_size} is 0 ({@code synapse.paging.default-size})
 * @param maxSize the largest {@code page_size} a caller may send ({@code synapse.paging.max-size})
 * @param maxOffset the largest {@code skip} a caller may send ({@code synapse.paging.max-offset})
 * @param sortKeys the method's {@code .sortable} keys, or {@code null} when it takes no {@code order_by}
 */
public record PagingRules(int defaultSize, int maxSize, long maxOffset, @Nullable SortKeys sortKeys) {

    /**
     * Returns the rules of a method.
     *
     * @param properties core's {@code synapse.paging} properties
     * @param sortKeys the method's sort keys, or {@code null}
     * @return the rules
     */
    public static PagingRules of(SynapsePagingProperties properties, @Nullable SortKeys sortKeys) {
        return new PagingRules(properties.defaultSize(), properties.maxSize(), properties.maxOffset(), sortKeys);
    }

    /**
     * Returns core's default rules, bound from {@code SynapsePagingProperties}' own defaults (so they never drift from
     * core's), without sort keys. Used by the reader overloads that take no rules.
     *
     * @return the rules
     */
    public static PagingRules defaults() {
        return of(new Binder().bindOrCreate("synapse.paging", SynapsePagingProperties.class), null);
    }
}
