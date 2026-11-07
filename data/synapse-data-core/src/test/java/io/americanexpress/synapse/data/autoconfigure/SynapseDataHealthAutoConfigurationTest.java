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

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.endpoint.web.AdditionalPathsMapper;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthContributors;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.fixture.H2TestVendor;
import io.americanexpress.synapse.data.fixture.books.BooksStore;
import io.americanexpress.synapse.data.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.fixture.legacyorders.LegacyOrdersStore;
import io.americanexpress.synapse.data.fixture.ownbooks.BooksStoreWithOwnDataSource;
import io.americanexpress.synapse.data.fixture.wrappedbooks.BooksStoreWithWrappedDataSource;
import io.americanexpress.synapse.data.internal.health.StoreHealthIndicator;
import io.americanexpress.synapse.data.internal.health.StoreReadinessGroups;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseDataHealthAutoConfigurationTest {

    private static final String CONTRIBUTOR = "dbHealthContributor";

    private static final String READINESS = "synapseStoreReadinessGroups";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataHealthAutoConfiguration.class, DataSourceAutoConfiguration.class,
                            DataSourceHealthContributorAutoConfiguration.class))
            .withUserConfiguration(H2TestVendor.class);

    private final ApplicationContextRunner booksAndLegacy = runner
            .withUserConfiguration(BooksStore.class, LegacyStore.class)
            .withPropertyValues(store("books"))
            .withPropertyValues(store("legacy"));

    @Test
    void replacesBootsDbContributorWithOnePerStore() {
        booksAndLegacy.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(HealthContributor.class)).containsOnlyKeys(CONTRIBUTOR);
            assertThat(children(context)).containsOnlyKeys("books", "legacy");
            assertThat(children(context).values())
                    .allSatisfy(child -> assertThat(child).isInstanceOf(StoreHealthIndicator.class));
            assertThat(health(context, "books").getStatus().getCode()).isEqualTo("UP");
            assertThat(health(context, "legacy").getStatus().getCode()).isEqualTo("UP");
            assertThat(context.getBean(READINESS, StoreReadinessGroups.class).members()).isEmpty();
        });
    }

    @Test
    void aSingleStoreIsAChildToo() {
        runner.withUserConfiguration(BooksStore.class).withPropertyValues(store("books")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(children(context)).containsOnlyKeys("books");
        });
    }

    @Test
    void aDashedStoreKeepsItsName() {
        runner
                .withUserConfiguration(LegacyOrdersStore.class)
                .withPropertyValues(store("legacy-orders"))
                .withPropertyValues("synapse.data.stores.legacy-orders.health.readiness=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(children(context)).containsOnlyKeys("legacy-orders");
                    assertThat(context.getBean(READINESS, StoreReadinessGroups.class).members())
                            .containsExactly("db/legacy-orders");
                });
    }

    @Test
    void aStoreWithoutAHikariPoolIsCheckedToo() {
        runner
                .withUserConfiguration(BooksStoreWithOwnDataSource.class, LegacyStore.class)
                .withPropertyValues(store("books"))
                .withPropertyValues(store("legacy"))
                .withPropertyValues("synapse.data.stores.books.health.readiness=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("booksDataSource")).isNotInstanceOf(HikariDataSource.class);
                    assertThat(children(context)).containsOnlyKeys("books", "legacy");
                    assertThat(health(context, "books").getStatus().getCode()).isEqualTo("UP");
                    assertThat(context.getBean(READINESS, StoreReadinessGroups.class).members())
                            .containsExactly("db/books");
                });
    }

    /**
     * Building the stores' health opens no connection: the pools start at the first check. (Migrations, which connect
     * when the stores start, are off here.)
     */
    @Test
    void healthOpensNoConnectionAtStartup() {
        booksAndLegacy
                .withPropertyValues("synapse.data.stores.books.migrations.enabled=false",
                        "synapse.data.stores.legacy.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(children(context)).containsOnlyKeys("books", "legacy");
                    assertThat(context.getBean("booksDataSource", HikariDataSource.class).getHikariPoolMXBean())
                            .isNull();
                    assertThat(context.getBean("legacyDataSource", HikariDataSource.class).getHikariPoolMXBean())
                            .isNull();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-5s"})
    void aTimeoutThatIsNotPositiveFailsStartup(String timeout) {
        booksAndLegacy.withPropertyValues("synapse.data.stores.books.health.timeout=" + timeout).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                        assertThat(failure.description())
                                .isEqualTo("The store 'books' sets synapse.data.stores.books.health.timeout to a "
                                        + "duration that is not positive, so its health check could never pass.");
                        assertThat(failure.action())
                                .isEqualTo("Set synapse.data.stores.books.health.timeout to a positive duration, "
                                        + "or remove it to wait 1s.");
                        assertThat(failure.description() + failure.action()).doesNotContain(timeout);
                    });
        });
    }

    @Test
    void aStoreWhoseDataSourceWrapsAHikariPoolChecksThePool() {
        runner
                .withUserConfiguration(BooksStoreWithWrappedDataSource.class)
                .withPropertyValues(store("books"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(children(context)).containsOnlyKeys("books");
                    assertThat(health(context, "books").getStatus().getCode()).isEqualTo("UP");
                    assertThat(BooksStoreWithWrappedDataSource.POOL.getHikariPoolMXBean()).isNotNull();
                });
    }

    @Test
    void backsOffToAUserDbHealthContributor() {
        booksAndLegacy.withUserConfiguration(UserContributor.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CONTRIBUTOR)).isSameAs(UserContributor.CONTRIBUTOR);
        });
    }

    @Test
    void backsOffToAUserDbHealthIndicator() {
        booksAndLegacy.withUserConfiguration(UserIndicator.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(HealthContributor.class)).containsOnlyKeys("dbHealthIndicator");
        });
    }

    @Test
    void backsOffToAUserReadinessPostProcessor() {
        booksAndLegacy.withUserConfiguration(UserReadiness.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(READINESS)).isSameAs(UserReadiness.POST_PROCESSOR);
        });
    }

    /** Without Boot's health support, there is no contributor (Boot's own health configuration is left out here). */
    @Test
    void absentWithoutHealth() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                                SynapseDataHealthAutoConfiguration.class))
                .withUserConfiguration(H2TestVendor.class, BooksStore.class)
                .withPropertyValues(store("books"))
                .withClassLoader(new FilteredClassLoader(HealthContributor.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CONTRIBUTOR).doesNotHaveBean(READINESS);
                    assertThat(context).hasBean("booksDataSource");
                });
    }

    @Test
    void readinessIsAbsentWithoutTheActuator() {
        booksAndLegacy.withClassLoader(new FilteredClassLoader(AdditionalPathsMapper.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean(CONTRIBUTOR).doesNotHaveBean(READINESS);
        });
    }

    @Test
    void absentWhenTheDbIndicatorIsDisabled() {
        booksAndLegacy.withPropertyValues("management.health.db.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CONTRIBUTOR).doesNotHaveBean(READINESS);
        });
    }

    /** Without a store, Boot's contributor reports the application's own DataSource. */
    @Test
    void absentWithoutAStore() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DataSource.class).doesNotHaveBean(READINESS);
            assertThat(context.getBean(CONTRIBUTOR)).isInstanceOf(DataSourceHealthIndicator.class);
        });
    }

    @Test
    void bindsHealthTimeout() {
        booksAndLegacy.withPropertyValues("synapse.data.stores.books.health.timeout=200ms").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(timeout(context, "books")).isEqualTo(Duration.ofMillis(200));
            assertThat(timeout(context, "legacy")).isEqualTo(Duration.ofSeconds(1));
        });
    }

    @Test
    void bindsHealthReadiness() {
        booksAndLegacy.withPropertyValues("synapse.data.stores.legacy.health.readiness=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(READINESS, StoreReadinessGroups.class).members())
                    .containsExactly("db/legacy");
        });
    }

    private static Map<String, HealthContributor> children(AssertableApplicationContext context) {
        CompositeHealthContributor composite = context.getBean(CONTRIBUTOR, CompositeHealthContributor.class);
        return composite
                .stream()
                .collect(Collectors.toMap(HealthContributors.Entry::name, HealthContributors.Entry::contributor));
    }

    private static Health health(AssertableApplicationContext context, String store) {
        HealthIndicator indicator = (HealthIndicator) Objects.requireNonNull(children(context).get(store));
        return Objects.requireNonNull(indicator.health());
    }

    private static Duration timeout(AssertableApplicationContext context, String store) {
        return ((StoreHealthIndicator) Objects.requireNonNull(children(context).get(store))).timeout();
    }

    private static String[] store(String name) {
        return new String[]{"synapse.data.stores." + name + ".url=jdbc:h2:mem:health-" + name + ";DB_CLOSE_DELAY=-1",
                "synapse.data.stores." + name + ".username=sa", "synapse.data.stores." + name + ".password="};
    }

    @Configuration(proxyBeanMethods = false)
    static class UserContributor {

        static final HealthContributor CONTRIBUTOR = (HealthIndicator) () -> Health.up().build();

        @Bean
        HealthContributor dbHealthContributor() {
            return CONTRIBUTOR;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserIndicator {

        @Bean
        HealthIndicator dbHealthIndicator() {
            return () -> Health.up().build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserReadiness {

        static final HealthEndpointGroupsPostProcessor POST_PROCESSOR = groups -> groups;

        @Bean
        HealthEndpointGroupsPostProcessor synapseStoreReadinessGroups() {
            return POST_PROCESSOR;
        }
    }
}
