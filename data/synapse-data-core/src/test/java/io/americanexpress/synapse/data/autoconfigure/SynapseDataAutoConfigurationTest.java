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

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.SQLExceptionTranslator;

import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.EntityIdGenerator;
import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.fixture.H2TestVendor;
import io.americanexpress.synapse.data.fixture.books.BooksStore;
import io.americanexpress.synapse.data.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.fixture.legacyorders.LegacyOrdersStore;
import io.americanexpress.synapse.data.fixture.ownbooks.BooksStoreWithOwnDataSource;
import io.americanexpress.synapse.data.fixture.ownjdbc.BooksStoreWithOwnJdbcOperations;
import io.americanexpress.synapse.data.internal.audit.AuditStamps;
import io.americanexpress.synapse.data.internal.error.DataExceptionTranslator;
import io.americanexpress.synapse.data.internal.id.UuidV7Generator;
import io.americanexpress.synapse.data.internal.store.DataStartupReport;
import io.americanexpress.synapse.data.internal.store.StoreDefinition;
import io.americanexpress.synapse.data.internal.store.StoreFactory;
import io.americanexpress.synapse.data.vendor.DataStoreVendor;
import io.americanexpress.synapse.test.operation.RequestContexts;
import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynapseDataAutoConfigurationTest {

    private static final String FACTORY = "synapseDataStoreFactory";

    private static final String TRANSLATOR = "synapseDataExceptionTranslator";

    private static final String ID_GENERATOR = "synapseEntityIdGenerator";

    private static final String AUDITOR = "synapseAuditorAware";

    private static final String STAMPS = "synapseAuditStamps";

    private static final String[] BOOKS_BEANS = {"booksDataSource", "booksJdbcTemplate",
            "booksNamedParameterJdbcTemplate", "booksJdbcClient"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            DataSourceAutoConfiguration.class,
                            JdbcTemplateAutoConfiguration.class, JdbcClientAutoConfiguration.class,
                            DataSourceTransactionManagerAutoConfiguration.class,
                            DataSourceInitializationAutoConfiguration.class, TransactionAutoConfiguration.class))
            .withUserConfiguration(H2TestVendor.class);

    private final ApplicationContextRunner books = runner
            .withUserConfiguration(BooksStore.class)
            .withPropertyValues(store("books"));

    private final ApplicationContextRunner booksAndLegacy = books
            .withUserConfiguration(LegacyStore.class)
            .withPropertyValues(store("legacy"));

    @Test
    void aSingleStoreGetsItsBeans() {
        books.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("booksDataSource"))
                    .isInstanceOfSatisfying(HikariDataSource.class,
                            dataSource -> assertThat(dataSource.getPoolName()).isEqualTo("books"));
            assertThat(context.getBean("booksJdbcTemplate")).isInstanceOf(JdbcTemplate.class);
            assertThat(context.getBean("booksNamedParameterJdbcTemplate"))
                    .isInstanceOf(NamedParameterJdbcTemplate.class);
            assertThat(context.getBean("booksJdbcClient")).isInstanceOf(JdbcClient.class);
            for (String name : BOOKS_BEANS) {
                BeanDefinition definition = context.getBeanFactory().getBeanDefinition(name);
                assertThat(definition.isPrimary()).as(name).isTrue();
                assertThat(qualifier(definition)).as(name).isEqualTo("books");
            }
            assertThat(context.getBean("booksJdbcTemplate", JdbcTemplate.class).getDataSource())
                    .isSameAs(context.getBean("booksDataSource"));
            assertThat(context
                    .getBean("booksJdbcClient", JdbcClient.class)
                    .sql("SELECT 1")
                    .query(Integer.class)
                    .single()).isEqualTo(1);
        });
    }

    @Test
    void bootsSingleDataSourceBacksOff() {
        books.run(context -> {
            assertThat(context).doesNotHaveBean("dataSource");
            assertThat(context.getBeansOfType(DataSource.class).keySet()).containsExactly("booksDataSource");
            assertThat(context).doesNotHaveBean("jdbcTemplate");
            assertThat(context).doesNotHaveBean("namedParameterJdbcTemplate");
            assertThat(context).doesNotHaveBean("jdbcClient");
        });
    }

    @Test
    void twoStoresWithoutPrimary() {
        booksAndLegacy.withUserConfiguration(LegacyConsumer.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanFactory().getBeanDefinition("booksDataSource").isPrimary()).isFalse();
            assertThat(context.getBeanFactory().getBeanDefinition("legacyDataSource").isPrimary()).isFalse();
            assertThatThrownBy(() -> context.getBean(DataSource.class))
                    .isInstanceOf(NoUniqueBeanDefinitionException.class);
            assertThat(context.getBean(LegacyConsumer.class).dataSource)
                    .isSameAs(context.getBean("legacyDataSource"));
        });
    }

    @Test
    void primaryStoreIsPrimary() {
        booksAndLegacy.withPropertyValues("synapse.data.stores.legacy.primary=true").run(context -> {
            assertThat(context.getBean(DataSource.class)).isSameAs(context.getBean("legacyDataSource"));
            assertThat(context.getBean(JdbcClient.class)).isSameAs(context.getBean("legacyJdbcClient"));
            assertThat(context.getBeanFactory().getBeanDefinition("booksDataSource").isPrimary()).isFalse();
        });
    }

    @Test
    void bindsHikariSettings() {
        books
                .withPropertyValues("synapse.data.stores.books.hikari.maximum-pool-size=7",
                        "synapse.data.stores.books.hikari.connection-timeout=2000")
                .run(context -> {
                    HikariDataSource dataSource = context.getBean("booksDataSource", HikariDataSource.class);
                    assertThat(dataSource.getMaximumPoolSize()).isEqualTo(7);
                    assertThat(dataSource.getConnectionTimeout()).isEqualTo(2000);
                });
    }

    @Test
    void hikariPoolNameCanBeOverridden() {
        books
                .withPropertyValues("synapse.data.stores.books.hikari.pool-name=books-main")
                .run(context -> assertThat(context.getBean("booksDataSource", HikariDataSource.class).getPoolName())
                        .isEqualTo("books-main"));
    }

    @Test
    void bindsJdbcTemplateSettings() {
        books
                .withPropertyValues("synapse.data.stores.books.jdbc.template.fetch-size=50",
                        "synapse.data.stores.books.jdbc.template.max-rows=10",
                        "synapse.data.stores.books.jdbc.template.query-timeout=3s",
                        "synapse.data.stores.books.jdbc.template.ignore-warnings=false",
                        "synapse.data.stores.books.jdbc.template.skip-results-processing=true",
                        "synapse.data.stores.books.jdbc.template.skip-undeclared-results=true",
                        "synapse.data.stores.books.jdbc.template.results-map-case-insensitive=true")
                .run(context -> {
                    JdbcTemplate template = context.getBean("booksJdbcTemplate", JdbcTemplate.class);
                    assertThat(template.getFetchSize()).isEqualTo(50);
                    assertThat(template.getMaxRows()).isEqualTo(10);
                    assertThat(template.getQueryTimeout()).isEqualTo(3);
                    assertThat(template.isIgnoreWarnings()).isFalse();
                    assertThat(template.isSkipResultsProcessing()).isTrue();
                    assertThat(template.isSkipUndeclaredResults()).isTrue();
                    assertThat(template.isResultsMapCaseInsensitive()).isTrue();
                });
    }

    @Test
    void jdbcTemplateDefaultsAreBoots() {
        books.run(context -> {
            JdbcTemplate template = context.getBean("booksJdbcTemplate", JdbcTemplate.class);
            assertThat(template.getFetchSize()).isEqualTo(-1);
            assertThat(template.getMaxRows()).isEqualTo(-1);
            assertThat(template.getQueryTimeout()).isEqualTo(-1);
            assertThat(template.isIgnoreWarnings()).isTrue();
        });
    }

    @Test
    void aUniqueExceptionTranslatorIsUsed() {
        books.withUserConfiguration(TranslatorConfiguration.class).run(context -> {
            JdbcTemplate template = context.getBean("booksJdbcTemplate", JdbcTemplate.class);
            assertThat(template.getExceptionTranslator()).isSameAs(TranslatorConfiguration.TRANSLATOR);
        });
    }

    @Test
    void qualifiedConnectionDetailsWin() {
        runner.withUserConfiguration(BooksStore.class, QualifiedDetails.class).run(context -> {
            HikariDataSource dataSource = context.getBean("booksDataSource", HikariDataSource.class);
            assertThat(dataSource.getJdbcUrl()).isEqualTo(Details.URL);
            assertThat(dataSource.getUsername()).isEqualTo("details-user");
            assertThat(dataSource.getDriverClassName()).isEqualTo("org.h2.Driver");
        });
    }

    @Test
    void qualifiedConnectionDetailsThatAreNotDefaultCandidatesWin() {
        runner.withUserConfiguration(BooksStore.class, NonDefaultCandidateDetails.class).run(context -> {
            HikariDataSource dataSource = context.getBean("booksDataSource", HikariDataSource.class);
            assertThat(dataSource.getJdbcUrl()).isEqualTo(Details.URL);
            assertThat(dataSource.getUsername()).isEqualTo("details-user");
        });
    }

    /**
     * Qualified details may have no password ({@code null}). For a database that isn't embedded, Boot passes
     * {@code null} on, and a driver then looks for a password of its own (PostgreSQL's {@code .pgpass}), so the pool
     * gets {@code ""}, as for an empty store password.
     */
    @Test
    void qualifiedConnectionDetailsWithoutAPasswordReachThePoolAsEmpty() {
        runner
                .withUserConfiguration(BooksStore.class, PasswordlessDetails.class)
                .withPropertyValues("synapse.data.stores.books.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HikariDataSource dataSource = context.getBean("booksDataSource", HikariDataSource.class);
                    assertThat(dataSource.getJdbcUrl()).isEqualTo(PasswordlessDetails.URL);
                    assertThat(dataSource.getPassword()).isEmpty();
                });
    }

    @Test
    void anotherStoresConnectionDetailsAreIgnored() {
        books.withUserConfiguration(LegacyDetails.class).run(context -> {
            HikariDataSource dataSource = context.getBean("booksDataSource", HikariDataSource.class);
            assertThat(dataSource.getJdbcUrl()).isEqualTo(url("books"));
            assertThat(dataSource.getUsername()).isEqualTo("sa");
        });
    }

    @Test
    void aBeanInTheStoreClassReplacesSynapses() {
        runner.withUserConfiguration(BooksStoreWithOwnDataSource.class).run(context -> {
            assertThat(context).hasNotFailed();
            DataSource own = context.getBean("booksDataSource", DataSource.class);
            assertThat(own).isNotInstanceOf(HikariDataSource.class);
            assertThat(context.getBeansOfType(DataSource.class).keySet()).containsExactly("booksDataSource");
            assertThat(context.getBean("booksJdbcTemplate", JdbcTemplate.class).getDataSource()).isSameAs(own);
            assertThat(context
                    .getBean("booksJdbcClient", JdbcClient.class)
                    .sql("SELECT 2")
                    .query(Integer.class)
                    .single()).isEqualTo(2);
        });
    }

    /** I3: a bean that replaces one of Synapse's gets the store's qualifier and primary flag, as Synapse's would. */
    @Test
    void aReplacingBeanGetsTheStoresQualifierAndPrimaryFlag() {
        runner.withUserConfiguration(BooksStoreWithOwnDataSource.class).run(context -> {
            assertThat(context).hasNotFailed();
            BeanDefinition definition = context.getBeanFactory().getBeanDefinition("booksDataSource");
            assertThat(qualifier(definition)).isEqualTo("books");
            assertThat(definition.isPrimary()).isTrue();
            assertThat(BeanFactoryAnnotationUtils
                    .qualifiedBeanOfType(context.getBeanFactory(), DataSource.class, "books"))
                    .isSameAs(context.getBean("booksDataSource"));
        });
    }

    /** I3: a replacing bean that names its own qualifier keeps it; it is still primary as the store's bean. */
    @Test
    void aReplacingBeanKeepsItsOwnQualifier() {
        runner.withUserConfiguration(BooksStoreWithOwnQualifiedDataSource.class).run(context -> {
            assertThat(context).hasNotFailed();
            BeanDefinition definition = context.getBeanFactory().getBeanDefinition("booksDataSource");
            assertThat(qualifier(definition)).isNull();
            assertThat(definition.isPrimary()).isTrue();
            assertThat(BeanFactoryAnnotationUtils
                    .qualifiedBeanOfType(context.getBeanFactory(), DataSource.class, "own"))
                    .isSameAs(context.getBean("booksDataSource"));
        });
    }

    @Test
    void noStoreNoBeans() {
        runner.run(context -> {
            assertThat(context).hasBean(FACTORY);
            assertThat(context.getBeansOfType(DataSource.class).keySet()).containsExactly("dataSource");
            assertThat(context.getBeansOfType(StoreDefinition.class)).isEmpty();
        });
    }

    @Test
    void aUserStoreFactoryReplacesSynapses() {
        books.withUserConfiguration(UserStoreFactory.class).run(context -> {
            assertThat(context).hasSingleBean(StoreFactory.class);
            assertThat(context.getBean(FACTORY)).isSameAs(UserStoreFactory.created);
            assertThat(context).hasBean("booksDataSource");
        });
    }

    @Test
    void absentWithoutHikari() {
        runner
                .withClassLoader(new FilteredClassLoader(HikariDataSource.class))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(FACTORY)
                        .doesNotHaveBean(SynapseDataProperties.class)
                        .doesNotHaveBean(TRANSLATOR)
                        .doesNotHaveBean(ID_GENERATOR)
                        .doesNotHaveBean(AUDITOR)
                        .doesNotHaveBean(STAMPS)
                        .doesNotHaveBean(DataStartupReport.class));
    }

    @Test
    void registersAUuidV7GeneratorOnTheClock() {
        runner.withUserConfiguration(FixedClock.class).run(context -> {
            assertThat(context.getBean(ID_GENERATOR)).isInstanceOf(UuidV7Generator.class);
            UUID id = context.getBean(EntityIdGenerator.class).nextId();
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(FixedClock.CLOCK.millis());
        });
    }

    @Test
    void aUserEntityIdGeneratorReplacesSynapses() {
        runner.withUserConfiguration(UserEntityIdGenerator.class).run(context -> {
            assertThat(context).doesNotHaveBean(ID_GENERATOR);
            assertThat(context.getBean(EntityIdGenerator.class)).isSameAs(UserEntityIdGenerator.IDS);
        });
    }

    @Test
    void theAuditorIsTheSystemUserWithoutAPrincipal() {
        runner.run(context -> assertThat(auditor(context).getCurrentAuditor()).contains("system"));
    }

    @Test
    void theAuditorIsTheRequestContextsPrincipal() {
        runner.run(context -> {
            AuditorAware<String> auditor = auditor(context);
            assertThat(RequestContexts.with().principal(() -> "ana").call(auditor::getCurrentAuditor)).contains("ana");
        });
    }

    @Test
    void theAuditorIsTheSystemUserInAContextWithoutAPrincipal() {
        runner.run(context -> {
            AuditorAware<String> auditor = auditor(context);
            assertThat(RequestContexts.with().call(auditor::getCurrentAuditor)).contains("system");
        });
    }

    @Test
    void theAuditorsSystemUserIsBound() {
        runner
                .withPropertyValues("synapse.data.auditing.system-user=batch")
                .run(context -> assertThat(auditor(context).getCurrentAuditor()).contains("batch"));
    }

    @Test
    void synapsesAuditorIsNotADefaultCandidate() {
        runner.run(context -> {
            assertThat(((AbstractBeanDefinition) context.getBeanFactory().getBeanDefinition(AUDITOR))
                    .isDefaultCandidate()).isFalse();
        });
    }

    @Test
    void aUserStringAuditorIsTheOneTheStampsUse() {
        runner.withUserConfiguration(UserAuditorAware.class).run(context -> {
            assertThat(context).hasBean(AUDITOR);
            assertThat(context.getBean(AuditorAware.class)).isSameAs(UserAuditorAware.AUDITOR);
            assertThat(context.getBean(STAMPS, AuditStamps.class).user()).isEqualTo("auditor");
        });
    }

    @Test
    void anAuditorOfAnotherTypeLeavesSynapsesInUse() {
        runner.withUserConfiguration(UserIdAuditorAware.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AuditorAware.class)).isSameAs(UserIdAuditorAware.AUDITOR);
            assertThat(context.getBean(STAMPS, AuditStamps.class).user()).isEqualTo("system");
        });
    }

    @Test
    void aUserBeanNamedLikeSynapsesAuditorReplacesIt() {
        runner.withUserConfiguration(NamedLikeSynapsesAuditor.class).run(context -> {
            assertThat(context.getBean(AUDITOR)).isSameAs(NamedLikeSynapsesAuditor.AUDITOR);
            assertThat(context.getBean(STAMPS, AuditStamps.class).user()).isEqualTo("named");
        });
    }

    @Test
    void registersTheAuditStampsOnTheClock() {
        runner.withUserConfiguration(FixedClock.class).run(context -> {
            AuditStamps stamps = context.getBean(STAMPS, AuditStamps.class);
            FixedClock.CLOCK.set(Instant.parse("2026-01-01T00:00:00.123456789Z"));
            assertThat(stamps.now()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
            assertThat(stamps.user()).isEqualTo("system");
        });
    }

    @Test
    void userAuditStampsReplaceSynapses() {
        runner.withUserConfiguration(UserAuditStamps.class).run(context -> {
            assertThat(context).hasSingleBean(AuditStamps.class);
            assertThat(context.getBean(AuditStamps.class)).isSameAs(UserAuditStamps.STAMPS);
            assertThat(context).doesNotHaveBean(STAMPS);
        });
    }

    @Test
    void registersTheDataExceptionTranslator() {
        runner.run(context -> assertThat(context.getBean(TRANSLATOR)).isInstanceOf(DataExceptionTranslator.class));
    }

    @Test
    void aUserBeanNamedLikeTheTranslatorReplacesSynapses() {
        runner.withUserConfiguration(UserDataExceptionTranslator.class).run(context -> {
            assertThat(context.getBean(TRANSLATOR)).isSameAs(UserDataExceptionTranslator.TRANSLATOR);
            assertThat(context).doesNotHaveBean(DataExceptionTranslator.class);
        });
    }

    @Test
    void anotherExceptionTranslatorDoesNotReplaceSynapses() {
        runner.withUserConfiguration(OtherExceptionTranslator.class).run(context -> {
            assertThat(context.getBean(TRANSLATOR)).isInstanceOf(DataExceptionTranslator.class);
            assertThat(context.getBeansOfType(ExceptionTranslator.class).keySet())
                    .containsExactlyInAnyOrder(TRANSLATOR, "otherExceptionTranslator");
        });
    }

    @Test
    void dashedStoreNamesWork() {
        runner
                .withUserConfiguration(LegacyOrdersStore.class, LegacyOrdersConsumer.class)
                .withPropertyValues("synapse.data.stores.legacy-orders.url=" + url("legacy-orders"),
                        "synapse.data.stores.legacy-orders.username=sa")
                .withInitializer(context -> context
                        .getEnvironment()
                        .getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
                                Map.of("SYNAPSE_DATA_STORES_LEGACYORDERS_PASSWORD", "from-the-environment"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HikariDataSource dataSource = context.getBean("legacyOrdersDataSource", HikariDataSource.class);
                    assertThat(dataSource.getPoolName()).isEqualTo("legacy-orders");
                    assertThat(dataSource.getPassword()).isEqualTo("from-the-environment");
                    assertThat(context)
                            .hasBean("legacyOrdersJdbcTemplate")
                            .hasBean("legacyOrdersNamedParameterJdbcTemplate")
                            .hasBean("legacyOrdersJdbcClient")
                            .hasBean("legacyOrdersStoreDefinition");
                    assertThat(qualifier(context.getBeanFactory().getBeanDefinition("legacyOrdersDataSource")))
                            .isEqualTo("legacy-orders");
                    assertThat(context.getBean(LegacyOrdersConsumer.class).dataSource).isSameAs(dataSource);
                });
    }

    @Test
    void dataSourceCreationOpensNoConnection() {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:tcp://127.0.0.1:1/none",
                        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
                        "synapse.data.stores.books.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HikariDataSource dataSource = context.getBean("booksDataSource", HikariDataSource.class);
                    assertThat(dataSource.getHikariPoolMXBean()).isNull();
                    assertThat(dataSource.isRunning()).isFalse();
                });
    }

    @Test
    void bootsSqlInitializationDoesNotRunOnAStore() {
        withDataDefaults(runner)
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:tcp://127.0.0.1:1/none",
                        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
                        "synapse.data.stores.books.migrations.enabled=false",
                        "spring.sql.init.schema-locations=classpath:sql-init/schema.sql")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("dataSourceScriptDatabaseInitializer");
                    HikariDataSource dataSource = context.getBean("booksDataSource", HikariDataSource.class);
                    assertThat(dataSource.getHikariPoolMXBean()).isNull();
                });
    }

    @Test
    void bootsSqlInitializationCanBeTurnedBackOn() {
        withDataDefaults(runner)
                .withUserConfiguration(BooksStore.class)
                // Without migrations: Flyway would find the script's table in a schema it has no history for.
                .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:mem:sqlinit;DB_CLOSE_DELAY=-1",
                        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
                        "synapse.data.stores.books.migrations.enabled=false",
                        "spring.sql.init.schema-locations=classpath:sql-init/schema.sql", "spring.sql.init.mode=always")
                .run(context -> assertThat(context
                        .getBean("booksJdbcClient", JdbcClient.class)
                        .sql("SELECT COUNT(*) FROM sql_init_marker")
                        .query(Integer.class)
                        .single()).isZero());
    }

    @Test
    void storeTemplatesAcceptReplacedJdbcOperations() {
        runner
                .withUserConfiguration(BooksStoreWithOwnJdbcOperations.class)
                .withPropertyValues(store("books"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JdbcOperations own = context.getBean("booksJdbcTemplate", JdbcOperations.class);
                    assertThat(own).isNotInstanceOf(JdbcTemplate.class);
                    assertThat(context
                            .getBean("booksNamedParameterJdbcTemplate", NamedParameterJdbcTemplate.class)
                            .getJdbcOperations()).isSameAs(own);
                });
    }

    @Test
    void theFactoryDescribesTheStore() {
        books.run(context -> {
            StoreFactory factory = context.getBean(StoreFactory.class);
            StoreDefinition definition = factory.definition("books");
            assertThat(definition.name()).isEqualTo("books");
            assertThat(definition.beanPrefix()).isEqualTo("books");
            assertThat(definition.basePackage()).isEqualTo(BooksStore.class.getPackageName());
            assertThat(definition.access()).isEqualTo(DataAccess.JDBC);
            assertThat(definition.primary()).isFalse();
            assertThat(definition.declaredBy()).isEqualTo(BooksStore.class.getName());
            assertThat(definition.beanName("DataSource")).isEqualTo("booksDataSource");
            assertThat(factory.vendor("books")).isInstanceOf(H2TestVendor.class);
            assertThat(factory.properties("books").url()).isEqualTo(url("books"));
            assertThat(context.getBean("booksStoreDefinition")).isEqualTo(definition);
        });
    }

    @Test
    void bindsStoreProperties() {
        books
                .withPropertyValues("synapse.data.stores.books.driver-class-name=org.h2.Driver",
                        "synapse.data.stores.books.database-version=2.3.232",
                        "synapse.data.stores.books.allow-ddl-auto=true",
                        "synapse.data.stores.books.migrations.enabled=false",
                        "synapse.data.stores.books.migrations.locations=classpath:db/books-a,classpath:db/books-b",
                        "synapse.data.stores.books.migrations.flyway.baseline-on-migrate=true",
                        "synapse.data.stores.books.health.readiness=true",
                        "synapse.data.stores.books.health.timeout=250ms")
                .run(context -> {
                    SynapseDataStoreProperties properties = context.getBean(StoreFactory.class).properties("books");
                    assertThat(properties.username()).isEqualTo("sa");
                    assertThat(properties.password()).isEmpty();
                    assertThat(properties.driverClassName()).isEqualTo("org.h2.Driver");
                    assertThat(properties.databaseVersion()).isEqualTo("2.3.232");
                    assertThat(properties.allowDdlAuto()).isTrue();
                    assertThat(properties.migrations().enabled()).isFalse();
                    assertThat(properties.migrations().locations())
                            .containsExactly("classpath:db/books-a", "classpath:db/books-b");
                    assertThat(properties.migrations().flyway()).containsEntry("baseline-on-migrate", "true");
                    assertThat(properties.health().readiness()).isTrue();
                    assertThat(properties.health().timeout()).hasMillis(250);
                    assertThat(context.getBean("booksDataSource", HikariDataSource.class).getDriverClassName())
                            .isEqualTo("org.h2.Driver");
                });
    }

    @Test
    void storePropertiesHaveDefaults() {
        books.run(context -> {
            SynapseDataStoreProperties properties = context.getBean(StoreFactory.class).properties("books");
            assertThat(properties.primary()).isFalse();
            assertThat(properties.driverClassName()).isNull();
            assertThat(properties.databaseVersion()).isNull();
            assertThat(properties.allowDdlAuto()).isFalse();
            assertThat(properties.migrations().enabled()).isTrue();
            assertThat(properties.migrations().locations()).isNull();
            assertThat(properties.migrations().flyway()).isEmpty();
            assertThat(properties.health().readiness()).isFalse();
            assertThat(properties.health().timeout()).hasSeconds(1);
        });
    }

    @Test
    void bindsTheAuditingSystemUser() {
        runner
                .run(context -> assertThat(context.getBean(SynapseDataProperties.class).auditing().systemUser())
                        .isEqualTo("system"));
        runner
                .withPropertyValues("synapse.data.auditing.system-user=batch")
                .run(context -> assertThat(context.getBean(SynapseDataProperties.class).auditing().systemUser())
                        .isEqualTo("batch"));
    }

    /** Applies Synapse's data defaults, which a context runner doesn't run on its own. */
    private static ApplicationContextRunner withDataDefaults(ApplicationContextRunner runner) {
        return runner
                .withInitializer(context -> new DataDefaultsEnvironmentPostProcessor()
                        .postProcessEnvironment(context.getEnvironment(), new SpringApplication()));
    }

    @SuppressWarnings("unchecked")
    private static AuditorAware<String> auditor(ApplicationContext context) {
        return context.getBean(AUDITOR, AuditorAware.class);
    }

    private static String[] store(String name) {
        return new String[]{"synapse.data.stores." + name + ".url=" + url(name),
                "synapse.data.stores." + name + ".username=sa", "synapse.data.stores." + name + ".password="};
    }

    private static String url(String name) {
        return "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
    }

    private static @Nullable Object qualifier(BeanDefinition definition) {
        AutowireCandidateQualifier qualifier = ((AbstractBeanDefinition) definition)
                .getQualifier(Qualifier.class.getName());
        return qualifier == null ? null : qualifier.getAttribute(AutowireCandidateQualifier.VALUE_KEY);
    }

    static class LegacyConsumer {

        final DataSource dataSource;

        LegacyConsumer(@Qualifier("legacy") DataSource dataSource) {
            this.dataSource = dataSource;
        }
    }

    static class LegacyOrdersConsumer {

        final DataSource dataSource;

        LegacyOrdersConsumer(@Qualifier("legacy-orders") DataSource dataSource) {
            this.dataSource = dataSource;
        }
    }

    static class Details implements JdbcConnectionDetails {

        static final String URL = "jdbc:h2:mem:details;DB_CLOSE_DELAY=-1";

        @Override
        public String getUsername() {
            return "details-user";
        }

        @Override
        public String getPassword() {
            return "";
        }

        @Override
        public String getJdbcUrl() {
            return URL;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class QualifiedDetails {

        @Bean
        @Qualifier("books")
        JdbcConnectionDetails booksConnectionDetails() {
            return new Details();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonDefaultCandidateDetails {

        @Bean(defaultCandidate = false)
        @Qualifier("books")
        JdbcConnectionDetails booksConnectionDetails() {
            return new Details();
        }
    }

    /** Qualified details without a password, for a database that isn't embedded (nothing connects). */
    @Configuration(proxyBeanMethods = false)
    static class PasswordlessDetails {

        static final String URL = "jdbc:h2:tcp://127.0.0.1:1/none";

        @Bean
        @Qualifier("books")
        JdbcConnectionDetails booksConnectionDetails() {
            return new JdbcConnectionDetails() {

                @Override
                public String getUsername() {
                    return "details-user";
                }

                @Override
                public @Nullable String getPassword() {
                    return null;
                }

                @Override
                public String getJdbcUrl() {
                    return URL;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyDetails {

        @Bean
        @Qualifier("legacy")
        JdbcConnectionDetails legacyConnectionDetails() {
            return new Details();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TranslatorConfiguration {

        static final SQLExceptionTranslator TRANSLATOR = new SQLExceptionTranslator() {

            @Override
            public @Nullable DataAccessException translate(String task, @Nullable String sql, SQLException ex) {
                return null;
            }
        };

        @Bean
        SQLExceptionTranslator sqlExceptionTranslator() {
            return TRANSLATOR;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserDataExceptionTranslator {

        static final ExceptionTranslator TRANSLATOR = exception -> null;

        @Bean
        ExceptionTranslator synapseDataExceptionTranslator() {
            return TRANSLATOR;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherExceptionTranslator {

        @Bean
        ExceptionTranslator otherExceptionTranslator() {
            return exception -> null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedClock {

        static final MutableClock CLOCK = MutableClock.at("2026-01-01T00:00:00Z");

        @Bean
        Clock clock() {
            return CLOCK;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserEntityIdGenerator {

        static final EntityIdGenerator IDS = UUID::randomUUID;

        @Bean
        EntityIdGenerator userIds() {
            return IDS;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserAuditorAware {

        static final AuditorAware<String> AUDITOR = () -> Optional.of("auditor");

        @Bean
        AuditorAware<String> userAuditor() {
            return AUDITOR;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserIdAuditorAware {

        static final AuditorAware<Long> AUDITOR = () -> Optional.of(42L);

        @Bean
        AuditorAware<Long> userIdAuditor() {
            return AUDITOR;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NamedLikeSynapsesAuditor {

        static final AuditorAware<String> AUDITOR = () -> Optional.of("named");

        @Bean
        AuditorAware<String> synapseAuditorAware() {
            return AUDITOR;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserAuditStamps {

        static final AuditStamps STAMPS = new AuditStamps(Clock.systemUTC(), new DefaultListableBeanFactory()
                .getBeanProvider(ResolvableType.forClassWithGenerics(AuditorAware.class, String.class)),
                () -> Optional.of("stamps"), "system");

        @Bean
        AuditStamps userStamps() {
            return STAMPS;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserStoreFactory {

        static @Nullable StoreFactory created;

        @Bean
        StoreFactory synapseDataStoreFactory(ConfigurableListableBeanFactory beanFactory, Environment environment,
                ObjectProvider<DataStoreVendor> vendors, ObjectProvider<SQLExceptionTranslator> translators) {
            StoreFactory factory = new StoreFactory(beanFactory, environment, vendors, translators);
            created = factory;
            return factory;
        }
    }

    /** Declares the books store and replaces its DataSource with one that names its own qualifier. */
    @Configuration(proxyBeanMethods = false)
    @SynapseDataStore("books")
    static class BooksStoreWithOwnQualifiedDataSource {

        @Bean
        @Qualifier("own")
        DataSource booksDataSource() {
            return new DriverManagerDataSource(BooksStoreWithOwnDataSource.URL, "sa", "");
        }
    }
}
