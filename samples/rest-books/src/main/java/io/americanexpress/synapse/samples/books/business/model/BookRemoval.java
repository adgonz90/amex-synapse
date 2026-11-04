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
package io.americanexpress.synapse.samples.books.business.model;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A book to remove from the catalog.
 *
 * @param id the id of the book
 * @param version the version the caller expects the book to have, or {@code null} to check against the version the
 *        business operation reads
 */
public record BookRemoval(UUID id, @Nullable Long version) {
}
