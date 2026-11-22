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
 * Compiles {@code GrpcBindings} into gRPC services: each method gets a handler that parses the request bytes, maps
 * them to the operation's input, calls the operation (through its pipeline) and maps the output to the response. Also
 * logs the exposure inventory when the server starts. Internal; not public API.
 */
@NullMarked
package io.americanexpress.synapse.api.grpc.internal.service;

import org.jspecify.annotations.NullMarked;
