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
package io.americanexpress.synapse.api.grpc.internal.binding;

import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;

/**
 * A {@code .map(fieldPath, component)} declaration: the component reads from, or writes to, the field at the path
 * instead of the field named after it.
 *
 * @param fieldPath the proto field path, relative to the message of the component's record and dotted for nested
 *        messages ({@code author.name})
 * @param component the component of an input or output record, or of a record nested in one
 */
public record FieldOverride(String fieldPath, ResolvedComponent component) {
}
