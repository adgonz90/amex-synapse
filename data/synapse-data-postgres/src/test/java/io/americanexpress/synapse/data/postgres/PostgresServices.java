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
package io.americanexpress.synapse.data.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts the fixture service in a context of its own, outside the test's, with each store connected to a container
 * by its {@code url}, {@code username} and {@code password}, as a deployed service is; and prepares databases for it.
 */
final class PostgresServices {

    /** The tables of the public schema, sorted. */
    static final String TABLES = "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
            + "ORDER BY table_name";

    /** The sequences of the public schema, sorted. */
    static final String SEQUENCES = "SELECT sequence_name FROM information_schema.sequences "
            + "WHERE sequence_schema = 'public' ORDER BY sequence_name";

    /** The foreign keys of the public schema, as {@code <table> -> <referenced table>}, sorted. */
    static final String FOREIGN_KEYS = "SELECT c.conrelid::regclass || ' -> ' || c.confrelid::regclass "
            + "FROM pg_constraint c WHERE c.contype = 'f' ORDER BY 1";

    private PostgresServices() {
    }

    /**
     * Returns a builder for the fixture service, with more sources.
     *
     * @param sources more configuration classes, such as a bean the test adds
     * @return the builder
     */
    static SpringApplicationBuilder service(Class<?>... sources) {
        return new SpringApplicationBuilder(PostgresTestApplication.class)
                .sources(sources)
                .web(WebApplicationType.NONE);
    }

    /**
     * Returns the arguments that run the service as {@code test}, with the books store on one container and the
     * legacy store on another, then more arguments, which win.
     *
     * @param books the books store's container
     * @param legacy the legacy store's container
     * @param more more arguments, such as {@code --synapse.data.stores.books.url=...}
     * @return the arguments
     */
    static String[] arguments(PostgreSQLContainer books, PostgreSQLContainer legacy, String... more) {
        return arguments(books, books.getJdbcUrl(), legacy, more);
    }

    /**
     * Returns the arguments that run the service as {@code test}, with the books store on a database of one container
     * and the legacy store on another container, then more arguments. Each key appears once: Boot joins the values of
     * a repeated command-line argument.
     *
     * @param books the books store's container
     * @param booksUrl the JDBC URL of the books store's database in that container
     * @param legacy the legacy store's container
     * @param more more arguments, such as {@code --synapse.data.stores.books.jpa.hibernate.ddl-auto=update}
     * @return the arguments
     */
    static String[] arguments(PostgreSQLContainer books, String booksUrl, PostgreSQLContainer legacy,
            String... more) {
        return arguments(books, booksUrl, books.getPassword(), legacy, more);
    }

    /**
     * Returns the arguments of {@link #arguments(PostgreSQLContainer, String, PostgreSQLContainer, String...)}, with
     * another password for the books store.
     *
     * @param books the books store's container
     * @param booksUrl the JDBC URL of the books store's database in that container
     * @param booksPassword the books store's password, such as an empty one
     * @param legacy the legacy store's container
     * @param more more arguments
     * @return the arguments
     */
    static String[] arguments(PostgreSQLContainer books, String booksUrl, String booksPassword,
            PostgreSQLContainer legacy, String... more) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--spring.profiles.active=test");
        arguments.addAll(store("books", books.getUsername(), booksPassword, booksUrl));
        arguments.addAll(store("legacy", legacy.getUsername(), legacy.getPassword(), legacy.getJdbcUrl()));
        arguments.addAll(List.of(more));
        return arguments.toArray(String[]::new);
    }

    private static List<String> store(String store, String username, String password, String url) {
        String prefix = "--synapse.data.stores." + store;
        return List.of(prefix + ".url=" + url, prefix + ".username=" + username, prefix + ".password=" + password);
    }

    /**
     * Creates an empty database in a container, dropping one of that name first.
     *
     * @param container the container
     * @param name the database name
     * @return the database's JDBC URL
     * @throws SQLException when the database can't be created
     */
    static String emptyDatabase(PostgreSQLContainer container, String name) throws SQLException {
        try (Connection connection = connection(container, container.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + name);
        }
        return "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
    }

    /**
     * Opens a connection to a database of a container, outside every store's pool.
     *
     * @param container the container
     * @param url the database's JDBC URL
     * @return the connection
     * @throws SQLException when it can't connect
     */
    static Connection connection(PostgreSQLContainer container, String url) throws SQLException {
        return DriverManager.getConnection(url, container.getUsername(), container.getPassword());
    }

    /**
     * Returns the names of the tables, sequences or foreign keys of a database's public schema, sorted.
     *
     * @param container the container
     * @param url the database's JDBC URL
     * @param sql a query that returns the names
     * @return the names
     * @throws SQLException when the query fails
     */
    static List<String> names(PostgreSQLContainer container, String url, String sql) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = connection(container, url);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                names.add(result.getString(1));
            }
        }
        return names;
    }
}
