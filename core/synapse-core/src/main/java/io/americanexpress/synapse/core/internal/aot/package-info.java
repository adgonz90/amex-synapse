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
/**
 * Ahead-of-time (AOT) support: runtime hints for the core's reflective and service-loaded types, binding hints for the
 * input and output types of operations, and the build-time switch check, which fails an AOT-mode startup when a
 * property that conditions read at build time differs from the value the build used.
 */
@NullMarked
package io.americanexpress.synapse.core.internal.aot;

import org.jspecify.annotations.NullMarked;
