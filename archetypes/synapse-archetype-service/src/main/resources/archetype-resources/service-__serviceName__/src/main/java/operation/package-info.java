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
 * The service's operations, one per use case. Each takes and returns the contract's records, and reads and writes the
 * ${serviceName} store through its repository, mapping the entity to the contract's {@code Item}. They know no
 * protocol: REST and gRPC call the same beans, so both protocols answer every request alike. The ${serviceName} store
 * is the service's only store, so an unqualified {@code @Transactional} uses it.
 */
@NullMarked
package ${package}.operation;

import org.jspecify.annotations.NullMarked;
