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
package io.americanexpress.synapse.samples.books.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.jspecify.annotations.Nullable;

/**
 * Adds a book to the catalog.
 *
 * @param isbn the ISBN
 * @param title the title
 * @param author the author
 * @param year the year of publication, if known
 * @param channel the channel the request came through. The REST binding fills it from the request context (the
 *        {@code X-Channel} header); a {@code "channel"} in the body that differs from it is rejected with a 400, so a
 *        caller can't set it through the body
 */
public record CreateBookRequest(@NotBlank String isbn, @NotBlank String title, @NotBlank String author,
        @Positive @Nullable Integer year, @Nullable String channel) {
}
