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
package io.americanexpress.synapse.samples.bookstore.scenario;

import java.net.URI;

/**
 * Where a running bookstore answers.
 *
 * @param rest the base URI of the REST API, such as {@code http://localhost:8080}
 * @param grpcHost the host of the gRPC server
 * @param grpcPort the port of the gRPC server
 */
public record Target(URI rest, String grpcHost, int grpcPort) {
}
