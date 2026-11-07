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
package io.americanexpress.synapse.data.jdbc.internal;

import java.time.Instant;

import org.springframework.core.Ordered;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;

import io.americanexpress.synapse.data.Auditable;
import io.americanexpress.synapse.data.internal.audit.AuditStamps;

/**
 * Stamps {@link Auditable} aggregates before Spring Data JDBC saves them, with the time and user of
 * {@link AuditStamps} (the same as JPA entities get): the creation when the aggregate has no {@code createdAt} yet, and
 * the modification on every save. Other aggregates are left as they are. Spring Data has decided between insert and
 * update before it calls the callback, so the stamps never change that decision.
 * <p>
 * It runs at {@value #ORDER}, the order of Spring Data's own auditing callback, so before the application's unordered
 * callbacks, which then see the stamps.
 */
public final class JdbcAuditingCallback implements BeforeConvertCallback<Object>, Ordered {

    /** The callback's order. */
    public static final int ORDER = 100;

    private final AuditStamps stamps;

    /**
     * Creates the callback.
     *
     * @param stamps the time and user to stamp
     */
    public JdbcAuditingCallback(AuditStamps stamps) {
        this.stamps = stamps;
    }

    @Override
    public Object onBeforeConvert(Object aggregate) {
        if (aggregate instanceof Auditable auditable) {
            Instant now = stamps.now();
            String user = stamps.user();
            if (auditable.getCreatedAt() == null) {
                auditable.setCreated(now, user);
            }
            auditable.setModified(now, user);
        }
        return aggregate;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
