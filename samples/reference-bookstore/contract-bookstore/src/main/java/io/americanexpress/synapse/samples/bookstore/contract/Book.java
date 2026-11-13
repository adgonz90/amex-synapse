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

import org.jspecify.annotations.Nullable;

/**
 * A book of the catalog, as the operations return it: the REST body, and the gRPC {@code Book} message.
 *
 * @param id the id, a UUIDv7 the store assigns when the book is added
 * @param isbn the ISBN, unique in the catalog
 * @param title the title
 * @param author the author
 * @param publishedYear the year of publication, or {@code null} when it is not known
 * @param version 0 when the book is added, plus 1 on every change. It is also the book's entity tag, {@code "0"}
 *        with its double quotes: REST's {@code ETag} header and the gRPC {@code etag} field of a read or a create,
 *        which a client sends back to update or delete this version
 */
public record Book(UUID id, String isbn, String title, String author, @Nullable Integer publishedYear, long version) {
}
