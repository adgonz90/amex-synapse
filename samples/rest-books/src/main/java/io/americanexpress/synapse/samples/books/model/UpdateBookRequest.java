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

import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.conditional.VersionCondition;

/**
 * Replaces the details of a book.
 *
 * @param id the id of the book
 * @param isbn the ISBN
 * @param title the title
 * @param author the author
 * @param year the year of publication, if known
 * @param version the version this change is conditional on, or {@code null} for none. The REST binding fills it
 *        from {@code If-Match}: {@code "1"} is {@code Version(1)}, {@code *} is {@code AnyVersion}, and no header is
 *        {@code null}. With {@code AnyVersion} or {@code null} the update applies to the version it reads, and a
 *        concurrent change between that read and the write still fails
 */
public record UpdateBookRequest(UUID id, @NotBlank String isbn, @NotBlank String title, @NotBlank String author,
        @Positive @Nullable Integer year, @Nullable VersionCondition<Long> version) {
}
