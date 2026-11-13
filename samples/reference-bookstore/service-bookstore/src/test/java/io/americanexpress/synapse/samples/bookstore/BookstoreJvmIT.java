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
package io.americanexpress.synapse.samples.bookstore;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.samples.bookstore.data.books.BookRepository;
import io.americanexpress.synapse.samples.bookstore.scenario.BookstoreScenarios;
import io.americanexpress.synapse.samples.bookstore.scenario.Outcome;
import io.americanexpress.synapse.samples.bookstore.scenario.Protocol;
import io.americanexpress.synapse.samples.bookstore.scenario.Target;
import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willAnswer;

/**
 * The parity scenarios against the whole service in the test context: Tomcat and Netty on free ports, the books store
 * on a PostgreSQL container (Docker), migrated by its Flyway at startup. Error detail is off, as in every environment
 * but local, so the scenarios see the answers a deployed service gives; the slice tests turn it on in
 * {@code application-test.yaml}. No cursor key is set, so every start generates one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.grpc.server.port=0",
        "synapse.errors.include-detail=false"})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class BookstoreJvmIT extends BookstoreScenarios {

    private static final String GENERATED_KEY = "synapse.paging.cursor.key is not set, so paging cursors are "
            + "encrypted with a key generated at startup";

    @StoreConnection("books")
    static final PostgreSQLContainer BOOKS = SynapseContainers.postgres();

    /** The real repository, spied on so that one test can change a book while an operation is running. */
    @MockitoSpyBean
    BookRepository books;

    /** The books store's database, as another request than the service's would change it. */
    @Autowired
    @Qualifier("books")
    JdbcClient jdbc;

    @LocalServerPort
    int restPort;

    @LocalGrpcServerPort
    int grpcPort;

    @Override
    protected Target target() {
        return new Target(URI.create("http://localhost:" + restPort), "localhost", grpcPort);
    }

    /**
     * Both plugins page (GET /books and SearchBooks return a {@code PageResult}), and each reports the generated
     * cursor key, but the WARN is logged once per application. The test context started before the output was
     * captured, so a second bookstore starts here, on the same database.
     */
    @Test
    void generatedKeyWarnsOnceWithBothPlugins(CapturedOutput output) {
        long before = generatedKeyWarnings(output);

        try (ConfigurableApplicationContext bookstore = new SpringApplicationBuilder(BookstoreApplication.class)
                // application.yaml maps spring.profiles.active to ${ENV}, which the platform sets: here, test.
                .properties("ENV=test", "server.port=0", "spring.grpc.server.port=0",
                        "synapse.data.stores.books.url=" + BOOKS.getJdbcUrl(),
                        "synapse.data.stores.books.username=" + BOOKS.getUsername(),
                        "synapse.data.stores.books.password=" + BOOKS.getPassword())
                .run()) {
            assertThat(bookstore.getBeansOfType(RestBindings.class)).hasSize(1);
            assertThat(bookstore.getBeansOfType(GrpcBindings.class)).hasSize(1);
            assertThat(generatedKeyWarnings(output) - before).isOne();
        }
    }

    /**
     * Another request changes the book after the operation read it and before it writes: JPA's {@code @Version} makes
     * the write's flush find no row at the version read, which is {@code STALE_VERSION}. The request carried a version
     * (the book's own etag, or {@code *}), so both protocols answer {@code PRECONDITION_FAILED} (REST 412, gRPC
     * {@code FAILED_PRECONDITION}), and the book keeps the other request's change. Without a version the same race
     * would be {@code STALE_VERSION} (409, {@code ABORTED}), but the bookstore's writes always carry one: a write
     * without one is {@code PRECONDITION_REQUIRED} before the operation runs. The other request is a JDBC update,
     * committed on its own connection from another thread while the operation's transaction is open, so the race
     * happens every time, without waiting.
     */
    @ParameterizedTest
    @EnumSource(Protocol.class)
    void aChangeBetweenTheReadAndTheWriteIsPreconditionFailed(Protocol protocol) {
        String isbn = isbn();
        Outcome created = client(protocol).create(isbn, "The Other Wind", "Ursula K. Le Guin", 2001);
        UUID id = Objects.requireNonNull(created.book()).id();
        willAnswer(invocation -> {
            // The spy delegates to the real repository through its default answer.
            Object found = Mockito
                    .mockingDetails(books)
                    .getMockCreationSettings()
                    .getDefaultAnswer()
                    .answer(invocation);
            CompletableFuture
                    .runAsync(() -> jdbc.sql("UPDATE book SET version = version + 1 WHERE id = ?").param(id).update())
                    .join();
            return found;
        }).given(books).findById(id);

        assertThat(client(protocol).update(id, created.etag(), isbn, "Changed", "Ursula K. Le Guin", null))
                .isEqualTo(Outcome.failed(SynapseErrors.PRECONDITION_FAILED));
        assertThat(client(protocol).delete(id, "*")).isEqualTo(Outcome.failed(SynapseErrors.PRECONDITION_FAILED));

        // Each attempt lost to one other change: the book is at version 2, with its title, and still there.
        assertThat(jdbc.sql("SELECT version FROM book WHERE id = ?").param(id).query(Long.class).single())
                .isEqualTo(2L);
        assertThat(jdbc.sql("SELECT title FROM book WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("The Other Wind");
    }

    private static long generatedKeyWarnings(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains(GENERATED_KEY)).count();
    }
}
