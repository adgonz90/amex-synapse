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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * The health of one store's DataSource. When it is, or wraps, a Hikari pool that has connections and none of them
 * idle, the store is {@code UP} with the detail {@code pool: busy}, without waiting for a connection: a busy pool is
 * not a failing database. Otherwise, and for any other DataSource, the indicator borrows a connection on a platform
 * daemon thread and waits at most the store's {@code health.timeout} for it to pass
 * {@link Connection#isValid(int) isValid(1)}. A connection that arrives after the timeout is closed as soon as it
 * does, so the pool gets it back.
 * <p>
 * At most one borrow is in flight per store. A check that finds one in flight shares its result: it waits on the same
 * borrow for at most its own timeout, and reports the timeout if the borrow hasn't answered by then. So overlapping
 * checks of a healthy store are all {@code UP}, and while the database is down checks never queue up borrows. A
 * borrow thread that can't be created or started leaves no borrow in flight. The borrow is not a virtual thread
 * because Hikari starts its pool inside a monitor, which pins a virtual thread's carrier on Java 21.
 * <p>
 * The details never carry the URL, a credential, an exception or a driver's message: a failing store reports a fixed
 * {@code error} text, and the failure's type is logged at DEBUG only.
 */
public final class StoreHealthIndicator implements HealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(StoreHealthIndicator.class);

    /** How long {@link Connection#isValid(int)} may take, in seconds. */
    private static final int VALIDATION_SECONDS = 1;

    private final String store;

    private final DataSource dataSource;

    /** The Hikari pool the DataSource is or wraps, for the busy check; {@code null} for any other DataSource. */
    private final @Nullable HikariDataSource pool;

    private final Duration timeout;

    private final ThreadFactory threads;

    /** The result of the borrow in flight, which every check made meanwhile shares; {@code null} when there is none. */
    private final AtomicReference<@Nullable CompletableFuture<Boolean>> inFlight = new AtomicReference<>();

    /**
     * Creates the indicator.
     *
     * @param store the store's name, for the borrow thread's name and the log
     * @param dataSource the store's DataSource; unwrapping it to a Hikari pool opens no connection
     * @param timeout how long a check waits for a valid connection
     */
    public StoreHealthIndicator(String store, DataSource dataSource, Duration timeout) {
        this(store, dataSource, timeout, borrower -> {
            Thread thread = new Thread(borrower, "synapse-health-" + store);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Creates the indicator with the factory of its borrow threads.
     *
     * @param store the store's name, for the log
     * @param dataSource the store's DataSource
     * @param timeout how long a check waits for a valid connection
     * @param threads the factory of the borrow threads
     */
    StoreHealthIndicator(String store, DataSource dataSource, Duration timeout, ThreadFactory threads) {
        this.store = store;
        this.dataSource = dataSource;
        this.pool = hikari(dataSource);
        this.timeout = timeout;
        this.threads = threads;
    }

    /**
     * Returns how long a check waits for a valid connection.
     *
     * @return the store's {@code health.timeout}
     */
    public Duration timeout() {
        return timeout;
    }

    @Override
    public Health health() {
        HikariPoolMXBean connections = pool == null ? null : pool.getHikariPoolMXBean();
        if (connections != null && connections.getTotalConnections() > 0 && connections.getIdleConnections() == 0) {
            return Health.up().withDetail("pool", "busy").build();
        }
        CompletableFuture<Boolean> check = inFlight.get();
        if (check == null) {
            CompletableFuture<Boolean> borrow = new CompletableFuture<>();
            check = inFlight.compareAndExchange(null, borrow);
            if (check == null) {
                if (!start(borrow)) {
                    return down("The check could not start.");
                }
                check = borrow;
            }
        }
        try {
            return check.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
                    ? Health.up().build()
                    : down("The connection is not valid.");
        } catch (TimeoutException ex) {
            return timedOut();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return down("The check was interrupted.");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (logger.isDebugEnabled() && cause != null) {
                logger
                        .debug("The health check of the store '{}' failed: {}", store,
                                cause.getClass().getName());
            }
            return down("The connection failed.");
        }
    }

    /**
     * Starts the borrow thread. When it can't be created or started, the borrow is no longer in flight: the checks
     * that joined it fail, and the next check borrows again.
     *
     * @return whether the thread started
     */
    private boolean start(CompletableFuture<Boolean> borrow) {
        boolean started = false;
        try {
            threads.newThread(() -> validate(borrow)).start();
            started = true;
        } catch (RuntimeException ex) {
            if (logger.isDebugEnabled()) {
                logger
                        .debug("The health check of the store '{}' could not start: {}", store,
                                ex.getClass().getName());
            }
        } finally {
            if (!started) {
                inFlight.compareAndSet(borrow, null);
                borrow.completeExceptionally(new IllegalStateException("The borrow thread did not start."));
            }
        }
        return started;
    }

    /**
     * Borrows a connection and validates it. The connection is closed, which gives it back to the pool, and the borrow
     * is no longer in flight, before the checks that share it get their answer. Every failure answers them, an
     * {@link Error} included. An {@code Error} is logged at ERROR with the store and its type only, never its message,
     * and is not rethrown, so the thread's default handler never prints it.
     */
    private void validate(CompletableFuture<Boolean> borrow) {
        boolean valid = false;
        Throwable failure = null;
        try (Connection connection = dataSource.getConnection()) {
            valid = connection.isValid(VALIDATION_SECONDS);
        } catch (SQLException | RuntimeException ex) {
            failure = ex;
        } catch (Error ex) {
            // Not rethrown: the JVM's default handler would print the message and stack outside the logging backend.
            failure = ex;
            logger.error("The health check of the store '{}' failed: {}", store, ex.getClass().getName());
        } finally {
            inFlight.compareAndSet(borrow, null);
            if (failure != null) {
                borrow.completeExceptionally(failure);
            } else {
                borrow.complete(valid);
            }
        }
    }

    /** Returns the Hikari pool a DataSource is or wraps, or {@code null}. */
    private static @Nullable HikariDataSource hikari(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari;
        }
        try {
            return dataSource.isWrapperFor(HikariDataSource.class) ? dataSource.unwrap(HikariDataSource.class) : null;
        } catch (SQLException ex) {
            return null;
        }
    }

    private Health timedOut() {
        return down("No connection within " + timeout.toMillis() + " ms.");
    }

    private static Health down(String error) {
        return Health.down().withDetail("error", error).build();
    }
}
