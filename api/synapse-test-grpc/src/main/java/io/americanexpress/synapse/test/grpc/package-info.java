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
 * The gRPC test slice: {@link io.americanexpress.synapse.test.grpc.SynapseGrpcTest} starts the gRPC plugin with the
 * application's bindings and hand-written services, its operations replaced by test doubles, on Spring Boot's
 * in-process test transport.
 */
@NullMarked
package io.americanexpress.synapse.test.grpc;

import org.jspecify.annotations.NullMarked;
