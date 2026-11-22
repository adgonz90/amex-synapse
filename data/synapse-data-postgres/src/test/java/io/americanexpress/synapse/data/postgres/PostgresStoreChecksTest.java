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

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.Driver;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.core.NestedExceptionUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.internal.store.JdbcUrls;
import io.americanexpress.synapse.data.jpa.autoconfigure.SynapseDataJpaAutoConfiguration;
import io.americanexpress.synapse.data.postgres.autoconfigure.SynapsePostgresAutoConfiguration;
import io.americanexpress.synapse.data.postgres.fixture.books.BooksStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store checks for a store on PostgreSQL, with PostgreSQL's URL forms and keys: credentials in the URL, and keys
 * that would connect the store somewhere else or change its schema behind {@code ddl-auto}, fail startup with a
 * message that names the key, never its value. No store connects (the port is closed); no Docker.
 */
@ExtendWith(OutputCaptureExtension.class)
class PostgresStoreChecksTest {

    private static final String PREFIX = "synapse.data.stores.books";

    private static final String URL = "jdbc:postgresql://127.0.0.1:1/books";

    private static final String URL_PROPERTY = PREFIX + ".url=" + URL;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataJpaAutoConfiguration.class, SynapsePostgresAutoConfiguration.class,
                            TransactionManagerCustomizationAutoConfiguration.class,
                            TransactionAutoConfiguration.class))
            .withUserConfiguration(BooksStore.class)
            .withPropertyValues(PREFIX + ".username=app", PREFIX + ".password=",
                    PREFIX + ".migrations.enabled=false", PREFIX + ".jpa.hibernate.ddl-auto=none");

    /**
     * PgJDBC's URL forms that carry a user name or password, or may (Review Focus 1): user information, before one
     * host or a host list, and the {@code user}, {@code password} and {@code sslpassword} parameters, in any case and
     * percent-encoded.
     */
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://app:s3cr3t@db:5432/books", "jdbc:postgresql://app@db/books",
            "jdbc:postgresql://app:s3cr3t@h1:5432,h2:5432/books?targetServerType=primary",
            "jdbc:postgresql://h1:5432,app:s3cr3t@h2:5432/books", "jdbc:postgresql://app:s3cr3t@[::1]:5432/books",
            "jdbc:postgresql://db/books?user=app&password=s3cr3t", "jdbc:postgresql://db/books?password=s3cr3t",
            "jdbc:postgresql:books?user=app", "jdbc:postgresql:///books?password=s3cr3t",
            "jdbc:postgresql://h1,h2/books?loadBalanceHosts=true&password=s3cr3t",
            "jdbc:postgresql://db/books?sslmode=verify-full&sslpassword=s3cr3t",
            "jdbc:postgresql://db/books?PASSWORD=s3cr3t", "jdbc:postgresql://db/books?%70assword=s3cr3t",
            "jdbc:postgresql://app%3As3cr3t%40db/books"})
    void credentialsInTheUrlFailWithoutShowingThem(String url, CapturedOutput output) {
        runner.withPropertyValues(PREFIX + ".url=" + url).run(context -> {
            SynapseConfigurationException failure = configurationFailure(context);
            assertThat(failure)
                    .hasMessage("The store 'books' has credentials in synapse.data.stores.books.url, and "
                            + "drivers and pools log the URL.");
            assertThat(failure.action()).doesNotContain("s3cr3t");
        });
        assertThat(output).doesNotContain("s3cr3t", "app:", "app@");
    }

    /**
     * The URL forms PgJDBC reads credentials from, by its own parser: Synapse finds credentials in every one of them.
     */
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://db/books?user=app", "jdbc:postgresql://db/books?password=s3cr3t",
            "jdbc:postgresql://db/books?sslpassword=s3cr3t", "jdbc:postgresql:books?password=s3cr3t",
            "jdbc:postgresql://h1:5432,h2:5432/books?password=s3cr3t&targetServerType=primary",
            "jdbc:postgresql://db/books?password=s3%63r3t"})
    void everyFormTheDriverReadsCredentialsFromIsFound(String url) {
        assertThat(credential(parsed(url))).as("PgJDBC reads a credential from %s", url).isNotNull();
        assertThat(JdbcUrls.hasCredentials(url)).isTrue();
    }

    /** URL forms without credentials, which PgJDBC accepts: they start. */
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://127.0.0.1:1/books",
            "jdbc:postgresql://127.0.0.1:1,127.0.0.1:2/books?targetServerType=primary",
            "jdbc:postgresql://127.0.0.1:1/books?socketFactory=javax.net.DefaultSocketFactory&socketFactoryArg=x",
            "jdbc:postgresql://[::1]:1/books?ApplicationName=books&sslmode=disable",
            "jdbc:postgresql://127.0.0.1:1/books?sslpasswordcallback=com.example.Callback&sslkey=/k.pk8"})
    void urlsWithoutCredentialsStart(String url) {
        assertThat(credential(parsed(url))).isNull();

        runner.withPropertyValues(PREFIX + ".url=" + url).run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * I1 (review probes C2, B, E1, C): a URL must name a host and a database, and no parameter may replace them or read
     * them from a {@code pg_service.conf} file. Otherwise PgJDBC takes the target from driver properties, its
     * class-path configuration or a service file, and connects somewhere the URL doesn't say.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"jdbc:postgresql:books|names no host",
            "jdbc:postgresql:///books|names no host", "jdbc:postgresql://?service=books|names no host",
            "jdbc:postgresql://127.0.0.1:1/|names no database", "jdbc:postgresql://127.0.0.1:1|names no database",
            "jdbc:postgresql://127.0.0.1:1/books?host=elsewhere-host&port=5432&dbname=other|has the parameter host, "
                    + "which would replace the host, port or database it names",
            "jdbc:postgresql://127.0.0.1:1/books?dbname=other|has the parameter dbname, which would replace the host, "
                    + "port or database it names",
            "jdbc:postgresql://127.0.0.1:1/books?service=elsewhere|has the parameter service, which reads where and "
                    + "as whom to connect from a pg_service.conf file"})
    void aUrlThatDoesNotSayWhereToConnectFails(String url, String problem, CapturedOutput output) {
        runner.withPropertyValues(PREFIX + ".url=" + url).run(context -> {
            SynapseConfigurationException failure = configurationFailure(context);
            assertThat(failure).hasMessage("The store 'books' has a url that " + problem + ".");
            assertThat(failure.action())
                    .isEqualTo("Set " + PREFIX + ".url in the form jdbc:postgresql://<host>[:<port>][,<host>[:<port>]"
                            + "...]/<database>[?<parameters>].");
        });
        assertThat(output).doesNotContain("elsewhere");
    }

    /**
     * I1 (review probes C2, C, G, G2, ROLE, E3 and the socket factory): driver properties that choose the host, port,
     * database, schema or role, or hold a secret, fail in any case, naming the key and never its value.
     */
    @ParameterizedTest
    @ValueSource(strings = {"PGHOST", "PGPORT", "PGDBNAME", "host", "port", "dbname", "sslpassword", "socketFactory",
            "socketFactoryArg", "options", "currentSchema", "service", "pghost", "PgDbName", "CURRENTSCHEMA",
            "sslPassword", "SocketFactory"})
    void driverPropertiesThatChooseTheTargetFail(String key, CapturedOutput output) {
        runner
                .withPropertyValues(URL_PROPERTY,
                        PREFIX + ".hikari.data-source-properties." + key + "=elsewhere-s3cr3t")
                .run(context -> assertThat(configurationFailure(context))
                        .hasMessage("The store 'books' sets " + PREFIX + ".hikari.data-source-properties." + key
                                + ", a driver property that would change where, as whom or in which schema the store "
                                + "connects, or that holds a secret."));
        assertThat(output).doesNotContain("elsewhere-s3cr3t");
    }

    /** The user and password keep data-core's message: they replace the store's own keys. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"user|username", "USER|username", "password|password",
            "Password|password"})
    void driverCredentialsReplaceTheStoresKeys(String key, String storeKey) {
        runner
                .withPropertyValues(URL_PROPERTY, PREFIX + ".hikari.data-source-properties." + key + "=s3cr3t")
                .run(context -> assertThat(configurationFailure(context))
                        .hasMessage("The store 'books' sets " + PREFIX + ".hikari.data-source-properties." + key
                                + ", which would replace its " + storeKey + "."));
    }

    /**
     * Driver properties that change how the store proves its user name, or how the driver behaves, are allowed: they
     * reach the pool.
     */
    @ParameterizedTest
    @ValueSource(strings = {"sslkey", "sslcert", "sslrootcert", "sslmode", "authenticationPluginClassName",
            "jaasLogin", "jaasApplicationName", "kerberosServerName", "gssUseDefaultCreds", "gsslib", "useSpnego",
            "sspiServiceClass", "ApplicationName", "tcpKeepAlive", "sslpasswordcallback"})
    void otherDriverPropertiesReachThePool(String key) {
        runner
                .withPropertyValues(URL_PROPERTY, PREFIX + ".hikari.data-source-properties." + key + "=x")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("booksDataSource", HikariDataSource.class).getDataSourceProperties())
                            .containsEntry(key, "x");
                });
    }

    /**
     * I1 (review probes D1, D2): PgJDBC reads {@code org/postgresql/driverconfig.properties} from the class path for
     * every connection, under the URL's and the pool's properties: a host, a password or a schema in it applies with
     * no Synapse key. Startup fails, naming where the file is, never what it says.
     */
    @Test
    void theDriversClassPathConfigurationFails(@TempDir Path directory, CapturedOutput output) throws IOException {
        Path file = directory.resolve("org/postgresql/driverconfig.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "PGHOST=elsewhere-host\npassword=s3cr3t\ncurrentSchema=other\n");
        try (URLClassLoader classLoader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},
                getClass().getClassLoader())) {
            runner.withClassLoader(classLoader).withPropertyValues(URL_PROPERTY).run(context -> {
                SynapseConfigurationException failure = configurationFailure(context);
                assertThat(failure)
                        .hasMessage("The class path has org/postgresql/driverconfig.properties ("
                                + classLoader.getResource("org/postgresql/driverconfig.properties")
                                + "), which the postgres driver reads behind the settings of the store "
                                + "'books'.");
                assertThat(failure.action()).isEqualTo("Remove it, and configure the store under " + PREFIX + ".");
            });
        }
        assertThat(output).doesNotContain("s3cr3t", "elsewhere-host");
    }

    /**
     * I1 (review probes A1, A2): an empty password reaches PgJDBC as {@code ""}, not as Boot's {@code null}, so the
     * driver never reads a password from {@code .pgpass}, a service file or its class-path configuration.
     */
    @Test
    void anEmptyPasswordReachesThePoolAsEmpty() {
        runner.withPropertyValues(URL_PROPERTY).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("booksDataSource", HikariDataSource.class).getPassword()).isEmpty();
        });
    }

    /**
     * Hibernate and JPA connection keys in {@code jpa.properties}, with PostgreSQL values, would connect the
     * EntityManagerFactory elsewhere than the store's pool: they fail, naming the key only.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"hibernate.connection.url|jdbc:postgresql://elsewhere/db|url",
            "jakarta.persistence.jdbc.url|jdbc:postgresql://elsewhere/db|url",
            "javax.persistence.jdbc.url|jdbc:postgresql://elsewhere/db|url",
            "hibernate.connection.username|elsewhere|username", "jakarta.persistence.jdbc.user|elsewhere|username",
            "hibernate.connection.password|s3cr3t|password", "jakarta.persistence.jdbc.password|s3cr3t|password",
            "hibernate.connection.driver_class|org.postgresql.Driver|driver-class-name",
            "hibernate.connection.datasource|java:comp/env/jdbc/elsewhere|url"})
    void jpaConnectionKeysFail(String key, String value, String storeKey, CapturedOutput output) {
        runner.withPropertyValues(URL_PROPERTY, PREFIX + ".jpa.properties." + key + "=" + value).run(context -> {
            assertThat(configurationFailure(context))
                    .hasMessage("The store 'books' sets " + PREFIX + ".jpa.properties."
                            + key + ", which would replace its " + storeKey + ".");
        });
        assertThat(output).doesNotContain("s3cr3t", "elsewhere");
    }

    /** The Hikari keys that would replace the store's connection settings, with PostgreSQL driver properties. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"hikari.jdbc-url|jdbc:postgresql://elsewhere/db|url",
            "hikari.username|elsewhere|username", "hikari.password|s3cr3t|password",
            "hikari.data-source-class-name|org.postgresql.ds.PGSimpleDataSource|url",
            "hikari.data-source-properties.password|s3cr3t|password",
            "hikari.data-source-properties.user|elsewhere|username",
            "hikari.data-source-properties.url|jdbc:postgresql://elsewhere/db|url"})
    void hikariConnectionKeysFail(String key, String value, String storeKey, CapturedOutput output) {
        runner.withPropertyValues(URL_PROPERTY, PREFIX + "." + key + "=" + value).run(context -> {
            assertThat(configurationFailure(context))
                    .hasMessage("The store 'books' sets " + PREFIX + "." + key + ", which would replace its " + storeKey
                            + ".");
        });
        assertThat(output).doesNotContain("s3cr3t", "elsewhere");
    }

    /** Review Focus 3, on PostgreSQL: a raw schema key fails like {@code ddl-auto: update}. */
    @ParameterizedTest
    @ValueSource(strings = {"hibernate.hbm2ddl.auto", "jakarta.persistence.schema-generation.database.action",
            "javax.persistence.schema-generation.database.action"})
    void rawSchemaKeysFail(String key) {
        runner.withPropertyValues(URL_PROPERTY, PREFIX + ".jpa.properties." + key + "=update").run(context -> {
            assertThat(configurationFailure(context))
                    .hasMessage("The store 'books' sets " + key + " in " + PREFIX + ".jpa.properties.");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "create", "create-drop"})
    void ddlAutoThatChangesTheSchemaFails(String action) {
        runner.withPropertyValues(URL_PROPERTY, PREFIX + ".jpa.hibernate.ddl-auto=" + action).run(context -> {
            assertThat(configurationFailure(context))
                    .hasMessage(
                            "The store 'books' sets ddl-auto to " + action + ", which changes the schema at startup.");
        });
    }

    private static SynapseConfigurationException configurationFailure(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        Throwable cause = NestedExceptionUtils
                .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure(), "the startup failure"));
        assertThat(cause).isInstanceOf(SynapseConfigurationException.class);
        return (SynapseConfigurationException) cause;
    }

    /** The properties PgJDBC's own parser reads from a URL, which it must accept. */
    private static Properties parsed(String url) {
        return Objects.requireNonNull(Driver.parseURL(url, new Properties()), "PgJDBC accepts " + url);
    }

    /** The credential PgJDBC read from a URL, if any. */
    private static @Nullable String credential(Properties parsed) {
        for (String key : new String[]{"user", "password", "sslpassword"}) {
            String value = parsed.getProperty(key);
            if (value != null) {
                return key;
            }
        }
        return null;
    }
}
