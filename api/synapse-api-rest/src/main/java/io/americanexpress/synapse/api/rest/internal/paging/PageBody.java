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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * The response body of a route whose operation returns a {@code PageResult}, serialized with the application's mapper,
 * so the member names follow its naming strategy. It carries the cursor token, never the operation's raw cursor.
 *
 * @param items the page's items, always written, even when the application's inclusion would leave out an empty list
 * @param nextCursor the token of the next page, or {@code null} (and left out) when there is none
 * @param hasNext whether there is a next page, always written, even when {@code false}
 * @param total the total number of items, or {@code null} (and left out) when the operation did not count them
 */
public record PageBody(@JsonInclude(JsonInclude.Include.ALWAYS) List<?> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String nextCursor,
        @JsonInclude(JsonInclude.Include.ALWAYS) boolean hasNext,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Long total) {
}
