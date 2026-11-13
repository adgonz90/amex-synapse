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
package io.americanexpress.synapse.samples.multistore.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An order, as callers see it. The orders store keeps it as a {@code StoredOrder} entity, which never leaves the
 * service: an operation's input or output is never a persistence entity.
 *
 * @param id the id, a UUIDv7 that the store assigns when the order is placed
 * @param customer who placed the order
 * @param amount the order's amount
 * @param createdAt when the order was placed, from the service's clock
 * @param createdBy who placed it: the signed-in principal, else {@code synapse.data.auditing.system-user}
 *        ({@code system} by default). Nothing signs callers in yet, so every order records {@code system}
 * @param version the version: 0 when the order is placed. It is also the order's entity tag ({@code "0"}): REST's
 *        {@code ETag} header, which a client sends back in {@code If-Match} to cancel this version
 */
public record Order(UUID id, String customer, BigDecimal amount, Instant createdAt, String createdBy, long version) {
}
