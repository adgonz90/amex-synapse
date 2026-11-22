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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PostgreSQL vendor's checks (I1): a store's URL names a host and a database, and no URL parameter replaces
 * them; the driver properties that choose where, as whom or in which schema to connect, or that hold a secret, are
 * blocked; and the driver's class-path configuration file is forbidden.
 */
class PostgresVendorTest {

    /**
     * Every PgJDBC property that a store may set in {@code hikari.data-source-properties}: how the store connects
     * (timeouts, SSL mode and certificates, which prove the same user name), how it authenticates its user name
     * (plugins, Kerberos, GSS, SSPI), and how the driver behaves. A PgJDBC upgrade that adds a property fails
     * {@link #everyDriverPropertyIsClassified()} until the property is added here or to the vendor's blocked list.
     */
    static final Set<String> ALLOWED = Set
            .of("adaptiveFetch", "adaptiveFetchMaximum", "adaptiveFetchMinimum", "allowEncodingChanges",
                    "ApplicationName", "assumeMinServerVersion", "authenticationPluginClassName", "autosave",
                    "binaryTransfer", "binaryTransferDisable", "binaryTransferEnable", "cancelSignalTimeout",
                    "channelBinding", "classLoaderStrategy", "cleanupSavepoints", "connectExecutor",
                    "connectExecutorArg", "connectTimeout", "convertBooleanToNumeric", "databaseMetadataCacheFields",
                    "databaseMetadataCacheFieldsMiB", "defaultRowFetchSize", "disableColumnSanitiser",
                    "escapeSyntaxCallMode", "flushCacheOnDdl", "groupStartupParameters", "gssEncMode", "gsslib",
                    "gssResponseTimeout", "gssUseDefaultCreds", "hideUnprivilegedObjects", "hostRecheckSeconds",
                    "jaasApplicationName", "jaasLogin", "kerberosServerName", "loadBalanceHosts",
                    "localSocketAddress", "loggerFile", "loggerLevel", "loginTimeout", "logServerErrorDetail",
                    "logUnclosedConnections", "maxResultBuffer", "maxSendBufferSize", "pemKeyAlgorithm",
                    "preferQueryMode", "preparedStatementCacheQueries", "preparedStatementCacheSizeMiB",
                    "prepareThreshold", "protocolVersion", "queryTimeout", "quoteReturningIdentifiers", "readOnly",
                    "readOnlyMode", "receiveBufferSize", "replication", "requireAuth", "reWriteBatchedInserts",
                    "reWriteBatchedInsertsSize", "scramMaxIterations", "sendBufferSize", "socketTimeout", "ssl",
                    "sslcert", "sslfactory", "sslfactoryarg", "sslhostnameverifier", "sslkey", "sslmode",
                    "sslNegotiation", "sslpasswordcallback", "sslResponseTimeout", "sslrootcert", "sspiServiceClass",
                    "stringtype", "targetServerType", "tcpKeepAlive", "tcpNoDelay", "unknownLength", "useSpnego",
                    "xmlFactoryFactory");

    private final PostgresVendor vendor = new PostgresVendor();

    @Test
    void blocksTheDriverPropertiesThatChooseTheTargetIdentitySchemaOrASecret() {
        assertThat(vendor.blockedDriverProperties())
                .containsExactlyInAnyOrder("PGHOST", "PGPORT", "PGDBNAME", "host", "port", "dbname", "user",
                        "password", "sslpassword", "socketFactory", "socketFactoryArg", "options", "currentSchema",
                        "service");
    }

    /** Each of PgJDBC's properties is blocked or allowed, never both, never neither (I1). */
    @Test
    void everyDriverPropertyIsClassified() {
        Set<String> blocked = lowerCase(vendor.blockedDriverProperties());
        Set<String> allowed = lowerCase(ALLOWED);
        List<String> unclassified = new ArrayList<>();
        for (PGProperty property : PGProperty.values()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (blocked.contains(name) == allowed.contains(name)) {
                unclassified.add(property.getName());
            }
        }

        assertThat(unclassified).as("PgJDBC properties neither or both blocked and allowed").isEmpty();
        assertThat(blocked).doesNotContainAnyElementsOf(allowed);
        Set<String> known = new TreeSet<>(lowerCase(Set.of("host", "port", "dbname")));
        for (PGProperty property : PGProperty.values()) {
            known.add(property.getName().toLowerCase(Locale.ROOT));
        }
        assertThat(known)
                .as("every classified name is a PgJDBC property or one of its URL aliases")
                .containsAll(blocked)
                .containsAll(allowed);
    }

    @Test
    void forbidsTheDriversClassPathConfiguration() {
        assertThat(vendor.forbiddenResources()).containsExactly("org/postgresql/driverconfig.properties");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://db/books", "jdbc:postgresql://db:5432/books",
            "jdbc:postgresql://h1:5432,h2:5432/books?targetServerType=primary", "jdbc:postgresql://[::1]:5432/books",
            "jdbc:postgresql://[2001:db8::1],[2001:db8::2]:5433/books",
            "jdbc:postgresql://db/books?currentSchema=books&options=-c%20statement_timeout=5000",
            "jdbc:postgresql://db/books?sslmode=verify-full&sslrootcert=/ca.pem&ApplicationName=books",
            "jdbc:postgresql://db/books?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg"
                    + "&socketFactoryArg=/var/run/postgresql/.s.PGSQL.5432",
            "jdbc:postgresql://db/books?hostRecheckSeconds=10&portal=1&loggerLevel=OFF", "jdbc:postgresql://db/books?",
            "jdbc:postgresql://db/books?ssl"})
    void acceptsAUrlThatNamesAHostAndADatabase(String url) {
        assertThat(vendor.urlProblem(url)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql:books", "jdbc:postgresql:/", "jdbc:postgresql:",
            "jdbc:postgresql:///books",
            "jdbc:postgresql://:5432/books", "jdbc:postgresql://db,/books", "jdbc:postgresql://,db/books",
            "jdbc:postgresql://db,:5432/books", "jdbc:postgresql://?service=books", "jdbc:postgresql:books?host=db"})
    void aUrlWithoutAHostNamesNoHost(String url) {
        assertThat(vendor.urlProblem(url)).isEqualTo("names no host");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://db", "jdbc:postgresql://db:5432", "jdbc:postgresql://db/",
            "jdbc:postgresql://db/?ssl=true", "jdbc:postgresql://db?dbname=books", "jdbc:postgresql://db:1/?",
            "jdbc:postgresql://h1,h2"})
    void aUrlWithoutADatabaseNamesNoDatabase(String url) {
        assertThat(vendor.urlProblem(url)).isEqualTo("names no database");
    }

    /**
     * PgJDBC reads {@code host}, {@code port} and {@code dbname} parameters (and their {@code PG*} names) after the
     * URL's host and path, so they replace them; {@code service} reads them, and a password, from a
     * {@code pg_service.conf} file. The parameter is matched in any case, and percent-decoded, but the problem names
     * Synapse's spelling of it, never the URL's text.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"jdbc:postgresql://127.0.0.1:1/books?host=db|host",
            "jdbc:postgresql://127.0.0.1:1/books?port=5432|port",
            "jdbc:postgresql://127.0.0.1:1/books?dbname=other|dbname",
            "jdbc:postgresql://127.0.0.1:1/books?host=db&port=5432&dbname=other|host",
            "jdbc:postgresql://127.0.0.1:1/books?ssl=true&PGHOST=db|PGHOST",
            "jdbc:postgresql://127.0.0.1:1/books?PGPORT=5432|PGPORT",
            "jdbc:postgresql://127.0.0.1:1/books?PGDBNAME=other|PGDBNAME",
            "jdbc:postgresql://127.0.0.1:1/books?HOST=db|host", "jdbc:postgresql://127.0.0.1:1/books?pgHost=db|PGHOST",
            "jdbc:postgresql://127.0.0.1:1/books?h%6Fst=db|host", "jdbc:postgresql://127.0.0.1:1/books?dbname|dbname"})
    void aParameterThatReplacesTheTargetIsAProblem(String url, String parameter) {
        assertThat(vendor.urlProblem(url))
                .isEqualTo("has the parameter " + parameter + ", which would replace the host, port or database it "
                        + "names");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://127.0.0.1:1/books?service=books",
            "jdbc:postgresql://127.0.0.1:1/books?ssl=true&Service=books", "jdbc:postgresql://db/books?SERVICE"})
    void theServiceParameterIsAProblem(String url) {
        assertThat(vendor.urlProblem(url))
                .isEqualTo("has the parameter service, which reads where and as whom to connect from a "
                        + "pg_service.conf file");
    }

    @Test
    void namesItsUrlForm() {
        assertThat(vendor.urlForm())
                .isEqualTo("jdbc:postgresql://<host>[:<port>][,<host>[:<port>]...]/<database>[?<parameters>]");
    }

    private static Set<String> lowerCase(Set<String> names) {
        return names.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
