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
package io.americanexpress.synapse.test.data;

import java.util.concurrent.atomic.AtomicInteger;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A {@link JdbcDatabaseContainer} that runs no container: it answers with fixed connection settings, so the tests of
 * {@link StoreConnection} need no Docker. {@link #start()} only marks it running.
 */
public final class StubJdbcContainer extends JdbcDatabaseContainer<StubJdbcContainer> {

    private final String jdbcUrl;

    private final String username;

    private final String password;

    private final AtomicInteger starts = new AtomicInteger();

    private volatile boolean running;

    /**
     * Creates a stub that is already running.
     *
     * @param jdbcUrl the URL it answers
     * @param username the user name it answers
     * @param password the password it answers
     */
    public StubJdbcContainer(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, true);
    }

    /**
     * Creates a stub.
     *
     * @param jdbcUrl the URL it answers
     * @param username the user name it answers
     * @param password the password it answers
     * @param running whether it is running already
     */
    public StubJdbcContainer(String jdbcUrl, String username, String password, boolean running) {
        super(DockerImageName.parse("synapse/stub:1"));
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.running = running;
    }

    @Override
    public void start() {
        starts.incrementAndGet();
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns how often {@link #start()} was called.
     *
     * @return the number of starts
     */
    public int starts() {
        return starts.get();
    }

    @Override
    public String getDriverClassName() {
        return "org.h2.Driver";
    }

    @Override
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    protected String getTestQueryString() {
        return "SELECT 1";
    }
}
