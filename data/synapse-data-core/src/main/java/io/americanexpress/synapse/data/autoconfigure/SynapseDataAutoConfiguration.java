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

import java.security.Principal;
import java.time.Clock;
import java.util.Optional;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.data.EntityIdGenerator;
import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.internal.audit.AuditStamps;
import io.americanexpress.synapse.data.internal.error.DataExceptionTranslator;
import io.americanexpress.synapse.data.internal.id.UuidV7Generator;
import io.americanexpress.synapse.data.internal.migration.FlywayStoreFactory;
import io.americanexpress.synapse.data.internal.store.DataStartupReport;
import io.americanexpress.synapse.data.internal.store.StoreFactory;
import io.americanexpress.synapse.data.internal.store.UnqualifiedTransactionCheck;
import io.americanexpress.synapse.data.vendor.DataStoreVendor;

/**
 * Builds the beans of every {@link SynapseDataStore}. The store's bean definitions name the
 * {@code synapseDataStoreFactory} bean defined here as their factory.
 */
@AutoConfiguration(after = SynapseCoreAutoConfiguration.class)
@ConditionalOnClass(HikariDataSource.class)
@EnableConfigurationProperties(SynapseDataProperties.class)
public final class SynapseDataAutoConfiguration {

    private static final String DATA_EXCEPTION_TRANSLATOR = "synapseDataExceptionTranslator";

    SynapseDataAutoConfiguration() {
    }

    @Bean(StoreFactory.BEAN_NAME)
    @ConditionalOnMissingBean
    static StoreFactory synapseDataStoreFactory(ConfigurableListableBeanFactory beanFactory, Environment environment,
            ObjectProvider<DataStoreVendor> vendors, ObjectProvider<SQLExceptionTranslator> exceptionTranslators) {
        return new StoreFactory(beanFactory, environment, vendors, exceptionTranslators);
    }

    /**
     * Builds each store's {@code <prefix>Flyway}, while Flyway is on the class path. With a {@code Flyway} bean per
     * store, Boot's Flyway auto-configuration backs off. The store's Flyway bean definitions name the
     * {@code synapseDataFlywayFactory} bean defined here as their factory.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Flyway.class)
    static class FlywayConfiguration {

        @Bean(FlywayStoreFactory.BEAN_NAME)
        @ConditionalOnMissingBean
        static FlywayStoreFactory synapseDataFlywayFactory(ConfigurableListableBeanFactory beanFactory,
                Environment environment) {
            return new FlywayStoreFactory(beanFactory, environment);
        }
    }

    /**
     * Checks, while spring-tx is on the class path, that every {@code @Transactional} can choose a transaction
     * manager.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(TransactionAttributeSource.class)
    static class TransactionConfiguration {

        /**
         * Fails startup when there are several stores, none of them primary, and a bean has an unqualified
         * {@code @Transactional}. It is never lazy, so it runs with {@code spring.main.lazy-initialization} too. Define
         * a bean with the same name to replace it.
         *
         * @param beanFactory the bean factory holding the stores and the beans to check
         * @return the check
         */
        @Bean(UnqualifiedTransactionCheck.BEAN_NAME)
        @ConditionalOnMissingBean(name = UnqualifiedTransactionCheck.BEAN_NAME)
        @Lazy(false)
        static UnqualifiedTransactionCheck synapseUnqualifiedTransactionCheck(
                ConfigurableListableBeanFactory beanFactory) {
            return new UnqualifiedTransactionCheck(beanFactory);
        }
    }

    /**
     * Translates Spring's data access exceptions into Synapse errors ({@code STALE_VERSION}, {@code ALREADY_EXISTS},
     * {@code DEADLINE_EXCEEDED}, {@code UNAVAILABLE}) for the operation pipeline. Define a bean with the same name to
     * replace it.
     *
     * @return the translator
     */
    @Bean(DATA_EXCEPTION_TRANSLATOR)
    @ConditionalOnMissingBean(name = DATA_EXCEPTION_TRANSLATOR)
    static DataExceptionTranslator synapseDataExceptionTranslator() {
        return new DataExceptionTranslator();
    }

    /**
     * Generates the UUIDv7 ids of new entities from the application's {@link Clock}. Define an
     * {@link EntityIdGenerator} bean to replace it.
     *
     * @param clock the clock the ids' timestamps come from
     * @return the id generator
     */
    @Bean
    @ConditionalOnMissingBean(EntityIdGenerator.class)
    static EntityIdGenerator synapseEntityIdGenerator(Clock clock) {
        return new UuidV7Generator(clock);
    }

    /**
     * Names the user that audited entities record when the application defines no {@code AuditorAware<String>}: the
     * principal of the current Synapse request context, else {@code synapse.data.auditing.system-user}. It is not a
     * default candidate, so it never meets an auditor of the application's own Spring Data auditing, and nothing that
     * injects an {@link AuditorAware} by type receives it. Define an {@code AuditorAware<String>} bean to stamp another
     * user, or a bean with this name to replace it.
     *
     * @param properties the data settings, for the system user
     * @return the auditor
     */
    @Bean(name = AuditStamps.SYNAPSE_AUDITOR, defaultCandidate = false)
    @ConditionalOnMissingBean(name = AuditStamps.SYNAPSE_AUDITOR)
    static AuditorAware<String> synapseAuditorAware(SynapseDataProperties properties) {
        String systemUser = properties.auditing().systemUser();
        return () -> Optional
                .of(RequestContext
                        .find()
                        .flatMap(RequestContext::principal)
                        .map(Principal::getName)
                        .orElse(systemUser));
    }

    /**
     * Gives audited entities their time and user, for every data access module: the clock's time truncated to
     * microseconds, and the user of the application's {@code AuditorAware<String>}, else of
     * {@code synapseAuditorAware}. To stamp another user, define an {@code AuditorAware<String>} bean.
     *
     * @param clock the clock the time comes from
     * @param applicationAuditors the application's {@code AuditorAware<String>}, if it defines one
     * @param synapseAuditor Synapse's auditor
     * @param properties the data settings, for the system user
     * @return the stamps
     */
    @Bean
    @ConditionalOnMissingBean
    static AuditStamps synapseAuditStamps(Clock clock, ObjectProvider<AuditorAware<String>> applicationAuditors,
            @Qualifier(AuditStamps.SYNAPSE_AUDITOR) AuditorAware<String> synapseAuditor,
            SynapseDataProperties properties) {
        return new AuditStamps(clock, applicationAuditors, synapseAuditor, properties.auditing().systemUser());
    }

    /**
     * Logs the stores' connection budget once the application has started, and warns about the Boot keys the stores
     * ignore: {@code spring.datasource.*}, {@code spring.flyway.*}, {@code spring.jdbc.template.*} and those of the
     * access modules the stores use.
     *
     * @param context the application context the stores are in
     * @return the report
     */
    @Bean
    @ConditionalOnMissingBean
    static DataStartupReport synapseDataStartupReport(ApplicationContext context) {
        return new DataStartupReport(context);
    }
}
