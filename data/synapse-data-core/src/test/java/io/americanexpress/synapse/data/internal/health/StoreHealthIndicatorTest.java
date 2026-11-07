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
package io.americanexpress.synapse.data.internal.health;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class StoreHealthIndicatorTest {

    private static final String SECRET = "s3cr3t";

    private static final String UNREACHABLE = "jdbc:h2:tcp://127.0.0.1:1/none;PASSWORD=" + SECRET;

    @Test
    void upWhenAConnectionValidates() {
        try (HikariDataSource dataSource = pool("jdbc:h2:mem:health-up;DB_CLOSE_DELAY=-1")) {
            Health health = new StoreHealthIndicator("books", dataSource, Duration.ofSeconds(1)).health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).isEmpty();
            assertThat(pool(dataSource).getActiveConnections()).isZero();
        }
    }

    /**
     * While the database is down, a backlog of checks starts one borrow at a time: the checks that find it still in
     * flight report the timeout without starting another.
     */
    @Test
    void aBacklogOfChecksStartsOneBorrow() throws InterruptedException, ExecutionException {
        SlowFailingDataSource slow = new SlowFailingDataSource(Duration.ofMillis(600));
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setPoolName("books");
            dataSource.setDataSource(slow);
            StoreHealthIndicator indicator = new StoreHealthIndicator("books", dataSource, Duration.ofMillis(50));
            ExecutorService probes = Executors.newFixedThreadPool(4);
            try {
                List<Callable<Health>> checks = Collections.nCopies(8, indicator::health);
                for (Future<Health> check : probes.invokeAll(checks)) {
                    assertThat(check.get().getDetails())
                            .containsExactly(Map.entry("error", "No connection within 50 ms."));
                }
            } finally {
                probes.shutdownNow();
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> slow.finished.get() == 1);
            await()
                    .during(Duration.ofMillis(2500))
                    .atMost(Duration.ofSeconds(4))
                    .untilAsserted(() -> assertThat(slow.attempts).as("borrows started").hasValue(1));

            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(slow.attempts).hasValue(2));
        }
    }

    /** A check that follows a finished one borrows again, never finding the finished borrow still in flight. */
    @Test
    void consecutiveChecksEachBorrow() {
        try (HikariDataSource dataSource = pool("jdbc:h2:mem:health-consecutive;DB_CLOSE_DELAY=-1")) {
            StoreHealthIndicator indicator = new StoreHealthIndicator("books", dataSource, Duration.ofSeconds(1));
            for (int i = 0; i < 50; i++) {
                assertThat(indicator.health().getStatus()).as("check %d", i).isEqualTo(Status.UP);
            }
        }
    }

    /**
     * Overlapping checks of a healthy store, as readiness, scrapes and load balancers make them, are all UP: a check
     * that finds a borrow in flight shares its result.
     */
    @Test
    void overlappingChecksOfAHealthyStoreAreAllUp() throws InterruptedException, ExecutionException {
        try (HikariDataSource dataSource = pool("jdbc:h2:mem:health-overlapping;DB_CLOSE_DELAY=-1")) {
            StoreHealthIndicator indicator = new StoreHealthIndicator("books", dataSource, Duration.ofSeconds(1));
            ExecutorService probes = Executors.newFixedThreadPool(4);
            try {
                Callable<List<Status>> checks = () -> {
                    List<Status> statuses = new ArrayList<>();
                    for (int i = 0; i < 250; i++) {
                        statuses.add(indicator.health().getStatus());
                    }
                    return statuses;
                };
                for (Future<List<Status>> probe : probes.invokeAll(Collections.nCopies(4, checks))) {
                    assertThat(probe.get()).hasSize(250).containsOnly(Status.UP);
                }
            } finally {
                probes.shutdownNow();
            }
        }
    }

    /** A borrow thread that can't be created leaves no borrow in flight: the next check borrows again. */
    @Test
    void aBorrowThreadThatCannotBeCreatedIsNotInFlight() {
        AtomicInteger created = new AtomicInteger();
        ThreadFactory threads = borrower -> {
            if (created.incrementAndGet() == 1) {
                throw new IllegalStateException("No thread for " + SECRET);
            }
            return daemon(borrower);
        };
        assertThatTheNextCheckBorrowsAgain(threads, created);
    }

    /** A borrow thread that can't be started leaves no borrow in flight: the next check borrows again. */
    @Test
    void aBorrowThreadThatCannotBeStartedIsNotInFlight() {
        AtomicInteger created = new AtomicInteger();
        ThreadFactory threads = borrower -> {
            if (created.incrementAndGet() == 1) {
                Thread started = daemon(() -> {
                });
                started.start();
                return started;
            }
            return daemon(borrower);
        };
        assertThatTheNextCheckBorrowsAgain(threads, created);
    }

    /**
     * A borrow that fails with an {@link Error} still answers every check that shares it, at once, instead of leaving
     * them to wait out their timeout. The error is logged at ERROR with the store and its type only, never its
     * message, and the borrow thread ends without an uncaught exception.
     */
    @Test
    void aBorrowThatFailsWithAnErrorAnswersTheChecksThatShareIt(CapturedOutput output) throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<@Nullable Throwable> uncaught = new AtomicReference<>();
        AtomicReference<@Nullable Thread> borrowThread = new AtomicReference<>();
        ThreadFactory threads = borrower -> {
            Thread thread = daemon(borrower);
            thread.setUncaughtExceptionHandler((failed, error) -> uncaught.set(error));
            borrowThread.set(thread);
            return thread;
        };
        StoreHealthIndicator indicator = new StoreHealthIndicator("books", new ErrorDataSource(release),
                Duration.ofSeconds(5), threads);
        List<Health> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> checks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread check = daemon(() -> results.add(indicator.health()));
            checks.add(check);
            check.start();
        }
        // Both checks wait on the one borrow, which waits for the release.
        await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> checks.stream().allMatch(check -> check.getState() == Thread.State.TIMED_WAITING));

        long start = System.nanoTime();
        release.countDown();
        for (Thread check : checks) {
            check.join(Duration.ofSeconds(10).toMillis());
        }

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(results)
                .hasSize(2)
                .allSatisfy(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsExactly(Map.entry("error", "The connection failed."));
                });
        Thread borrower = Objects.requireNonNull(borrowThread.get(), "a borrow ran");
        borrower.join(Duration.ofSeconds(10).toMillis());
        assertThat(borrower.isAlive()).isFalse();
        assertThat(uncaught.get()).as("an uncaught error on the borrow thread").isNull();
        assertThat(output.getOut().lines().filter(line -> line.contains(" ERROR ")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .endsWith("The health check of the store 'books' failed: java.lang.LinkageError"));
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    private static void assertThatTheNextCheckBorrowsAgain(ThreadFactory threads, AtomicInteger created) {
        try (HikariDataSource dataSource = pool("jdbc:h2:mem:health-no-thread;DB_CLOSE_DELAY=-1")) {
            StoreHealthIndicator indicator = new StoreHealthIndicator("books", dataSource, Duration.ofSeconds(1),
                    threads);

            Health first = indicator.health();
            assertThat(first.getStatus()).isEqualTo(Status.DOWN);
            assertThat(first.getDetails()).containsExactly(Map.entry("error", "The check could not start."));

            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            assertThat(created).hasValue(2);
        }
    }

    private static Thread daemon(Runnable runnable) {
        Thread thread = new Thread(runnable, "test-health");
        thread.setDaemon(true);
        return thread;
    }

    /** The borrow runs on a platform daemon thread, never a virtual one: Hikari starts its pool in a monitor. */
    @Test
    void theBorrowRunsOnAPlatformDaemonThread() {
        SlowFailingDataSource slow = new SlowFailingDataSource(Duration.ZERO);
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setPoolName("books");
            dataSource.setDataSource(slow);
            new StoreHealthIndicator("books", dataSource, Duration.ofSeconds(5)).health();

            Thread borrower = Objects.requireNonNull(slow.thread.get(), "a borrow ran");
            assertThat(borrower.isVirtual()).isFalse();
            assertThat(borrower.isDaemon()).isTrue();
            assertThat(borrower.getName()).isEqualTo("synapse-health-books");
        }
    }

    /** H2's client retries a refused connection for about 2s, so a 300 ms check times out first. */
    @Test
    void downWhenTheDatabaseIsUnreachable() {
        try (HikariDataSource dataSource = pool(UNREACHABLE)) {
            long start = System.nanoTime();
            Health health = new StoreHealthIndicator("books", dataSource, Duration.ofMillis(300)).health();

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsExactly(Map.entry("error", "No connection within 300 ms."));
        }
    }

    /** The failure's type is logged at DEBUG, never its message. */
    @Test
    void downWhenTheConnectionFails(CapturedOutput output) {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        String logger = StoreHealthIndicator.class.getName();
        logging.setLogLevel(logger, LogLevel.DEBUG);
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setPoolName("books");
            dataSource.setDataSource(new FailingDataSource());
            Health health = new StoreHealthIndicator("books", dataSource, Duration.ofSeconds(5)).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsExactly(Map.entry("error", "The connection failed."));
        } finally {
            logging.setLogLevel(logger, null);
        }
        assertThat(output.getOut().lines().filter(line -> line.contains("The health check of the store")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" DEBUG ")
                        .endsWith("The health check of the store 'books' failed: java.sql.SQLException"));
    }

    @Test
    void downWhenInterrupted() {
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setPoolName("books");
            dataSource.setDataSource(new SlowDataSource(Duration.ofMillis(200)));
            Thread.currentThread().interrupt();
            Health health = new StoreHealthIndicator("books", dataSource, Duration.ofSeconds(5)).health();

            assertThat(Thread.interrupted()).as("the interrupt is kept").isTrue();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsExactly(Map.entry("error", "The check was interrupted."));
        }
    }

    @Test
    void downWhenTheConnectionIsNotValid() throws SQLException {
        Connection connection = mock(Connection.class);
        given(connection.isValid(1)).willReturn(false);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        given(dataSource.getPoolName()).willReturn("books");
        given(dataSource.getConnection()).willReturn(connection);

        Health health = new StoreHealthIndicator("books", dataSource, Duration.ofSeconds(5)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsExactly(Map.entry("error", "The connection is not valid."));
        then(connection).should().close();
    }

    /** With every connection in use, the indicator reports the pool busy, without waiting for a connection. */
    @Test
    void aBusyPoolStaysUp() throws SQLException {
        try (HikariDataSource dataSource = pool("jdbc:h2:mem:health-busy;DB_CLOSE_DELAY=-1")) {
            dataSource.setMaximumPoolSize(1);
            try (Connection held = dataSource.getConnection()) {
                assertThat(held.isValid(1)).isTrue();
                long start = System.nanoTime();
                Health health = new StoreHealthIndicator("books", dataSource, Duration.ofSeconds(5)).health();

                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
                assertThat(health.getStatus()).isEqualTo(Status.UP);
                assertThat(health.getDetails()).containsExactly(Map.entry("pool", "busy"));
                assertThat(pool(dataSource).getThreadsAwaitingConnection()).isZero();
            }
        }
    }

    /** A connection that arrives after the timeout is closed, so the pool gets it back. */
    @Test
    void aLateBorrowIsClosed() {
        SlowDataSource slow = new SlowDataSource(Duration.ofMillis(800));
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setPoolName("books");
            dataSource.setDataSource(slow);
            dataSource.setMaximumPoolSize(1);
            long start = System.nanoTime();
            Health health = new StoreHealthIndicator("books", dataSource, Duration.ofMillis(200)).health();

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(700));
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsExactly(Map.entry("error", "No connection within 200 ms."));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(dataSource.getHikariPoolMXBean()).as("the pool has started").isNotNull();
                HikariPoolMXBean pool = pool(dataSource);
                assertThat(pool.getTotalConnections()).as("the late borrow arrived").isEqualTo(1);
                assertThat(pool.getActiveConnections()).as("the late borrow was closed").isZero();
            });
            assertThat(slow.connections).hasValue(1);
        }
    }

    @Test
    void detailsNeverContainTheUrl() {
        try (HikariDataSource dataSource = pool(UNREACHABLE)) {
            for (Duration timeout : new Duration[]{Duration.ofMillis(1), Duration.ofSeconds(1)}) {
                Health health = new StoreHealthIndicator("books", dataSource, timeout).health();

                assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                assertThat(health.getDetails().toString())
                        .doesNotContain(SECRET, "127.0.0.1", "jdbc:", "h2", "none", "Exception");
            }
        }
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setPoolName("books");
            pool.setDataSource(new FailingDataSource());
            for (DataSource failing : new DataSource[]{pool, new FailingDataSource()}) {
                Health health = new StoreHealthIndicator("books", failing, Duration.ofSeconds(5)).health();

                assertThat(health.getDetails())
                        .as("the failure path")
                        .containsExactly(
                                Map.entry("error", "The connection failed."));
                assertThat(health.getDetails().toString()).doesNotContain(SECRET, "Refused", "Exception");
            }
        }
    }

    /** A DataSource that is not a Hikari pool is checked by borrowing, as a pool is. */
    @Test
    void upForADataSourceThatIsNotAPool() {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:health-plain;DB_CLOSE_DELAY=-1", "sa", "");

        Health health = new StoreHealthIndicator("books", dataSource, Duration.ofSeconds(1)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEmpty();
    }

    /** The busy check reaches a Hikari pool through a DataSource that wraps it. */
    @Test
    void aBusyWrappedPoolStaysUp() throws SQLException {
        try (HikariDataSource pool = pool("jdbc:h2:mem:health-wrapped-busy;DB_CLOSE_DELAY=-1")) {
            pool.setMaximumPoolSize(1);
            try (Connection held = pool.getConnection()) {
                assertThat(held.isValid(1)).isTrue();
                Health health = new StoreHealthIndicator("books", new DelegatingDataSource(pool), Duration.ofSeconds(5))
                        .health();

                assertThat(health.getDetails()).containsExactly(Map.entry("pool", "busy"));
            }
        }
    }

    @Test
    void theTimeoutIsKept() {
        try (HikariDataSource dataSource = pool(UNREACHABLE)) {
            assertThat(new StoreHealthIndicator("books", dataSource, Duration.ofMillis(250)).timeout())
                    .isEqualTo(Duration.ofMillis(250));
        }
    }

    private static HikariDataSource pool(String url) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("books");
        dataSource.setJdbcUrl(url);
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static HikariPoolMXBean pool(HikariDataSource dataSource) {
        return Objects.requireNonNull(dataSource.getHikariPoolMXBean(), "the pool has started");
    }

    /** A data source that fails to open any connection. */
    static final class FailingDataSource extends DelegatingDataSource {

        FailingDataSource() {
            super(new DriverManagerDataSource("jdbc:h2:mem:health-failing", "sa", ""));
        }

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("Refused by " + SECRET);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }

    /** A data source whose connection attempt waits for a release, then fails with an {@link Error}. */
    static final class ErrorDataSource extends DelegatingDataSource {

        private final CountDownLatch release;

        ErrorDataSource(CountDownLatch release) {
            super(new DriverManagerDataSource("jdbc:h2:mem:health-error", "sa", ""));
            this.release = release;
        }

        @Override
        public Connection getConnection() throws SQLException {
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted", ex);
            }
            throw new LinkageError("No driver for " + SECRET);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }

    /** A data source whose connection attempts each take a while and then fail; it counts them. */
    static final class SlowFailingDataSource extends DelegatingDataSource {

        final AtomicInteger attempts = new AtomicInteger();

        final AtomicInteger finished = new AtomicInteger();

        final AtomicReference<@Nullable Thread> thread = new AtomicReference<>();

        private final Duration delay;

        SlowFailingDataSource(Duration delay) {
            super(new DriverManagerDataSource("jdbc:h2:mem:health-slow-failing", "sa", ""));
            this.delay = delay;
        }

        @Override
        public Connection getConnection() throws SQLException {
            attempts.incrementAndGet();
            thread.set(Thread.currentThread());
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                finished.incrementAndGet();
            }
            throw new SQLException("Refused by " + SECRET);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }

    /** An H2 data source that takes a while to open each connection. */
    static final class SlowDataSource extends DelegatingDataSource {

        final AtomicInteger connections = new AtomicInteger();

        private final Duration delay;

        SlowDataSource(Duration delay) {
            super(new DriverManagerDataSource("jdbc:h2:mem:health-late;DB_CLOSE_DELAY=-1", "sa", ""));
            this.delay = delay;
        }

        @Override
        public Connection getConnection() throws SQLException {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted", ex);
            }
            connections.incrementAndGet();
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }
}
