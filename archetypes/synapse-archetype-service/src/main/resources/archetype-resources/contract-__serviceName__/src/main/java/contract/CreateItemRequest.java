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
package ${package}.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

/**
 * Adds an item. The pipeline checks the constraints before the operation runs, on every protocol, and each violation
 * names the field. The sizes are the columns' sizes, so a valid request always fits the table.
 *
 * @param name the name, at most 255 characters. One another item has is {@code ALREADY_EXISTS}
 * @param description the description, at most 1000 characters, or {@code null} for none
 */
public record CreateItemRequest(@NotBlank @Size(max = 255) String name,
        @Size(max = 1000) @Nullable String description) {
}
