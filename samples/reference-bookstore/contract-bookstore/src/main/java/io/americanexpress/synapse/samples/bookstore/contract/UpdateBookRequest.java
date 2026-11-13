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

import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.conditional.VersionCondition;

/**
 * Replaces the details of a book, with the constraints of {@link CreateBookRequest}.
 *
 * @param id the id of the book: the path's {@code {id}} over REST, the {@code id} field over gRPC
 * @param isbn the ISBN. Changing it to one another book has is {@code ALREADY_EXISTS}, as on a create
 * @param title the title
 * @param author the author
 * @param publishedYear the year of publication, or {@code null} when it is not known
 * @param version the version this change is based on. It isn't {@code @Nullable}, so an update must name one, and no
 *        update can overwrite a change its caller hasn't seen. That is part of this operation's contract: every
 *        protocol rejects an update without one with {@code PRECONDITION_REQUIRED} before the operation runs (REST
 *        428, gRPC {@code FAILED_PRECONDITION}). It is an entity tag in REST's {@code If-Match} header or the gRPC
 *        {@code etag} field: {@code "3"} is {@code Version(3)}, and {@code *} is {@code AnyVersion}, for whatever
 *        version the operation reads
 */
public record UpdateBookRequest(UUID id, @NotBlank @Size(max = 17) String isbn,
        @NotBlank @Size(max = 255) String title, @NotBlank @Size(max = 255) String author,
        @Positive @Nullable Integer publishedYear, VersionCondition<Long> version) {
}
