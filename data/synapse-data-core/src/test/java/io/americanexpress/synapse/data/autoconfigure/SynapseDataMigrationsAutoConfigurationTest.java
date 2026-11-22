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
package io.americanexpress.synapse.data.autoconfigure;

import java.util.List;
import java.util.Objects;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.lifecycle.SynapseLifecyclePhases;
import io.americanexpress.synapse.data.fixture.H2TestVendor;
import io.americanexpress.synapse.data.fixture.books.BooksStore;
import io.americanexpress.synapse.data.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.fixture.ownflyway.BooksStoreWithOwnFlyway;
import io.americanexpress.synapse.data.internal.store.StoreLifecycle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration part of the data auto-configuration: a {@code <prefix>Flyway} per store while Flyway is on the class
 * path, and a {@code <prefix>StoreLifecycle} per store in any case.
 */
class SynapseDataMigrationsAutoConfigurationTest {

    private static final String FLYWAY_FACTORY = "synapseDataFlywayFactory";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class))
            .withUserConfiguration(H2TestVendor.class);

    @Test
    void eachStoreGetsAFlywayAndALifecycle() {
        runner
                .withUserConfiguration(BooksStore.class, LegacyStore.class)
                .withPropertyValues(store("books", "migrations-auto-books"))
                .withPropertyValues(store("legacy", "migrations-auto-legacy"))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean(FLYWAY_FACTORY);
                    assertThat(context.getBeansOfType(Flyway.class).keySet())
                            .containsExactlyInAnyOrder("booksFlyway", "legacyFlyway");
                    assertThat(context.getBeansOfType(StoreLifecycle.class).keySet())
                            .containsExactlyInAnyOrder("booksStoreLifecycle", "legacyStoreLifecycle");
                    for (String store : List.of("books", "legacy")) {
                        for (String suffix : List.of("Flyway", "StoreLifecycle")) {
                            BeanDefinition definition = context.getBeanFactory().getBeanDefinition(store + suffix);
                            assertThat(qualifier(definition)).as(store + suffix).isEqualTo(store);
                        }
                    }
                    Flyway books = context.getBean("booksFlyway", Flyway.class);
                    assertThat(books.getConfiguration().getDataSource()).isSameAs(context.getBean("booksDataSource"));
                    assertThat(books.getConfiguration().getLocations())
                            .extracting(Location::getDescriptor)
                            .containsExactly("classpath:db/migration/books");
                    StoreLifecycle lifecycle = context.getBean("booksStoreLifecycle", StoreLifecycle.class);
                    assertThat(lifecycle.store()).isEqualTo("books");
                    assertThat(lifecycle.getPhase()).isEqualTo(SynapseLifecyclePhases.DATA_STORES);
                    assertThat(lifecycle.isRunning()).isTrue();
                    assertThat(tables(context.getBean("booksJdbcClient", JdbcClient.class))).contains("book");
                    assertThat(tables(context.getBean("legacyJdbcClient", JdbcClient.class))).contains("legacy_item");
                });
    }

    @Test
    void bindsTheStoresFlywaySettings() {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(store("books", "migrations-auto-bind"))
                .withPropertyValues("synapse.data.stores.books.migrations.locations=classpath:db/migration/legacy",
                        "synapse.data.stores.books.migrations.flyway.table=books_history",
                        "synapse.data.stores.books.migrations.flyway.baseline-on-migrate=true")
                .run(context -> {
                    Flyway flyway = context.getBean("booksFlyway", Flyway.class);
                    assertThat(flyway.getConfiguration().getTable()).isEqualTo("books_history");
                    assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isTrue();
                    assertThat(tables(context.getBean("booksJdbcClient", JdbcClient.class)))
                            .contains("legacy_item", "books_history")
                            .doesNotContain("book");
                });
    }

    @Test
    void disabledMigrationsKeepTheFlywayBeanButNeverConnect() {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:tcp://127.0.0.1:1/none",
                        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
                        "synapse.data.stores.books.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("booksFlyway");
                    assertThat(context.getBean("booksStoreLifecycle", StoreLifecycle.class).isRunning()).isTrue();
                    assertThat(context.getBean("booksDataSource", HikariDataSource.class).getHikariPoolMXBean())
                            .isNull();
                });
    }

    @Test
    void aFlywayInTheStoreClassReplacesSynapses() {
        runner
                .withUserConfiguration(BooksStoreWithOwnFlyway.class)
                .withPropertyValues(store("books", "migrations-auto-own"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(Flyway.class).keySet()).containsExactly("booksFlyway");
                    assertThat(context.getBean("booksFlyway", Flyway.class).getConfiguration().getLocations())
                            .extracting(Location::getDescriptor)
                            .containsExactly(BooksStoreWithOwnFlyway.LOCATION);
                    assertThat(tables(context.getBean("booksJdbcClient", JdbcClient.class)))
                            .contains("legacy_item")
                            .doesNotContain("book");
                });
    }

    @Test
    void aFlywayConnectionOverrideFailsStartup() {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(store("books", "migrations-auto-override"))
                .withPropertyValues("synapse.data.stores.books.migrations.flyway.url=jdbc:h2:mem:elsewhere")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
                    assertThat(root).isInstanceOf(SynapseConfigurationException.class);
                    SynapseConfigurationException failure = (SynapseConfigurationException) root;
                    assertThat(failure.description())
                            .isEqualTo("The store 'books' sets synapse.data.stores.books.migrations.flyway.url, which "
                                    + "would replace its url.");
                    assertThat(failure.description() + failure.action()).doesNotContain("elsewhere");
                });
    }

    @Test
    void absentWithoutFlyway() {
        runner
                .withClassLoader(new FilteredClassLoader("org.flywaydb"))
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(store("books", "migrations-auto-noflyway"))
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(FLYWAY_FACTORY).doesNotHaveBean("booksFlyway");
                    assertThat(context.getBean("booksStoreLifecycle", StoreLifecycle.class).isRunning()).isTrue();
                    assertThat(tables(context.getBean("booksJdbcClient", JdbcClient.class))).doesNotContain("book");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"synapse.data.stores.books.migrations.locations=classpath:db/migration/books",
            "synapse.data.stores.books.migrations.flyway.baseline-on-migrate=true"})
    void migrationSettingsWithoutFlywayFailStartup(String setting) {
        runner
                .withClassLoader(new FilteredClassLoader("org.flywaydb"))
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(store("books", "migrations-auto-notool"))
                .withPropertyValues(setting)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
                    assertThat(root).isInstanceOf(SynapseConfigurationException.class);
                    SynapseConfigurationException failure = (SynapseConfigurationException) root;
                    assertThat(failure.description())
                            .isEqualTo(
                                    "The store 'books' configures migrations, but no migration tool is on the class path.");
                    assertThat(failure.action())
                            .isEqualTo("Add org.flywaydb:flyway-core, or set "
                                    + "synapse.data.stores.books.migrations.enabled: false.");
                });
    }

    @Test
    void disabledMigrationSettingsWithoutFlywayStart() {
        runner
                .withClassLoader(new FilteredClassLoader("org.flywaydb"))
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(store("books", "migrations-auto-notool-off"))
                .withPropertyValues("synapse.data.stores.books.migrations.enabled=false",
                        "synapse.data.stores.books.migrations.locations=classpath:db/migration/books")
                .run(context -> assertThat(context).hasNotFailed().hasBean("booksStoreLifecycle"));
    }

    @Test
    void bootsFlywayStillMigratesAServiceWithoutStores() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class,
                                JdbcTemplateAutoConfiguration.class, JdbcClientAutoConfiguration.class,
                                SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:migrations-boot;DB_CLOSE_DELAY=-1",
                        "spring.flyway.locations=classpath:db/migration/other")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("flyway").hasBean("flywayInitializer");
                    assertThat(context.getBeansOfType(StoreLifecycle.class)).isEmpty();
                    assertThat(tables(context.getBean(JdbcClient.class))).contains("other");
                });
    }

    private static String[] store(String name, String database) {
        return new String[]{"synapse.data.stores." + name + ".url=jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1",
                "synapse.data.stores." + name + ".username=sa", "synapse.data.stores." + name + ".password="};
    }

    private static List<String> tables(JdbcClient client) {
        return client
                .sql("SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")
                .query((row, rowNumber) -> row.getString(1))
                .list();
    }

    private static @Nullable Object qualifier(BeanDefinition definition) {
        AutowireCandidateQualifier qualifier = ((AbstractBeanDefinition) definition)
                .getQualifier(Qualifier.class.getName());
        return qualifier == null ? null : qualifier.getAttribute(AutowireCandidateQualifier.VALUE_KEY);
    }
}
