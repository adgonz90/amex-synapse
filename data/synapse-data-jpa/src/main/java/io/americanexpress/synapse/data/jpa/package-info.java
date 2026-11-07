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
 * Base classes for JPA entities: {@link io.americanexpress.synapse.data.jpa.IdEntity},
 * {@link io.americanexpress.synapse.data.jpa.VersionedEntity} and
 * {@link io.americanexpress.synapse.data.jpa.AuditedEntity}, with UUIDv7 ids, and
 * {@link io.americanexpress.synapse.data.jpa.LongIdEntity}, {@link io.americanexpress.synapse.data.jpa.LongVersionedEntity}
 * and {@link io.americanexpress.synapse.data.jpa.LongAuditedEntity}, with ids from a database sequence. Every store's
 * persistence unit has a default entity listener that assigns the UUID ids and stamps audited entities.
 */
@NullMarked
package io.americanexpress.synapse.data.jpa;

import org.jspecify.annotations.NullMarked;
