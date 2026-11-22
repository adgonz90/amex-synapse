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
package io.americanexpress.synapse.core.paging;

import java.util.List;
import java.util.stream.Collectors;

import io.americanexpress.synapse.core.Incubating;

/**
 * What a cursor token is bound to: a token issued for one scope is rejected for any other.
 *
 * @param operation the name of the operation the request calls, whatever the protocol
 * @param sort the sort of the request, in order; each property is the enum constant's name for keys declared with
 *        {@code .sortable(Class)}, on every protocol, and the declared name otherwise (REST's
 *        {@code .sortable(String...)})
 * @param inputDigest a digest of the request's other inputs (everything but the paging parameters and the version
 *        conditions) and of the plugin's own entries, such as a versioned REST route's version
 */
@Incubating
public record CursorScope(String operation, List<SortOrder> sort, String inputDigest) {

    /**
     * Copies the sort.
     */
    public CursorScope {
        sort = List.copyOf(sort);
    }

    /**
     * Returns the sort as text: each property and direction joined by {@code :}, in order and separated by commas.
     * The text is unambiguous only when no sort property contains {@code :} or {@code ,}. Routes compiled by Synapse
     * guarantee this through the rules for sortable keys; any other caller of a {@link CursorCodec} must guarantee it
     * too.
     *
     * @return for example {@code TITLE:ASC,PUBLISHED_YEAR:DESC}; empty when there is no sort
     */
    public String normalizedSort() {
        return sort
                .stream()
                .map(order -> order.property() + ":" + order.direction().name())
                .collect(Collectors.joining(","));
    }
}
