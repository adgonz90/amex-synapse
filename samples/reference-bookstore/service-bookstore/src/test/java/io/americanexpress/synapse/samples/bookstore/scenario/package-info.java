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
 * The parity suite: {@link io.americanexpress.synapse.samples.bookstore.scenario.BookstoreScenarios} runs every
 * scenario over REST and over gRPC against a running bookstore, through one {@code BookstoreClient} per protocol, and
 * expects the same {@code Outcome} from both. Each client also checks its own protocol's status for every answer.
 * The suite needs only the service's addresses, so the same scenarios run against the service in a test context, a
 * packaged jar or an image.
 */
@NullMarked
package io.americanexpress.synapse.samples.bookstore.scenario;

import org.jspecify.annotations.NullMarked;
