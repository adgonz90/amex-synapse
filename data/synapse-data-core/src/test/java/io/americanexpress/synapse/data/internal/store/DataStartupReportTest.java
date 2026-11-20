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
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.fixture.H2TestVendor;
import io.americanexpress.synapse.data.fixture.books.BooksStore;
import io.americanexpress.synapse.data.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.fixture.ownbooks.BooksStoreWithOwnDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DataStartupReportTest {

    private static final String REPORT = "Synapse data stores:";

    private static final String IGNORED = "but Synapse data stores don't use it. Configure each store under "
            + "synapse.data.stores.<name>.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class))
            .withUserConfiguration(H2TestVendor.class);

    private final ApplicationContextRunner booksAndLegacy = runner
            .withUserConfiguration(BooksStore.class, LegacyStore.class)
            .withPropertyValues(store("books"))
            .withPropertyValues(store("legacy"));

    @Test
    void logsTheConnectionBudget(CapturedOutput output) {
        booksAndLegacy
                .withPropertyValues("synapse.data.stores.books.hikari.maximum-pool-size=10",
                        "synapse.data.stores.legacy.hikari.maximum-pool-size=5")
                .run(started());
        assertThat(lines(output, REPORT))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" INFO ", "io.americanexpress.synapse.data ")
                        .endsWith("Synapse data stores: books (pool 10), legacy (pool 5); at most 15 connections "
                                + "per instance."));
    }

    @Test
    void poolSizeDefaultsToHikaris(CapturedOutput output) {
        runner.withUserConfiguration(BooksStore.class).withPropertyValues(store("books")).run(started());
        assertThat(lines(output, REPORT))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .endsWith("Synapse data stores: books (pool 10); at most 10 connections per instance."));
    }

    @Test
    void aStoreWithItsOwnDataSourceIsNotCounted(CapturedOutput output) {
        runner
                .withUserConfiguration(BooksStoreWithOwnDataSource.class, LegacyStore.class)
                .withPropertyValues(store("legacy"))
                .withPropertyValues("synapse.data.stores.legacy.hikari.maximum-pool-size=5")
                .run(started());
        assertThat(lines(output, REPORT))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .endsWith("Synapse data stores: books (own DataSource), legacy (pool 5); at most 5 "
                                + "connections per instance, not counting books."));
    }

    @Test
    void storesThatAllHaveTheirOwnDataSourceHaveNoBudget(CapturedOutput output) {
        runner.withUserConfiguration(BooksStoreWithOwnDataSource.class).run(started());
        assertThat(lines(output, REPORT))
                .singleElement()
                .satisfies(line -> assertThat(line).endsWith("Synapse data stores: books (own DataSource)."));
    }

    @Test
    void aWrappedPoolIsCounted(CapturedOutput output) {
        runner.withUserConfiguration(BooksStoreWithWrappedPool.class).run(started());
        assertThat(lines(output, REPORT))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .endsWith("Synapse data stores: books (pool 7); at most 7 connections per instance."));
    }

    @Test
    void warnsAboutSpringDatasourceKeys(CapturedOutput output) {
        booksAndLegacy
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:spring-secret",
                        "spring.datasource.username=spring-user")
                .run(started());
        assertThat(lines(output, "spring.datasource"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ", "io.americanexpress.synapse.data ")
                        .endsWith("spring.datasource.* is set (spring.datasource.url, spring.datasource.username), "
                                + IGNORED));
        assertThat(output.getOut()).doesNotContain("spring-secret", "spring-user");
    }

    @Test
    void warnsAboutSpringFlywayKeys(CapturedOutput output) {
        booksAndLegacy.withPropertyValues("spring.flyway.locations=classpath:db/books").run(started());
        assertThat(lines(output, "spring.flyway"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ")
                        .endsWith("spring.flyway.* is set (spring.flyway.locations), " + IGNORED));
        assertThat(output.getOut()).doesNotContain("spring.datasource", "classpath:db/books");
    }

    /** Minor 3: each store binds its own {@code jdbc.template.*}, so Boot's are ignored too. */
    @Test
    void warnsAboutSpringJdbcTemplateKeys(CapturedOutput output) {
        booksAndLegacy
                .withPropertyValues("spring.jdbc.template.query-timeout=7s", "spring.jdbc.template.max-rows=12")
                .run(started());
        assertThat(lines(output, "spring.jdbc.template"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ", "io.americanexpress.synapse.data ")
                        .endsWith("spring.jdbc.template.* is set (spring.jdbc.template.max-rows, "
                                + "spring.jdbc.template.query-timeout), " + IGNORED));
        assertThat(output.getOut()).doesNotContain("7s", "=12");
    }

    /** Minor 3: an access module adds the keys its stores ignore (the test stand-in for synapse-data-jdbc adds one). */
    @Test
    void anAccessModuleAddsTheKeysItsStoresIgnore(CapturedOutput output) {
        booksAndLegacy.withPropertyValues("spring.test.access.module=on").run(started());
        assertThat(lines(output, "spring.test.access"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ", "io.americanexpress.synapse.data ")
                        .endsWith("spring.test.access.* is set (spring.test.access.module), " + IGNORED));
    }

    /** R10: an access module names the Boot bean types its stores don't use; the report names each such bean. */
    @Test
    void aBeanOfATypeTheStoresIgnoreIsNamed(CapturedOutput output) {
        booksAndLegacy
                .withBean("unusedBean", TestJdbcStoreInfrastructure.IgnoredByTestStores.class,
                        () -> new TestJdbcStoreInfrastructure.IgnoredByTestStores() {
                        })
                .run(started());
        assertThat(lines(output, "unusedBean"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ", "io.americanexpress.synapse.data ")
                        .endsWith("The IgnoredByTestStores bean unusedBean ("
                                + TestJdbcStoreInfrastructure.IgnoredByTestStores.class.getName()
                                + ") is not used by any store: Synapse builds JDBC stores without it."));
    }

    /** Without stores, nothing is reported, so a bean of an ignored type isn't named either. */
    @Test
    void noStoreNoIgnoredBeanWarning(CapturedOutput output) {
        runner
                .withBean("unusedBean", TestJdbcStoreInfrastructure.IgnoredByTestStores.class,
                        () -> new TestJdbcStoreInfrastructure.IgnoredByTestStores() {
                        })
                .run(started());
        assertThat(output.getOut()).doesNotContain("unusedBean");
    }

    @Test
    void namesEachKeyOnceInItsCanonicalForm(CapturedOutput output) {
        booksAndLegacy
                .withPropertyValues("spring.datasource.hikari.maximumPoolSize=3", "spring.datasource.url=jdbc:h2:mem:x")
                .withInitializer(context -> context
                        .getEnvironment()
                        .getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
                                Map.of("SPRING_DATASOURCE_URL", "jdbc:h2:mem:y"))))
                .run(started());
        assertThat(lines(output, "spring.datasource"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains("spring.datasource.* is set (spring.datasource.hikari.maximumpoolsize, "
                                + "spring.datasource.url), "));
    }

    @Test
    void defaultPropertiesAreNotWarnedAbout(CapturedOutput output) {
        booksAndLegacy
                .withInitializer(context -> context
                        .getEnvironment()
                        .getPropertySources()
                        .addLast(new MapPropertySource(DefaultPropertiesPropertySource.NAME,
                                Map
                                        .of("spring.datasource.generate-unique-name", "false",
                                                "spring.flyway.enabled", "false"))))
                .run(started());
        assertThat(lines(output, REPORT)).hasSize(1);
        assertThat(output.getOut()).doesNotContain(" WARN ");
    }

    @Test
    void noStoresNoReport(CapturedOutput output) {
        runner
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:boot", "spring.flyway.enabled=false")
                .run(started());
        assertThat(output.getOut()).doesNotContain(REPORT, "spring.datasource", "spring.flyway");
    }

    @Test
    void anotherContextsEventIsIgnored(CapturedOutput output) {
        booksAndLegacy.withPropertyValues("spring.datasource.url=jdbc:h2:mem:boot").run(context -> {
            assertThat(context).hasNotFailed();
            try (GenericApplicationContext other = new GenericApplicationContext()) {
                other.refresh();
                context
                        .getBean(DataStartupReport.class)
                        .onApplicationEvent(new ApplicationStartedEvent(new SpringApplication(), new String[0],
                                other, Duration.ZERO));
            }
        });
        assertThat(output.getOut()).doesNotContain(REPORT, "spring.datasource");
    }

    @Test
    void aUserReportReplacesSynapses() {
        runner.withUserConfiguration(UserReport.class).run(context -> {
            assertThat(context).hasSingleBean(DataStartupReport.class);
            assertThat(context.getBean(DataStartupReport.class)).isSameAs(context.getBean("userReport"));
        });
    }

    @Test
    void theReportIsABean() {
        runner.run(context -> assertThat(context).hasSingleBean(DataStartupReport.class));
    }

    /** R9: with several stores, an unqualified Flyway callback applies to none of them, so the report names it. */
    @Test
    void anUnqualifiedCallbackWithTwoStoresWarns(CapturedOutput output) {
        booksAndLegacy.withBean("auditCallback", Callback.class, NoOpCallback::new).run(started());
        assertThat(lines(output, "auditCallback"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ", "io.americanexpress.synapse.data ")
                        .endsWith("The Flyway Callback bean auditCallback is not used by any store: with several "
                                + "stores, a Callback applies only to the store its @Qualifier names, such as "
                                + "@Qualifier(\"books\")."));
    }

    @Test
    void aJavaMigrationQualifiedWithNoStoreWarns(CapturedOutput output) {
        booksAndLegacy.withUserConfiguration(ArchiveMigration.class).run(started());
        assertThat(lines(output, "archiveMigration"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ")
                        .endsWith("The Flyway JavaMigration bean archiveMigration is not used by any store: its "
                                + "qualifier 'archive' names no store."));
    }

    /** A qualifier of a type other than {@code @Qualifier} names no store, so the report names the bean. */
    @Test
    void aCallbackWithAnotherQualifierTypeWarns(CapturedOutput output) {
        booksAndLegacy
                .withBean("archiveCallback", Callback.class, NoOpCallback::new,
                        definition -> ((AbstractBeanDefinition) definition)
                                .addQualifier(new AutowireCandidateQualifier(Archive.class)))
                .run(started());
        assertThat(lines(output, "archiveCallback"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ")
                        .endsWith("The Flyway Callback bean archiveCallback is not used by any store: its qualifier "
                                + "isn't a @Qualifier, so it names no store."));
    }

    /** A qualifier annotation that isn't {@code @Qualifier}. */
    @interface Archive {
    }

    @Test
    void flywayBeansThatAStoreUsesAreNotWarnedAbout(CapturedOutput output) {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(store("books"))
                .withBean("auditCallback", Callback.class, NoOpCallback::new)
                .run(started());
        booksAndLegacy.withUserConfiguration(BooksCallback.class).run(started());
        assertThat(output.getOut()).doesNotContain(" WARN ");
    }

    private static ContextConsumer<AssertableApplicationContext> started() {
        return context -> {
            assertThat(context).hasNotFailed();
            context
                    .publishEvent(new ApplicationStartedEvent(new SpringApplication(), new String[0],
                            context.getSourceApplicationContext(), Duration.ZERO));
        };
    }

    private static List<String> lines(CapturedOutput output, String text) {
        return output.getOut().lines().filter(line -> line.contains(text)).toList();
    }

    private static String[] store(String name) {
        return new String[]{"synapse.data.stores." + name + ".url=jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1",
                "synapse.data.stores." + name + ".username=sa", "synapse.data.stores." + name + ".password="};
    }

    @Configuration(proxyBeanMethods = false)
    @SynapseDataStore("books")
    static class BooksStoreWithWrappedPool {

        @Bean(destroyMethod = "")
        DataSource booksDataSource() {
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:h2:mem:wrapped;DB_CLOSE_DELAY=-1");
            pool.setMaximumPoolSize(7);
            return new DelegatingDataSource(pool);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserReport {

        @Bean
        DataStartupReport userReport(org.springframework.context.ApplicationContext context) {
            return new DataStartupReport(context);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ArchiveMigration {

        @Bean
        @Qualifier("archive")
        JavaMigration archiveMigration() {
            return new V9__Archive();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BooksCallback {

        @Bean
        @Qualifier("books")
        Callback booksCallback() {
            return new NoOpCallback();
        }
    }

    static class NoOpCallback implements Callback {

        @Override
        public boolean supports(Event event, Context context) {
            return false;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            // Never called: it supports no event.
        }

        @Override
        public String getCallbackName() {
            return "no-op";
        }
    }

    static class V9__Archive extends BaseJavaMigration {

        @Override
        public void migrate(org.flywaydb.core.api.migration.Context context) {
            // Never run: no store uses it.
        }
    }
}
