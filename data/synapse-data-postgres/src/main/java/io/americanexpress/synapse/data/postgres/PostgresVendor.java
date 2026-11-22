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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.Incubating;
import io.americanexpress.synapse.data.vendor.DataStoreVendor;

/**
 * PostgreSQL, for every store whose URL starts with {@code jdbc:postgresql:}, the prefix the PostgreSQL JDBC driver
 * accepts. Hibernate resolves its dialect from the product name {@code PostgreSQL}, and JDBC stores use Spring Data
 * JDBC's {@code JdbcPostgresDialect}.
 * <p>
 * A store without {@code database-version} is taken to run PostgreSQL 15, Synapse's default
 * ({@link #defaultDatabaseVersion()}), which a later release may raise. Hibernate 7.4 supports PostgreSQL 14 and
 * later: a store on a 14 server sets {@code database-version: 14}. Every service should set its server's version with
 * {@code synapse.data.stores.<name>.database-version}.
 * <p>
 * A store says where to connect in its URL only: it names a host and a database
 * ({@code jdbc:postgresql://<host>[:<port>]/<database>}), and no parameter replaces them ({@code host}, {@code port},
 * {@code dbname} and their {@code PG*} names) or reads them from a {@code pg_service.conf} file ({@code service}).
 * The driver properties that choose the target, the user, the schema or the session's settings, or that hold a
 * secret, are blocked in {@code hikari.data-source-properties}: the schema and options go in the URL, where they are
 * visible. The driver's class-path configuration, {@code org/postgresql/driverconfig.properties}, is forbidden.
 */
@Incubating
public final class PostgresVendor implements DataStoreVendor {

    /** The database version a store has when it sets none; not public, so callers read it at run time. */
    private static final String DEFAULT_DATABASE_VERSION = "15";

    private static final String URL_PREFIX = "jdbc:postgresql:";

    private static final String NO_HOST = "names no host";

    private static final String NO_DATABASE = "names no database";

    private static final String SERVICE = "service";

    /** The URL parameters PgJDBC reads after the URL's host and path, in lower case, each with Synapse's spelling. */
    private static final Map<String, String> TARGET_PARAMETERS = Map
            .of("host", "host", "port", "port", "dbname", "dbname", "pghost", "PGHOST", "pgport", "PGPORT", "pgdbname",
                    "PGDBNAME", SERVICE, SERVICE);

    private static final Set<String> BLOCKED_DRIVER_PROPERTIES = Set
            .of("PGHOST", "PGPORT", "PGDBNAME", "host", "port", "dbname", "user", "password", "sslpassword",
                    "socketFactory", "socketFactoryArg", "options", "currentSchema", SERVICE);

    @Override
    public String name() {
        return "postgres";
    }

    @Override
    public boolean supports(String jdbcUrl) {
        return jdbcUrl.startsWith(URL_PREFIX);
    }

    @Override
    public String defaultDatabaseVersion() {
        return DEFAULT_DATABASE_VERSION;
    }

    @Override
    public String databaseProductName() {
        return "PostgreSQL";
    }

    @Override
    public String hibernateDialect() {
        return "org.hibernate.dialect.PostgreSQLDialect";
    }

    @Override
    public String jdbcDialect() {
        return "org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect";
    }

    /**
     * The driver properties that choose the host, port or database ({@code PGHOST}, {@code PGPORT}, {@code PGDBNAME},
     * their URL names {@code host}, {@code port}, {@code dbname}, and {@code service}), the socket that reaches it
     * ({@code socketFactory}, {@code socketFactoryArg}), the user or a secret ({@code user}, {@code password},
     * {@code sslpassword}), or the schema and session settings, a role included ({@code currentSchema},
     * {@code options}). The certificates and authentication plugins that prove the store's user name stay allowed.
     */
    @Override
    public Set<String> blockedDriverProperties() {
        return BLOCKED_DRIVER_PROPERTIES;
    }

    /**
     * Finds a URL that doesn't name a host and a database, or that has a parameter which replaces them or reads them
     * from a service file. PgJDBC would then connect where its driver properties, its class-path configuration, a
     * {@code pg_service.conf} file or {@code localhost:5432} say. The parameter is matched in any case and
     * percent-decoded, and the problem names Synapse's spelling of it, never the URL's text.
     */
    @Override
    public @Nullable String urlProblem(String jdbcUrl) {
        String rest = jdbcUrl.startsWith(URL_PREFIX) ? jdbcUrl.substring(URL_PREFIX.length()) : "";
        if (!rest.startsWith("//")) {
            return NO_HOST;
        }
        String address = rest.substring(2);
        int query = address.indexOf('?');
        String parameters = query < 0 ? "" : address.substring(query + 1);
        String path = query < 0 ? address : address.substring(0, query);
        int slash = path.indexOf('/');
        if (!namesHosts(slash < 0 ? path : path.substring(0, slash))) {
            return NO_HOST;
        }
        if (slash < 0 || slash == path.length() - 1) {
            return NO_DATABASE;
        }
        String parameter = targetParameter(parameters);
        if (parameter == null) {
            return null;
        }
        return SERVICE.equals(parameter)
                ? "has the parameter service, which reads where and as whom to connect from a pg_service.conf file"
                : "has the parameter " + parameter + ", which would replace the host, port or database it names";
    }

    @Override
    public String urlForm() {
        return "jdbc:postgresql://<host>[:<port>][,<host>[:<port>]...]/<database>[?<parameters>]";
    }

    @Override
    public List<String> forbiddenResources() {
        return List.of("org/postgresql/driverconfig.properties");
    }

    /** Whether every entry of a host list ({@code h1:5432,[::1]:5433}) names a host. */
    private static boolean namesHosts(String hosts) {
        for (String entry : hosts.split(",", -1)) {
            String host;
            if (entry.startsWith("[")) {
                int end = entry.indexOf(']');
                host = end < 0 ? "" : entry.substring(1, end);
            } else {
                int port = entry.lastIndexOf(':');
                host = port < 0 ? entry : entry.substring(0, port);
            }
            if (host.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** Returns Synapse's spelling of the first parameter that chooses the target, or {@code null}. */
    private static @Nullable String targetParameter(String parameters) {
        for (String token : parameters.split("&")) {
            int equals = token.indexOf('=');
            String name = equals < 0 ? token : token.substring(0, equals);
            for (String form : List.of(name, decoded(name))) {
                String parameter = TARGET_PARAMETERS.get(form.trim().toLowerCase(Locale.ROOT));
                if (parameter != null) {
                    return parameter;
                }
            }
        }
        return null;
    }

    private static String decoded(String name) {
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return name;
        }
    }
}
