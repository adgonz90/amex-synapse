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
package io.americanexpress.synapse.samples.inventory.contract;

import java.util.UUID;

/**
 * Adds stock to an item or removes it. The change is relative and applied atomically, so two adjustments that run at
 * the same time both count; the request needs no version, and has no {@code VersionCondition}.
 *
 * @param id the id
 * @param delta the units to add (positive) or remove (negative)
 */
public record AdjustStockRequest(UUID id, int delta) {
}
