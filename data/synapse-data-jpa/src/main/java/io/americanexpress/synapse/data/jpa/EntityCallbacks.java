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
package io.americanexpress.synapse.data.jpa;

import java.time.Instant;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import io.americanexpress.synapse.data.Auditable;
import io.americanexpress.synapse.data.EntityIdGenerator;
import io.americanexpress.synapse.data.internal.audit.AuditStamps;

/**
 * The default entity listener of every store's persistence unit (mapping resource
 * {@code io/americanexpress/synapse/data/jpa/synapse-orm.xml}), so it reaches every entity without the entity
 * declaring it. Hibernate creates it through Spring's {@code SpringBeanContainer}, with its dependencies from the
 * application context.
 * <ul>
 * <li>Before an entity is first persisted, it gives an {@link IdEntity} without an id the generator's next id, and
 * stamps an {@link Auditable} entity as modified, and as created unless it already has a creation time (an imported
 * entity keeps it, as with JDBC).</li>
 * <li>Before an entity is updated, it stamps an {@link Auditable} entity as modified.</li>
 * </ul>
 * The time and the user come from {@link AuditStamps}: the clock's time truncated to microseconds, and the
 * application's or Synapse's auditor, else {@code synapse.data.auditing.system-user}.
 */
final class EntityCallbacks {

    private final EntityIdGenerator ids;

    private final AuditStamps stamps;

    EntityCallbacks(EntityIdGenerator ids, AuditStamps stamps) {
        this.ids = ids;
        this.stamps = stamps;
    }

    @PrePersist
    void prePersist(Object entity) {
        if (entity instanceof IdEntity identified && identified.getId() == null) {
            identified.assignId(ids.nextId());
        }
        if (entity instanceof Auditable audited) {
            Instant now = stamps.now();
            String user = stamps.user();
            if (audited.getCreatedAt() == null) {
                audited.setCreated(now, user);
            }
            audited.setModified(now, user);
        }
    }

    @PreUpdate
    void preUpdate(Object entity) {
        if (entity instanceof Auditable audited) {
            audited.setModified(stamps.now(), stamps.user());
        }
    }
}
