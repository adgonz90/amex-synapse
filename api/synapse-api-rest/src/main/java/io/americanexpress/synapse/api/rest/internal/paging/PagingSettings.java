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
package io.americanexpress.synapse.api.rest.internal.paging;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestProperties;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;

/**
 * The paging settings the REST edge applies: the query parameter names, the offset cap and the page sizes.
 *
 * @param cursor the name of the cursor parameter
 * @param offset the name of the offset parameter
 * @param size the name of the page size parameter
 * @param sort the name of the sort parameter
 * @param maxOffset the largest offset a caller may request
 * @param defaultSize the page size when the caller sends none
 * @param maxSize the largest page size a caller may request
 */
public record PagingSettings(String cursor, String offset, String size, String sort, long maxOffset, int defaultSize,
        int maxSize) {

    /**
     * Returns the settings from the REST plugin's parameter names and core's offset cap and page sizes.
     *
     * @param paging the {@code synapse.api.rest.paging} properties
     * @param core the {@code synapse.paging} properties
     * @return the settings
     */
    public static PagingSettings of(SynapseRestProperties.Paging paging, SynapsePagingProperties core) {
        SynapseRestProperties.Paging.Parameters parameters = paging.parameters();
        return new PagingSettings(parameters.cursor(), parameters.offset(), parameters.size(), parameters.sort(),
                core.maxOffset(), core.defaultSize(), core.maxSize());
    }

    /**
     * Returns the default settings: the standard parameter names, an offset cap of 1000, and pages of 20 items by
     * default and 100 at most.
     *
     * @return the default settings
     */
    public static PagingSettings defaults() {
        return new PagingSettings("cursor", "offset", "size", "sort", 1000, 20, 100);
    }

    /**
     * Returns the paging parameter names: cursor, offset, size and sort, in that order.
     *
     * @return the names
     */
    public Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(cursor, offset, size, sort)));
    }
}
