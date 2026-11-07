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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.relational.auditing.RelationalAuditingCallback;

import io.americanexpress.synapse.data.internal.audit.AuditStamps;
import io.americanexpress.synapse.data.jdbc.fixture.books.Book;
import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAuditingCallbackTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = MutableClock.at(START);

    private final JdbcAuditingCallback callback = new JdbcAuditingCallback(new AuditStamps(clock,
            new DefaultListableBeanFactory()
                    .getBeanProvider(ResolvableType.forClassWithGenerics(AuditorAware.class, String.class)),
            () -> Optional.of("ana"), "batch"));

    @Test
    void stampsCreatedAndModifiedOnTheFirstSave() {
        Book book = new Book("Dune");

        assertThat(callback.onBeforeConvert(book)).isSameAs(book);

        assertThat(book.getCreatedAt()).isEqualTo(START);
        assertThat(book.getCreatedBy()).isEqualTo("ana");
        assertThat(book.getModifiedAt()).isEqualTo(START);
        assertThat(book.getModifiedBy()).isEqualTo("ana");
    }

    @Test
    void laterSavesMoveModifiedOnly() {
        Book book = new Book("Dune");
        callback.onBeforeConvert(book);
        clock.advance(Duration.ofMinutes(5));

        callback.onBeforeConvert(book);

        assertThat(book.getCreatedAt()).isEqualTo(START);
        assertThat(book.getModifiedAt()).isEqualTo(START.plus(Duration.ofMinutes(5)));
    }

    /** The same rule as JPA's entity listener: an aggregate that arrives with a creation time (an import) keeps it. */
    @Test
    void keepsACreationTimeTheAggregateAlreadyHas() {
        Book book = new Book("Dune");
        Instant imported = Instant.parse("2020-05-01T10:00:00Z");
        book.setCreated(imported, "importer");

        callback.onBeforeConvert(book);

        assertThat(book.getCreatedAt()).isEqualTo(imported);
        assertThat(book.getCreatedBy()).isEqualTo("importer");
        assertThat(book.getModifiedAt()).isEqualTo(START);
    }

    @Test
    void truncatesTheClockToMicroseconds() {
        clock.set(Instant.parse("2026-01-01T00:00:00.123456789Z"));
        Book book = new Book("Dune");

        callback.onBeforeConvert(book);

        assertThat(book.getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
    }

    @Test
    void leavesOtherAggregatesAlone() {
        Object other = new Object();

        assertThat(callback.onBeforeConvert(other)).isSameAs(other);
    }

    @Test
    void runsWithSpringDatasAuditingOrder() {
        assertThat(callback.getOrder()).isEqualTo(RelationalAuditingCallback.AUDITING_ORDER);
    }
}
