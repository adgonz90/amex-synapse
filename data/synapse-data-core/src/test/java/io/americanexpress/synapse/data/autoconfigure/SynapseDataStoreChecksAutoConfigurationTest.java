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

import java.util.Objects;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
import io.americanexpress.synapse.data.internal.store.StoreDefinitionsPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseDataStoreChecksAutoConfigurationTest {

    private static final String POST_PROCESSOR = "synapseStoreDefinitionsPostProcessor";

    private static final String UNDECLARED = "synapse.data.stores.bokks is configured, but no "
            + "@SynapseDataStore(\"bokks\") declares the store.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SynapseDataStoreChecksAutoConfiguration.class));

    @Test
    void registersThePostProcessor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(POST_PROCESSOR)).isInstanceOf(StoreDefinitionsPostProcessor.class);
        });
    }

    @Test
    void anUndeclaredStoreFailsWhenNoStoreIsDeclared() {
        runner.withPropertyValues("synapse.data.stores.bokks.url=jdbc:h2:mem:bokks").run(context -> {
            SynapseConfigurationException failure = failure(context);
            assertThat(failure.description()).isEqualTo(UNDECLARED);
            assertThat(failure.action()).isEqualTo("Add the data module that declares it, or remove the settings.");
        });
    }

    @Test
    void noStoreAndNoStoreKeysStarts() {
        runner
                .withPropertyValues("synapse.data.auditing.system-user=batch")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theSliceSkipsTheCheckWhenNoStoreIsDeclared() {
        runner
                .withPropertyValues("synapse.data.stores.bokks.url=jdbc:h2:mem:bokks")
                .withBean("synapseDataTestSlice", Object.class, Object::new)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void doesNotNeedHikari() {
        runner
                .withClassLoader(new FilteredClassLoader(HikariDataSource.class))
                .withPropertyValues("synapse.data.stores.bokks.url=jdbc:h2:mem:bokks")
                .run(context -> assertThat(failure(context).description()).isEqualTo(UNDECLARED));
    }

    @Test
    void backsOffToABeanWithItsName() {
        runner
                .withUserConfiguration(UserPostProcessor.class)
                .withPropertyValues("synapse.data.stores.bokks.url=jdbc:h2:mem:bokks")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(POST_PROCESSOR)).isInstanceOf(NoChecks.class);
                });
    }

    @Test
    void aDeclaredStoreSharesTheOnePostProcessor() {
        runner
                .withConfiguration(
                        AutoConfigurations.of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class))
                .withUserConfiguration(H2TestVendor.class, BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:mem:books;DB_CLOSE_DELAY=-1",
                        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(StoreDefinitionsPostProcessor.class))
                            .containsOnlyKeys(POST_PROCESSOR);
                    assertThat(context.getBeanFactory().getBeanDefinition("booksDataSource").isPrimary()).isTrue();
                });
    }

    private static SynapseConfigurationException failure(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        Throwable root = NestedExceptionUtils
                .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
        assertThat(root).isInstanceOf(SynapseConfigurationException.class);
        return (SynapseConfigurationException) root;
    }

    static class NoChecks implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserPostProcessor {

        @Bean(POST_PROCESSOR)
        static BeanDefinitionRegistryPostProcessor synapseStoreDefinitionsPostProcessor() {
            return new NoChecks();
        }
    }
}
