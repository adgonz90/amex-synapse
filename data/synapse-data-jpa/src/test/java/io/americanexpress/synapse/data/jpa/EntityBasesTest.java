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
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class EntityBasesTest {

    private static final UUID ID = UUID.fromString("019b7d40-1c00-7000-8000-000000000001");

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void idOnlyBaseIsNewUntilItHasAnId() {
        Note note = new Note();
        assertThat(note.isNew()).isTrue();
        assertThat(note.getId()).isNull();

        note.assignId(ID);

        assertThat(note.isNew()).isFalse();
        assertThat(note.getId()).isEqualTo(ID);
    }

    @Test
    void versionedBaseIsNewUntilItHasAVersion() {
        Book book = new Book();
        book.assignId(ID);
        assertThat(book.isNew()).as("id set, version null").isTrue();
        assertThat(book.getVersion()).isNull();

        ReflectionTestUtils.setField(book, VersionedEntity.class, "version", 0L, Long.class);

        assertThat(book.isNew()).isFalse();
        assertThat(book.getVersion()).isZero();
    }

    @Test
    void equalityIsByIdAndUnproxiedClass() {
        Book book = withId(new Book(), ID);

        assertThat(book).isEqualTo(withId(new Book(), ID)).hasSameHashCodeAs(withId(new Book(), ID));
        assertThat(book).isNotEqualTo(withId(new Book(), UUID.randomUUID()));
        assertThat(book).as("another class, same id").isNotEqualTo(withId(new Magazine(), ID));
        assertThat(book).isNotEqualTo(withId(new Note(), ID)).isNotEqualTo("a string").isNotEqualTo(null);
        Book withoutId = new Book();
        assertThat(withoutId).as("no id: only itself").isEqualTo(withoutId).isNotEqualTo(new Book());
        assertThat(withoutId).isNotEqualTo(book);
    }

    @Test
    void hashCodeIsStableAcrossPersist() {
        Book book = new Book();
        int before = book.hashCode();

        book.assignId(ID);

        assertThat(book.hashCode()).isEqualTo(before).isEqualTo(Book.class.hashCode());
    }

    @Test
    void auditedBaseRecordsCreatedAndModified() {
        Book book = new Book();
        assertThat(book.getCreatedAt()).isNull();
        assertThat(book.getCreatedBy()).isNull();
        assertThat(book.getModifiedAt()).isNull();
        assertThat(book.getModifiedBy()).isNull();

        book.setCreated(AT, "ana");
        book.setModified(AT.plusSeconds(60), "bo");

        assertThat(book.getCreatedAt()).isEqualTo(AT);
        assertThat(book.getCreatedBy()).isEqualTo("ana");
        assertThat(book.getModifiedAt()).isEqualTo(AT.plusSeconds(60));
        assertThat(book.getModifiedBy()).isEqualTo("bo");
    }

    @Test
    void longIdBaseIsNewUntilItHasAnId() {
        Counter counter = new Counter();
        assertThat(counter.isNew()).isTrue();
        assertThat(counter.getId()).isNull();

        ReflectionTestUtils.setField(counter, LongIdEntity.class, "id", 1L, Long.class);

        assertThat(counter.isNew()).isFalse();
        assertThat(counter.getId()).isEqualTo(1L);
    }

    @Test
    void longVersionedBaseIsNewUntilItHasAVersion() {
        Entry entry = withLongId(new Entry(), 1L);
        assertThat(entry.isNew()).as("id set, version null").isTrue();
        assertThat(entry.getVersion()).isNull();

        ReflectionTestUtils.setField(entry, LongVersionedEntity.class, "version", 0L, Long.class);

        assertThat(entry.isNew()).isFalse();
        assertThat(entry.getVersion()).isZero();
    }

    @Test
    void longEqualityIsByIdAndUnproxiedClass() {
        Entry entry = withLongId(new Entry(), 1L);

        assertThat(entry).isEqualTo(withLongId(new Entry(), 1L)).hasSameHashCodeAs(withLongId(new Entry(), 1L));
        assertThat(entry).isNotEqualTo(withLongId(new Entry(), 2L));
        assertThat(entry).as("another class, same id").isNotEqualTo(withLongId(new Counter(), 1L));
        assertThat(entry).isNotEqualTo(withId(new Note(), ID)).isNotEqualTo(null);
        Entry withoutId = new Entry();
        assertThat(withoutId).as("no id: only itself").isEqualTo(withoutId).isNotEqualTo(new Entry());
        assertThat(withoutId).isNotEqualTo(entry);
        int before = withoutId.hashCode();
        ReflectionTestUtils.setField(withoutId, LongIdEntity.class, "id", 3L, Long.class);
        assertThat(withoutId.hashCode()).isEqualTo(before).isEqualTo(Entry.class.hashCode());
    }

    @Test
    void longAuditedBaseRecordsCreatedAndModified() {
        Entry entry = new Entry();
        assertThat(entry.getCreatedAt()).isNull();
        assertThat(entry.getCreatedBy()).isNull();
        assertThat(entry.getModifiedAt()).isNull();
        assertThat(entry.getModifiedBy()).isNull();

        entry.setCreated(AT, "ana");
        entry.setModified(AT.plusSeconds(60), "bo");

        assertThat(entry.getCreatedAt()).isEqualTo(AT);
        assertThat(entry.getCreatedBy()).isEqualTo("ana");
        assertThat(entry.getModifiedAt()).isEqualTo(AT.plusSeconds(60));
        assertThat(entry.getModifiedBy()).isEqualTo("bo");
    }

    private static <T extends IdEntity> T withId(T entity, UUID id) {
        entity.assignId(id);
        return entity;
    }

    private static <T extends LongIdEntity> T withLongId(T entity, long id) {
        ReflectionTestUtils.setField(entity, LongIdEntity.class, "id", id, Long.class);
        return entity;
    }

    /** An entity with a UUID id only. */
    static class Note extends IdEntity {
    }

    /** An audited entity with a UUID id. */
    static class Book extends AuditedEntity {
    }

    /** Another audited entity with a UUID id. */
    static class Magazine extends AuditedEntity {
    }

    /** An entity with a sequence id only. */
    static class Counter extends LongIdEntity {
    }

    /** An audited entity with a sequence id. */
    static class Entry extends LongAuditedEntity {
    }
}
