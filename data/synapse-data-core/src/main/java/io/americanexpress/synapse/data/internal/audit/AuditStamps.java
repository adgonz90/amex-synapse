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
package io.americanexpress.synapse.data.internal.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.domain.AuditorAware;
import org.springframework.util.function.SingletonSupplier;

/**
 * The time and the user an audited entity is stamped with, the same for every data access module (JPA's entity
 * listener and JDBC's callback).
 * <ul>
 * <li>The time is the clock's, truncated to microseconds: the precision of a {@code timestamp(6)} column, so the
 * entity holds exactly what the store keeps (H2 and the PostgreSQL driver round nanoseconds, which would make a
 * reloaded entity differ). Truncating, as Hibernate does for its own generated timestamps, never stamps a time
 * ahead of the clock.</li>
 * <li>The user is the application's {@code AuditorAware<String>}, when it defines one (a raw-typed one counts), else
 * Synapse's {@code synapseAuditorAware}; when the auditor has none, it is {@code synapse.data.auditing.system-user}.
 * Synapse's auditor is not a default candidate, so an application auditor of another type (such as an
 * {@code AuditorAware<Long>} for its own Spring Data auditing) neither replaces it nor meets it.</li>
 * </ul>
 * The application's auditor is looked up once, when the context has started, or on first use before that, so it may
 * itself depend on a store. Two application {@code AuditorAware<String>} beans without a primary one fail startup.
 */
public final class AuditStamps implements SmartInitializingSingleton {

    /** The bean name of Synapse's auditor. */
    public static final String SYNAPSE_AUDITOR = "synapseAuditorAware";

    private final Clock clock;

    private final Supplier<AuditorAware<String>> auditor;

    private final String systemUser;

    /**
     * Creates the stamps.
     *
     * @param clock the clock the time comes from
     * @param applicationAuditors the application's {@code AuditorAware<String>}, if it defines one
     * @param synapseAuditor Synapse's auditor, used when the application defines none
     * @param systemUser the user recorded when the auditor has none
     */
    public AuditStamps(Clock clock, ObjectProvider<AuditorAware<String>> applicationAuditors,
            AuditorAware<String> synapseAuditor, String systemUser) {
        this.clock = clock;
        this.auditor = SingletonSupplier.of(() -> applicationAuditors.getIfAvailable(() -> synapseAuditor));
        this.systemUser = systemUser;
    }

    /**
     * Truncates an instant to microseconds, the precision audited timestamps are stored with.
     *
     * @param instant the instant
     * @return the instant without its nanoseconds below a microsecond
     */
    public static Instant truncate(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Returns the time to stamp: the clock's, truncated to microseconds.
     *
     * @return the time
     */
    public Instant now() {
        return truncate(clock.instant());
    }

    /**
     * Returns the user to stamp: the auditor's, else the system user.
     *
     * @return the user
     */
    public String user() {
        return auditor.get().getCurrentAuditor().orElse(systemUser);
    }

    /** Resolves the auditor once the context has started, so two application auditors fail startup. */
    @Override
    public void afterSingletonsInstantiated() {
        AuditorAware<String> resolved = auditor.get();
        Objects.requireNonNull(resolved, "auditor");
    }
}
