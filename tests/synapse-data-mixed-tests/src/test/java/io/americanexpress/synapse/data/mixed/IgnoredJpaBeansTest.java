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
package io.americanexpress.synapse.data.mixed;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryBuilderCustomizer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.jdbc.autoconfigure.SynapseDataJdbcAutoConfiguration;
import io.americanexpress.synapse.data.jpa.autoconfigure.SynapseDataJpaAutoConfiguration;
import io.americanexpress.synapse.data.mixed.fixture.H2TestVendor;
import io.americanexpress.synapse.data.mixed.fixture.books.BooksStore;
import io.americanexpress.synapse.data.mixed.fixture.legacy.LegacyStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With synapse-data-jpa on the class path, the JPA beans that stores don't use are named only while a JPA store
 * exists: a JDBC store never uses them, so a service with JDBC stores only gets no WARN for them.
 */
@ExtendWith(OutputCaptureExtension.class)
class IgnoredJpaBeansTest {

    private static final String WARNING = "The EntityManagerFactoryBuilderCustomizer bean myCustomizer";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataJpaAutoConfiguration.class, SynapseDataJdbcAutoConfiguration.class))
            .withUserConfiguration(H2TestVendor.class, LegacyStore.class)
            .withPropertyValues("synapse.data.stores.legacy.url=jdbc:h2:mem:ignored-legacy;DB_CLOSE_DELAY=-1",
                    "synapse.data.stores.legacy.username=sa", "synapse.data.stores.legacy.password=")
            .withBean("myCustomizer", EntityManagerFactoryBuilderCustomizer.class, () -> builder -> {
            });

    @Test
    void noJpaStoreNoWarning(CapturedOutput output) {
        runner.run(started());
        assertThat(output.getOut()).contains("Synapse data stores: legacy").doesNotContain(WARNING);
    }

    @Test
    void aJpaStoreBesideAJdbcStoreWarns(CapturedOutput output) {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:mem:ignored-books;DB_CLOSE_DELAY=-1",
                        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=")
                .run(started());
        assertThat(output.getOut().lines().filter(line -> line.contains(WARNING))).hasSize(1);
    }

    private static ContextConsumer<AssertableApplicationContext> started() {
        return context -> {
            assertThat(context).hasNotFailed();
            context
                    .publishEvent(new ApplicationStartedEvent(new SpringApplication(), new String[0],
                            context.getSourceApplicationContext(), Duration.ZERO));
        };
    }
}
