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
 * Test slice for Synapse data stores: {@link io.americanexpress.synapse.test.data.SynapseDataTest} starts one store with
 * its migrations, entities and repositories in a transaction rolled back after each test, and
 * {@link io.americanexpress.synapse.test.data.StoreConnection} connects a store to a Testcontainers database, such as
 * one of {@link io.americanexpress.synapse.test.data.SynapseContainers}.
 */
@NullMarked
package io.americanexpress.synapse.test.data;

import org.jspecify.annotations.NullMarked;
