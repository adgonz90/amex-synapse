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

import java.util.UUID;

import io.americanexpress.synapse.core.conditional.VersionCondition;

/**
 * Cancels an order, which deletes it.
 *
 * @param id the id of the order
 * @param version the version the caller expects the order to have. It isn't {@code @Nullable}, so a cancel must name
 *        one: every protocol answers a request without it with {@code PRECONDITION_REQUIRED} before the operation runs
 *        (over REST, a {@code DELETE} without {@code If-Match} is 428). REST fills it from {@code If-Match}:
 *        {@code "0"} is {@code Version(0)}, and {@code *} is {@code AnyVersion}, which any current version satisfies
 */
public record DeleteOrderRequest(UUID id, VersionCondition<Long> version) {
}
