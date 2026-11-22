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
 * REST edge plumbing shared by the filters, the error handling and the routes: request attribute names, the filter
 * that hides or strips inbound propagation headers before tracing reads them, the filter that opens the Synapse
 * request context (correlation id, attributes, locale and deadline) inside the server observation, and the filters that
 * limit concurrent requests and request body size.
 */
@NullMarked
package io.americanexpress.synapse.api.rest.internal.edge;

import org.jspecify.annotations.NullMarked;
