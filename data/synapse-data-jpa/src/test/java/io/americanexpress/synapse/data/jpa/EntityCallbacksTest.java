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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.AuditorAware;

import io.americanexpress.synapse.data.Auditable;
import io.americanexpress.synapse.data.EntityIdGenerator;
import io.americanexpress.synapse.data.internal.audit.AuditStamps;
import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

class EntityCallbacksTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private static final UUID NEXT = UUID.fromString("019b7d40-1c00-7000-8000-000000000001");

    private final MutableClock clock = MutableClock.at(START);

    private final EntityCallbacks callbacks = new EntityCallbacks(() -> NEXT,
            stamps(clock, () -> Optional.of("ana")));

    @Test
    void prePersistAssignsTheNextIdAndStampsCreatedAndModified() {
        Book book = new Book();

        callbacks.prePersist(book);

        assertThat(book.getId()).isEqualTo(NEXT);
        assertThat(book.getCreatedAt()).isEqualTo(START);
        assertThat(book.getCreatedBy()).isEqualTo("ana");
        assertThat(book.getModifiedAt()).isEqualTo(START);
        assertThat(book.getModifiedBy()).isEqualTo("ana");
    }

    @Test
    void prePersistKeepsAnIdThatIsAlreadySet() {
        UUID existing = UUID.fromString("019b7d40-1c00-7000-8000-000000000002");
        Book book = new Book();
        book.assignId(existing);

        callbacks.prePersist(book);

        assertThat(book.getId()).isEqualTo(existing);
    }

    /** The same rule as the JDBC callback: an entity that arrives with a creation time (an import) keeps it. */
    @Test
    void prePersistKeepsACreationTimeThatIsAlreadySetAndStampsModified() {
        Instant imported = Instant.parse("2020-05-06T07:08:09Z");
        Book book = new Book();
        book.setCreated(imported, "importer");
        clock.advance(Duration.ofHours(1));

        callbacks.prePersist(book);

        assertThat(book.getCreatedAt()).isEqualTo(imported);
        assertThat(book.getCreatedBy()).isEqualTo("importer");
        assertThat(book.getModifiedAt()).isEqualTo(START.plus(Duration.ofHours(1)));
        assertThat(book.getModifiedBy()).isEqualTo("ana");
    }

    @Test
    void preUpdateStampsModifiedOnly() {
        Book book = new Book();
        callbacks.prePersist(book);
        clock.advance(Duration.ofHours(1));

        callbacks.preUpdate(book);

        assertThat(book.getCreatedAt()).isEqualTo(START);
        assertThat(book.getModifiedAt()).isEqualTo(START.plus(Duration.ofHours(1)));
    }

    @Test
    void stampsAreTruncatedToMicroseconds() {
        clock.set(Instant.parse("2026-01-01T00:00:00.123456789Z"));
        Book book = new Book();

        callbacks.prePersist(book);
        clock.set(Instant.parse("2026-01-01T00:00:01.987654321Z"));
        callbacks.preUpdate(book);

        assertThat(book.getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
        assertThat(book.getModifiedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:01.987654Z"));
    }

    @Test
    void anEmptyAuditorMeansTheSystemUser() {
        EntityCallbacks withoutAuditor = new EntityCallbacks(() -> NEXT, stamps(clock, Optional::empty));
        Book book = new Book();

        withoutAuditor.prePersist(book);
        withoutAuditor.preUpdate(book);

        assertThat(book.getCreatedBy()).isEqualTo("batch");
        assertThat(book.getModifiedBy()).isEqualTo("batch");
    }

    @Test
    void anIdOnlyEntityGetsAnIdAndIsNeverStamped() {
        CountingClock counting = new CountingClock();
        EntityCallbacks countingCallbacks = new EntityCallbacks(() -> NEXT, stamps(counting, () -> Optional.of("ana")));
        Note note = new Note();

        countingCallbacks.prePersist(note);
        countingCallbacks.preUpdate(note);

        assertThat(note.getId()).isEqualTo(NEXT);
        assertThat(counting.reads).as("the clock is never read").hasValue(0);
    }

    @Test
    void anAuditableThatIsNotAnIdEntityIsStampedWithoutAnId() {
        CountingIds ids = new CountingIds();
        EntityCallbacks countingCallbacks = new EntityCallbacks(ids, stamps(clock, () -> Optional.of("ana")));
        Stamped stamped = new Stamped();

        countingCallbacks.prePersist(stamped);

        assertThat(ids.calls).as("no id is generated").hasValue(0);
        assertThat(stamped.getCreatedAt()).isEqualTo(START);
        assertThat(stamped.getCreatedBy()).isEqualTo("ana");
        assertThat(stamped.getModifiedAt()).isEqualTo(START);
    }

    @Test
    void otherEntitiesAreLeftAlone() {
        CountingIds ids = new CountingIds();
        CountingClock counting = new CountingClock();
        EntityCallbacks countingCallbacks = new EntityCallbacks(ids, stamps(counting, () -> Optional.of("ana")));
        Plain plain = new Plain();

        countingCallbacks.prePersist(plain);
        countingCallbacks.preUpdate(plain);

        assertThat(ids.calls).as("no id is generated").hasValue(0);
        assertThat(counting.reads).as("the clock is never read").hasValue(0);
        assertThat(plain.title).isEqualTo("untouched");
    }

    /** Stamps whose auditor is the given one (no application auditor; how it is chosen is AuditStampsTest's). */
    private static AuditStamps stamps(Clock clock, AuditorAware<String> auditor) {
        return new AuditStamps(clock, new DefaultListableBeanFactory()
                .getBeanProvider(ResolvableType.forClassWithGenerics(AuditorAware.class, String.class)), auditor,
                "batch");
    }

    /** An audited entity. */
    static class Book extends AuditedEntity {
    }

    /** An entity with an id only. */
    static class Note extends IdEntity {
    }

    /** An entity with neither an id base nor auditing. */
    static class Plain {

        String title = "untouched";
    }

    /** An audited entity of its own, without an id base. */
    static class Stamped implements Auditable {

        private @Nullable Instant createdAt;

        private @Nullable String createdBy;

        private @Nullable Instant modifiedAt;

        private @Nullable String modifiedBy;

        @Override
        public @Nullable Instant getCreatedAt() {
            return createdAt;
        }

        @Override
        public @Nullable String getCreatedBy() {
            return createdBy;
        }

        @Override
        public @Nullable Instant getModifiedAt() {
            return modifiedAt;
        }

        @Override
        public @Nullable String getModifiedBy() {
            return modifiedBy;
        }

        @Override
        public void setCreated(Instant at, String by) {
            this.createdAt = at;
            this.createdBy = by;
        }

        @Override
        public void setModified(Instant at, String by) {
            this.modifiedAt = at;
            this.modifiedBy = by;
        }
    }

    /** Counts the ids it generates. */
    static class CountingIds implements EntityIdGenerator {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public UUID nextId() {
            calls.incrementAndGet();
            return NEXT;
        }
    }

    /** Counts how often the time is read. */
    static class CountingClock extends Clock {

        final AtomicInteger reads = new AtomicInteger();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            reads.incrementAndGet();
            return START;
        }

        @Override
        public long millis() {
            reads.incrementAndGet();
            return START.toEpochMilli();
        }
    }
}
