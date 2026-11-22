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

import io.americanexpress.synapse.core.conditional.VersionCondition;

/**
 * Removes a book from the catalog.
 *
 * @param id the id of the book: the path's {@code {id}} over REST, the {@code id} field over gRPC
 * @param version the version the caller expects the book to have. As for {@link UpdateBookRequest#version()}, it
 *        isn't {@code @Nullable}, so every protocol requires it: REST's {@code If-Match} header or the gRPC
 *        {@code etag} field, {@code "3"} for {@code Version(3)} or {@code *} for {@code AnyVersion}
 */
public record DeleteBookRequest(UUID id, VersionCondition<Long> version) {
}
