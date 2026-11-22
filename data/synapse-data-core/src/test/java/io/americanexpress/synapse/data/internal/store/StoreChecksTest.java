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
package io.americanexpress.synapse.data.internal.store;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.fixture.H2TestVendor;
import io.americanexpress.synapse.data.fixture.book.BookStore;
import io.americanexpress.synapse.data.fixture.books.BooksStore;
import io.americanexpress.synapse.data.fixture.books.legacy.NestedLegacyStore;
import io.americanexpress.synapse.data.fixture.invalid.InvalidNameStore;
import io.americanexpress.synapse.data.fixture.jpabooks.JpaBooksStore;
import io.americanexpress.synapse.data.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.fixture.legacyorders.LegacyOrdersStore;
import io.americanexpress.synapse.data.fixture.legacyordersalias.LegacyordersStore;
import io.americanexpress.synapse.data.fixture.morebooks.MoreBooksStore;
import io.americanexpress.synapse.data.fixture.samebooks.SamePackageBooksStore;
import io.americanexpress.synapse.data.fixture.samebooks.SamePackageLegacyStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checks the registrar, the store definitions post-processor and the store factory make.
 */
class StoreChecksTest {

    private static final String SECRET = "s3cr3t";

    private static final String OVERLAP_ACTION = "Give each store's @SynapseDataStore class, entities and repositories "
            + "their own package, outside every other store's package.";

    private static final String UNDECLARED_ACTION = "Add the data module that declares it, or remove the settings.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class))
            .withUserConfiguration(H2TestVendor.class);

    private final ApplicationContextRunner books = runner
            .withUserConfiguration(BooksStore.class)
            .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:mem:books;DB_CLOSE_DELAY=-1",
                    "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=");

    @Test
    void invalidNameFails() {
        runner
                .withUserConfiguration(InvalidNameStore.class)
                .run(failsWith("'Books' is not a valid store name (@SynapseDataStore on "
                        + InvalidNameStore.class.getName() + ").",
                        "Use lowercase letters, digits and dashes, starting with a letter, such as books or "
                                + "legacy-orders."));
    }

    @Test
    void aStoreDeclaredTwiceFails() {
        books
                .withUserConfiguration(MoreBooksStore.class)
                .run(failsWith("The store 'books' is declared twice: " + BooksStore.class.getName() + " and "
                        + MoreBooksStore.class.getName() + ".", "Declare each store once."));
    }

    @Test
    void namesThatBindTheSamePropertiesAreDeclaredTwice() {
        runner
                .withUserConfiguration(LegacyOrdersStore.class, LegacyordersStore.class)
                .run(failsWith("The store 'legacy-orders' is declared twice: " + LegacyOrdersStore.class.getName()
                        + " and " + LegacyordersStore.class.getName() + ".", "Declare each store once."));
    }

    @Test
    void missingUrlFails() {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=")
                .run(failsWith("The store 'books' has no url.",
                        "Set synapse.data.stores.books.url in the data module's config-<env>.yaml. Keys that say "
                                + "where to connect have no default."));
    }

    @Test
    void missingUsernameFails() {
        runner
                .withUserConfiguration(LegacyOrdersStore.class)
                .withPropertyValues("synapse.data.stores.legacy-orders.url=jdbc:h2:mem:lo;DB_CLOSE_DELAY=-1",
                        "synapse.data.stores.legacy-orders.password=x")
                .run(failsWith("The store 'legacy-orders' has no username.",
                        "Set synapse.data.stores.legacy-orders.username, from an environment variable such as "
                                + "SYNAPSE_DATA_STORES_LEGACYORDERS_USERNAME."));
    }

    @Test
    void missingPasswordFails() {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:mem:books;DB_CLOSE_DELAY=-1",
                        "synapse.data.stores.books.username=sa")
                .run(failsWith("The store 'books' has no password.",
                        "Set synapse.data.stores.books.password, from an environment variable such as "
                                + "SYNAPSE_DATA_STORES_BOOKS_PASSWORD."));
    }

    @Test
    void emptyPasswordIsAllowed() {
        books.run(context -> assertThat(context).hasNotFailed().hasBean("booksDataSource"));
    }

    @Test
    void unsupportedVendorFails() {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=jdbc:sqlserver://h;databaseName=x",
                        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=")
                .run(failsWith("No Synapse vendor module supports the store 'books' (jdbc:sqlserver).",
                        "Add the vendor module for this database, such as synapse-data-postgres."));
    }

    @ParameterizedTest
    @MethodSource("credentialUrls")
    void credentialsInTheUrlFail(String url) {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=" + url, "synapse.data.stores.books.username=sa",
                        "synapse.data.stores.books.password=")
                .run(context -> {
                    SynapseConfigurationException failure = failure(context);
                    assertThat(failure.description())
                            .isEqualTo("The store 'books' has credentials in synapse.data.stores.books.url, and "
                                    + "drivers and pools log the URL.");
                    assertThat(failure.action())
                            .isEqualTo("Remove them from the URL and set synapse.data.stores.books.username and "
                                    + "synapse.data.stores.books.password instead (from environment variables).");
                    assertThat(failure.description() + failure.action())
                            .doesNotContain(SECRET, "app", "scott", "db:", "db/", "db;", "@");
                });
    }

    static Stream<String> credentialUrls() {
        return Stream
                .concat(Stream
                        .of("jdbc:h2:tcp://app:" + SECRET + "@db/books", "jdbc:h2:tcp://app@db/books",
                                "jdbc:h2:mem:books;USER=app", "jdbc:h2:mem:books;Password=" + SECRET,
                                "jdbc:h2:mem:books?ssl=true&username=app", "jdbc:h2:mem:books?password=" + SECRET),
                        JdbcUrlsTest.withCredentials());
    }

    @Test
    void oracleThinUrlsWithoutCredentialsReachTheVendorCheck() {
        for (String url : List
                .of("jdbc:oracle:thin:@db:1521/books", "jdbc:oracle:thin:@//db:1521/books",
                        "jdbc:oracle:thin:/@alias")) {
            runner
                    .withUserConfiguration(BooksStore.class)
                    .withPropertyValues("synapse.data.stores.books.url=" + url,
                            "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=")
                    .run(failsWith("No Synapse vendor module supports the store 'books' (jdbc:oracle).",
                            "Add the vendor module for this database, such as synapse-data-postgres."));
        }
    }

    @Test
    void qualifiedConnectionDetailsAreNotCheckedForCredentials() {
        runner
                .withUserConfiguration(BooksStore.class, CredentialDetails.class)
                .run(context -> assertThat(context).hasNotFailed().hasBean("booksDataSource"));
    }

    @Test
    void aUrlWithoutASchemeNamesNone() {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=" + SECRET, "synapse.data.stores.books.username=sa",
                        "synapse.data.stores.books.password=")
                .run(failsWith("No Synapse vendor module supports the store 'books' (no scheme).",
                        "Add the vendor module for this database, such as synapse-data-postgres."));
    }

    /**
     * I2: a URL that isn't JDBC-shaped passes the credential check, but the vendor failure names only a safe scheme,
     * never the user information after it, in the message or in the startup log.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"jdbc:admin:" + SECRET + "@db|jdbc:admin",
            "admin:" + SECRET + "@db:5432/books|no scheme",
            "jdbc:pg@" + SECRET + ":db|jdbc:pg", "jdbc:pg " + SECRET + ":db|jdbc:pg"})
    @ExtendWith(OutputCaptureExtension.class)
    void aMalformedUrlNamesOnlyASafeScheme(String url, String scheme, CapturedOutput output) {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=" + url, "synapse.data.stores.books.username=sa",
                        "synapse.data.stores.books.password=")
                .run(failsWith("No Synapse vendor module supports the store 'books' (" + scheme + ").",
                        "Add the vendor module for this database, such as synapse-data-postgres."));
        assertThat(output.getAll()).contains("No Synapse vendor module").doesNotContain(SECRET, "@db");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void messagesAndReportsNeverContainTheUrl(CapturedOutput output) {
        String url = "jdbc:sqlserver://app:" + SECRET + "@db/books?password=" + SECRET;
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=" + url, "synapse.data.stores.books.username=sa",
                        "synapse.data.stores.books.password=" + SECRET)
                .run(context -> {
                    SynapseConfigurationException failure = failure(context);
                    assertThat(failure.description() + failure.action()).doesNotContain(SECRET, "db/books");
                });
        // A URL a vendor supports, with the secrets in it: the store fails before its pool opens, since drivers and
        // pools log the URL once they connect (migrations are on, so they would connect at startup).
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(
                        "synapse.data.stores.books.url=jdbc:h2:mem:app:" + SECRET + "@db/books?password=" + SECRET,
                        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=" + SECRET)
                .run(failsWith("The store 'books' has credentials in synapse.data.stores.books.url, and drivers and "
                        + "pools log the URL.",
                        "Remove them from the URL and set synapse.data.stores.books.username and "
                                + "synapse.data.stores.books.password instead (from environment variables)."));
        // The report runs only once the context starts and the store has migrated; the secrets are in other keys.
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:mem:report;DB_CLOSE_DELAY=-1",
                        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=" + SECRET,
                        "synapse.data.stores.books.hikari.maximum-pool-size=10", "spring.datasource.url=" + url,
                        "spring.datasource.password=" + SECRET, "spring.flyway.url=" + url)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("booksStoreLifecycle", StoreLifecycle.class).isRunning()).isTrue();
                    context
                            .publishEvent(new ApplicationStartedEvent(new SpringApplication(), new String[0],
                                    context.getSourceApplicationContext(), Duration.ZERO));
                });
        assertThat(output.getOut())
                .contains("Synapse data stores: books (pool 10); at most 10 connections per instance.")
                .contains("spring.datasource.* is set (spring.datasource.password, spring.datasource.url)")
                .contains("spring.flyway.* is set (spring.flyway.url)")
                .doesNotContain(SECRET, "db/books");
    }

    @Test
    void overlappingStorePackagesFail() {
        books
                .withUserConfiguration(NestedLegacyStore.class)
                .withPropertyValues(store("legacy"))
                .run(failsWith("The packages of the stores 'books' (" + BooksStore.class.getPackageName()
                        + ") and 'legacy' (" + NestedLegacyStore.class.getPackageName() + ") overlap.",
                        OVERLAP_ACTION));
    }

    @Test
    void theOuterPackageIsNamedFirst() {
        runner
                .withUserConfiguration(NestedLegacyStore.class, BooksStore.class)
                .withPropertyValues(store("books"))
                .withPropertyValues(store("legacy"))
                .run(failsWith("The packages of the stores 'books' (" + BooksStore.class.getPackageName()
                        + ") and 'legacy' (" + NestedLegacyStore.class.getPackageName() + ") overlap.",
                        OVERLAP_ACTION));
    }

    @Test
    void storesInOnePackageOverlap() {
        runner
                .withUserConfiguration(SamePackageBooksStore.class, SamePackageLegacyStore.class)
                .withPropertyValues(store("books"))
                .withPropertyValues(store("legacy"))
                .run(failsWith("The packages of the stores 'books' (" + SamePackageBooksStore.class.getPackageName()
                        + ") and 'legacy' (" + SamePackageLegacyStore.class.getPackageName() + ") overlap.",
                        OVERLAP_ACTION));
    }

    @Test
    void sameNamePrefixIsNotOverlap() {
        books
                .withUserConfiguration(BookStore.class)
                .withPropertyValues(store("book"))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasBean("bookDataSource")
                        .hasBean("booksDataSource"));
    }

    @Test
    void configuredButUndeclaredFails() {
        books
                .withPropertyValues("synapse.data.stores.bokks.url=jdbc:h2:mem:bokks")
                .run(failsWith("synapse.data.stores.bokks is configured, but no @SynapseDataStore(\"bokks\") "
                        + "declares the store.", UNDECLARED_ACTION));
    }

    @Test
    void anUndeclaredStoreFromAnEnvironmentVariableFails() {
        books
                .withInitializer(context -> context
                        .getEnvironment()
                        .getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
                                Map.of("SYNAPSE_DATA_STORES_ORDERS_PASSWORD", SECRET))))
                .run(failsWith("synapse.data.stores.orders is configured, but no @SynapseDataStore(\"orders\") "
                        + "declares the store.", UNDECLARED_ACTION));
    }

    @Test
    void storeKeysInEveryFormBelongToTheDeclaredStore() {
        runner
                .withUserConfiguration(LegacyOrdersStore.class)
                .withPropertyValues("synapse.data.stores.legacyOrders.url=jdbc:h2:mem:lo;DB_CLOSE_DELAY=-1",
                        "synapse.data.stores.legacy-orders.username=sa")
                .withInitializer(context -> context
                        .getEnvironment()
                        .getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
                                Map.of("SYNAPSE_DATA_STORES_LEGACYORDERS_PASSWORD", SECRET))))
                .run(context -> assertThat(context).hasNotFailed().hasBean("legacyOrdersDataSource"));
    }

    @Test
    void sliceSkipsTheUndeclaredCheck() {
        books
                .withPropertyValues("synapse.data.stores.bokks.url=jdbc:h2:mem:bokks")
                .withBean("synapseDataTestSlice", Object.class, Object::new)
                .run(context -> assertThat(context).hasNotFailed().hasBean("booksDataSource"));
    }

    @Test
    void theFirstUndeclaredStoreInSortedOrderIsNamed() {
        books
                .withPropertyValues("synapse.data.stores.zeta.url=jdbc:h2:mem:zeta",
                        "synapse.data.stores.middle.url=jdbc:h2:mem:middle",
                        "synapse.data.stores.alpha.url=jdbc:h2:mem:alpha")
                .run(failsWith("synapse.data.stores.alpha is configured, but no @SynapseDataStore(\"alpha\") "
                        + "declares the store.", UNDECLARED_ACTION));
    }

    @Test
    void aBracketKeyOfADeclaredStoreFails() {
        runner
                .withUserConfiguration(LegacyOrdersStore.class)
                .withPropertyValues("synapse.data.stores.legacy-orders.url=jdbc:h2:mem:lo;DB_CLOSE_DELAY=-1",
                        "synapse.data.stores.legacy-orders.password=",
                        "synapse.data.stores[legacy-orders].username=" + SECRET)
                .run(context -> {
                    SynapseConfigurationException failure = failure(context);
                    assertThat(failure.description())
                            .isEqualTo("The key synapse.data.stores[legacy-orders].username names its store in "
                                    + "brackets, but store keys use the dotted form, so it would be ignored.");
                    assertThat(failure.action()).isEqualTo("Write it as synapse.data.stores.legacy-orders.username.");
                    assertThat(failure.description() + failure.action()).doesNotContain(SECRET);
                });
    }

    @Test
    void aBracketKeyOfAnUndeclaredStoreFailsAsBracketed() {
        books
                .withPropertyValues("synapse.data.stores[bokks].url=jdbc:h2:mem:bokks")
                .run(failsWith("The key synapse.data.stores[bokks].url names its store in brackets, but store keys "
                        + "use the dotted form, so it would be ignored.",
                        "Write it as synapse.data.stores.bokks.url."));
    }

    @Test
    void theSliceStillRejectsBracketKeys() {
        books
                .withPropertyValues("synapse.data.stores[books].hikari.maximum-pool-size=3")
                .withBean("synapseDataTestSlice", Object.class, Object::new)
                .run(failsWith("The key synapse.data.stores[books].hikari.maximum-pool-size names its store in "
                        + "brackets, but store keys use the dotted form, so it would be ignored.",
                        "Write it as synapse.data.stores.books.hikari.maximum-pool-size."));
    }

    @Test
    void aBracketKeyWithoutChildrenFails() {
        books
                .withPropertyValues("synapse.data.stores[bokks]=x")
                .run(failsWith("The key synapse.data.stores[bokks] names its store in brackets, but store keys use "
                        + "the dotted form, so it would be ignored.", "Write it as synapse.data.stores.bokks."));
    }

    @Test
    void twoPrimariesFail() {
        books
                .withUserConfiguration(LegacyStore.class)
                .withPropertyValues(store("legacy"))
                .withPropertyValues("synapse.data.stores.books.primary=true", "synapse.data.stores.legacy.primary=true")
                .run(failsWith("More than one store sets primary: books, legacy.",
                        "Set synapse.data.stores.<name>.primary: true on one store only."));
    }

    /**
     * Under AOT the store definitions come from the build, and the post-processor runs again with the run-time
     * environment: a {@code primary} that differs from the registered one fails, whichever way it changed.
     */
    @ParameterizedTest
    @CsvSource({"false, true", "true, false"})
    void aPrimaryThatDiffersFromTheRegisteredOneFails(boolean registered, boolean configured) {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        RootBeanDefinition definition = new RootBeanDefinition(StoreDefinition.class);
        Object[] arguments = {"books", "books", "com.example.books", DataAccess.JPA, registered, "com.example.Books"};
        for (int i = 0; i < arguments.length; i++) {
            definition.getConstructorArgumentValues().addIndexedArgumentValue(i, arguments[i]);
        }
        registry.registerBeanDefinition("booksStoreDefinition", definition);
        StoreDefinitionsPostProcessor postProcessor = new StoreDefinitionsPostProcessor();
        postProcessor
                .setEnvironment(new MockEnvironment()
                        .withProperty("synapse.data.stores.books.primary", String.valueOf(configured)));

        assertThatThrownBy(() -> postProcessor.postProcessBeanDefinitionRegistry(registry))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                    assertThat(failure.description())
                            .isEqualTo("The store 'books' sets synapse.data.stores.books.primary differently from when "
                                    + "its beans were registered (in AOT mode, at build time).");
                    assertThat(failure.action())
                            .isEqualTo("Set synapse.data.stores.books.primary in config.yaml or application.yaml, the "
                                    + "same in every environment and not in a @PropertySource, and in AOT mode build "
                                    + "again.");
                });
    }

    @Test
    void jdbcAccessWithoutTheModuleFails() {
        // This module's tests always have the stand-in JDBC module, so the store names the access that is missing:
        // JPA. AUTO with neither module is tested through resolveAccess.
        runner
                .withUserConfiguration(JpaBooksStore.class)
                .withPropertyValues(store("books"))
                .run(failsWith("The store 'books' uses JPA, but synapse-data-jpa is not on the class path.",
                        "Add synapse-data-jpa."));
    }

    @ParameterizedTest
    @CsvSource({"jdbc-url, url", "username, username", "password, password", "data-source-class-name, url",
            "data-source-jndi, url", "schema, url", "catalog, url",
            "data-source-properties.user, username", "data-source-properties.username, username",
            "data-source-properties.password, password", "data-source-properties.url, url"})
    void hikariConnectionKeysAreRejected(String key, String storeKey) {
        books
                .withPropertyValues("synapse.data.stores.books.hikari." + key + "=" + SECRET)
                .run(context -> {
                    SynapseConfigurationException failure = failure(context);
                    assertThat(failure.description())
                            .isEqualTo("The store 'books' sets synapse.data.stores.books.hikari." + key
                                    + ", which would replace its " + storeKey + ".");
                    assertThat(failure.action())
                            .isEqualTo("Remove synapse.data.stores.books.hikari." + key
                                    + " and set synapse.data.stores.books." + storeKey + " instead.");
                    assertThat(failure.description() + failure.action()).doesNotContain(SECRET);
                });
    }

    /** SQL on each new connection could change the store's role ({@code SET ROLE}) or schema behind its settings. */
    @ParameterizedTest
    @ValueSource(strings = {"connection-init-sql", "connectionInitSql"})
    void hikariConnectionInitSqlIsRejected(String key) {
        books
                .withPropertyValues("synapse.data.stores.books.hikari." + key + "=SET ROLE " + SECRET)
                .run(context -> {
                    SynapseConfigurationException failure = failure(context);
                    assertThat(failure.description())
                            .isEqualTo("The store 'books' sets synapse.data.stores.books.hikari.connection-init-sql, "
                                    + "SQL that runs on each new connection and could change the role or schema the "
                                    + "store uses.");
                    assertThat(failure.action())
                            .isEqualTo("Remove synapse.data.stores.books.hikari.connection-init-sql. Name the schema "
                                    + "in synapse.data.stores.books.url.");
                    assertThat(failure.description() + failure.action()).doesNotContain(SECRET);
                });
    }

    /** A test query runs on every connection check, so a {@code SET ROLE} there would change the store's role too. */
    @ParameterizedTest
    @ValueSource(strings = {"connection-test-query", "connectionTestQuery"})
    void hikariConnectionTestQueryIsRejected(String key) {
        books
                .withPropertyValues("synapse.data.stores.books.hikari." + key + "=SET ROLE " + SECRET)
                .run(context -> {
                    SynapseConfigurationException failure = failure(context);
                    assertThat(failure.description())
                            .isEqualTo("The store 'books' sets synapse.data.stores.books.hikari.connection-test-query, "
                                    + "SQL that runs on each connection check and could change the role or schema the "
                                    + "store uses.");
                    assertThat(failure.action())
                            .isEqualTo("Remove synapse.data.stores.books.hikari.connection-test-query. Name the "
                                    + "schema in synapse.data.stores.books.url.");
                    assertThat(failure.description() + failure.action()).doesNotContain(SECRET);
                });
    }

    @Test
    void hikariConnectionKeysAreMatchedInAnyForm() {
        books
                .withPropertyValues("synapse.data.stores.books.hikari.jdbcUrl=" + SECRET)
                .run(context -> assertThat(failure(context).description())
                        .contains("synapse.data.stores.books.hikari.jdbc-url"));
    }

    @Test
    void driverPropertyKeysAreMatchedWhateverTheCase() {
        books
                .withPropertyValues("synapse.data.stores.books.hikari.data-source-properties[Password]=" + SECRET)
                .run(context -> {
                    SynapseConfigurationException failure = failure(context);
                    assertThat(failure.description())
                            .isEqualTo("The store 'books' sets "
                                    + "synapse.data.stores.books.hikari.data-source-properties.Password, which would "
                                    + "replace its password.");
                    assertThat(failure.description() + failure.action()).doesNotContain(SECRET);
                });
    }

    @Test
    void otherHikariSettingsAreAllowed() {
        books
                .withPropertyValues("synapse.data.stores.books.hikari.data-source-properties.cachePrepStmts=true")
                .run(context -> assertThat(context).hasNotFailed().hasBean("booksDataSource"));
    }

    @Test
    void autoAccessWithNeitherModuleFails() {
        assertThatThrownBy(() -> StoreRegistrar.resolveAccess("books", DataAccess.AUTO, List.of()))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                    assertThat(failure.description())
                            .isEqualTo("The store 'books' uses JPA or JDBC, but synapse-data-jpa or "
                                    + "synapse-data-jdbc is not on the class path.");
                    assertThat(failure.action()).isEqualTo("Add synapse-data-jpa or synapse-data-jdbc.");
                });
    }

    @Test
    void autoAccessWithBothModulesFails() {
        assertThatThrownBy(() -> StoreRegistrar
                .resolveAccess("books", DataAccess.AUTO, List
                        .of(infrastructure(DataAccess.JPA),
                                infrastructure(DataAccess.JDBC))))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                    assertThat(failure.description())
                            .isEqualTo("The store 'books' must name its access: both synapse-data-jpa and "
                                    + "synapse-data-jdbc are present.");
                    assertThat(failure.action())
                            .isEqualTo("Use @SynapseDataStore(value = \"books\", access = DataAccess.JPA) or "
                                    + "DataAccess.JDBC.");
                });
    }

    @Test
    void autoAccessWithOneModuleUsesIt() {
        StoreInfrastructure jpa = infrastructure(DataAccess.JPA);
        assertThat(StoreRegistrar.resolveAccess("books", DataAccess.AUTO, List.of(jpa))).isSameAs(jpa);
    }

    @Test
    void unavailableAccessModulesAreLeftOut() {
        StoreInfrastructure jdbc = infrastructure(DataAccess.JDBC);
        StoreInfrastructure unavailableJpa = new StoreInfrastructure() {

            @Override
            public DataAccess access() {
                return DataAccess.JPA;
            }

            @Override
            public boolean isAvailable(@Nullable ClassLoader classLoader) {
                return false;
            }

            @Override
            public void register(StoreDefinition store, BeanDefinitionRegistry registry, Environment environment,
                    ResourceLoader resourceLoader) {
            }
        };
        assertThat(jdbc.isAvailable(getClass().getClassLoader())).as("available by default").isTrue();
        assertThat(StoreRegistrar.available(List.of(unavailableJpa, jdbc), getClass().getClassLoader()))
                .containsExactly(jdbc);
    }

    @Test
    void namedAccessUsesItsModule() {
        StoreInfrastructure jpa = infrastructure(DataAccess.JPA);
        StoreInfrastructure jdbc = infrastructure(DataAccess.JDBC);
        assertThat(StoreRegistrar.resolveAccess("books", DataAccess.JDBC, List.of(jpa, jdbc))).isSameAs(jdbc);
    }

    @Test
    void namedAccessWithoutItsModuleFails() {
        assertThatThrownBy(() -> StoreRegistrar
                .resolveAccess("books", DataAccess.JPA, List.of(infrastructure(DataAccess.JDBC))))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                    assertThat(failure.description())
                            .isEqualTo("The store 'books' uses JPA, but synapse-data-jpa is not on the class path.");
                    assertThat(failure.action()).isEqualTo("Add synapse-data-jpa.");
                });
        assertThatThrownBy(() -> StoreRegistrar.resolveAccess("books", DataAccess.JDBC, List.of()))
                .isInstanceOfSatisfying(SynapseConfigurationException.class,
                        failure -> assertThat(failure.description())
                                .isEqualTo("The store 'books' uses JDBC, but synapse-data-jdbc is not on the class "
                                        + "path."));
    }

    @Test
    void connectionValuesShowOnlyTheScheme() {
        assertThat(new StoreConnectionValues("jdbc:postgresql://app:" + SECRET + "@db/books", "app", SECRET, null))
                .hasToString("StoreConnectionValues[jdbc:postgresql]");
    }

    private static String[] store(String name) {
        return new String[]{"synapse.data.stores." + name + ".url=jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1",
                "synapse.data.stores." + name + ".username=sa", "synapse.data.stores." + name + ".password="};
    }

    private static ContextConsumer<AssertableApplicationContext> failsWith(String description, String action) {
        return context -> {
            SynapseConfigurationException failure = failure(context);
            assertThat(failure.description()).isEqualTo(description);
            assertThat(failure.action()).isEqualTo(action);
        };
    }

    private static SynapseConfigurationException failure(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        Throwable startupFailure = context.getStartupFailure();
        assertThat(startupFailure).isNotNull();
        Throwable root = NestedExceptionUtils.getMostSpecificCause(Objects.requireNonNull(startupFailure));
        assertThat(root).isInstanceOf(SynapseConfigurationException.class);
        return (SynapseConfigurationException) root;
    }

    private static StoreInfrastructure infrastructure(DataAccess access) {
        return new StoreInfrastructure() {

            @Override
            public DataAccess access() {
                return access;
            }

            @Override
            public void register(StoreDefinition store, BeanDefinitionRegistry registry, Environment environment,
                    ResourceLoader resourceLoader) {
            }
        };
    }

    /** Connection details qualified for the books store whose URL names a user, as test tooling may produce. */
    @Configuration(proxyBeanMethods = false)
    static class CredentialDetails {

        @Bean
        @Qualifier("books")
        JdbcConnectionDetails booksConnectionDetails() {
            return new JdbcConnectionDetails() {

                @Override
                public String getUsername() {
                    return "sa";
                }

                @Override
                public String getPassword() {
                    return "";
                }

                @Override
                public String getJdbcUrl() {
                    return "jdbc:h2:mem:details-credentials;USER=sa;DB_CLOSE_DELAY=-1";
                }
            };
        }
    }
}
