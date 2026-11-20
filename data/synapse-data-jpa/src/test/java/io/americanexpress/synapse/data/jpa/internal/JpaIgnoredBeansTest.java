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
package io.americanexpress.synapse.data.jpa.internal;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryBuilderCustomizer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.persistenceunit.DefaultPersistenceUnitManager;
import org.springframework.orm.jpa.persistenceunit.PersistenceUnitManager;

import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.jpa.autoconfigure.SynapseDataJpaAutoConfiguration;
import io.americanexpress.synapse.data.jpa.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jpa.fixture.books.BooksStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot's JPA beans that a JPA store doesn't use (R10): the data stores' startup report names each one with a WARN,
 * since Synapse builds each store's EntityManagerFactory without them.
 */
@ExtendWith(OutputCaptureExtension.class)
class JpaIgnoredBeansTest {

    private static final String NOT_USED = "is not used by any store: Synapse builds JPA stores without it.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataJpaAutoConfiguration.class))
            .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:mem:ignored-beans;DB_CLOSE_DELAY=-1",
                    "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
                    "synapse.data.stores.books.migrations.enabled=false",
                    "synapse.data.stores.books.jpa.hibernate.ddl-auto=none")
            .withUserConfiguration(H2TestVendor.class, BooksStore.class);

    @Test
    void aBuilderCustomizerIsNamedAsIgnored(CapturedOutput output) {
        runner
                .withBean("myCustomizer", EntityManagerFactoryBuilderCustomizer.class, () -> builder -> {
                })
                .run(started());
        assertThat(lines(output, "myCustomizer"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ", "io.americanexpress.synapse.data ")
                        .endsWith("The EntityManagerFactoryBuilderCustomizer bean myCustomizer "
                                + "(org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryBuilderCustomizer) "
                                + NOT_USED));
    }

    @Test
    void aPersistenceUnitManagerIsNamedAsIgnored(CapturedOutput output) {
        runner
                .withBean("myUnits", PersistenceUnitManager.class, DefaultPersistenceUnitManager::new)
                .run(started());
        assertThat(lines(output, "myUnits"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ")
                        .endsWith("The PersistenceUnitManager bean myUnits "
                                + "(org.springframework.orm.jpa.persistenceunit.PersistenceUnitManager) " + NOT_USED));
    }

    @Test
    void entityScanPackagesAreNamedAsIgnored(CapturedOutput output) {
        runner.withUserConfiguration(ScansAcme.class).run(started());
        assertThat(lines(output, "@EntityScan"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ")
                        .endsWith("@EntityScan names com.acme, which no store uses: a JPA store's entities are those "
                                + "in and below its store class's package."));
    }

    @Test
    void entityScanPackagesWithNoPackagesAreNotNamed(CapturedOutput output) {
        runner
                .withInitializer(context -> EntityScanPackages.register((BeanDefinitionRegistry) context, List.of()))
                .run(started());
        assertThat(output.getOut()).contains("Synapse data stores:").doesNotContain("@EntityScan");
    }

    @Test
    void noIgnoredBeanNoWarning(CapturedOutput output) {
        runner.run(started());
        assertThat(output.getOut())
                .contains("Synapse data stores:")
                .doesNotContain("is not used by any store", "@EntityScan");
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

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.acme")
    static class ScansAcme {
    }
}
