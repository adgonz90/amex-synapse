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
package io.americanexpress.synapse.samples.bookstore.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

/**
 * Adds a book to the catalog. The pipeline checks the constraints before the operation runs, on every protocol, and
 * each violation names the field: {@code publishedYear} over REST, {@code published_year} over gRPC. The sizes are
 * the columns' sizes, so a valid request always fits the table.
 *
 * @param isbn the ISBN, at most 17 characters (an ISBN-13 with its hyphens). One another book has is
 *        {@code ALREADY_EXISTS}
 * @param title the title
 * @param author the author
 * @param publishedYear the year of publication, or {@code null} when it is not known
 */
public record CreateBookRequest(@NotBlank @Size(max = 17) String isbn, @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 255) String author, @Positive @Nullable Integer publishedYear) {
}
