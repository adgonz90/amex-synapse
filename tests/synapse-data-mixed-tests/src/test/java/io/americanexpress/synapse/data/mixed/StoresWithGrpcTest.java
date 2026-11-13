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

import java.util.Map;
import jakarta.persistence.EntityManagerFactory;

import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.test.tools.CompileWithForkedClassLoader;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.javapoet.ClassName;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.americanexpress.synapse.data.mixed.fixture.H2TestVendor;
import io.americanexpress.synapse.data.mixed.fixture.books.BooksStore;
import io.americanexpress.synapse.data.mixed.fixture.legacy.LegacyStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A service with stores and the gRPC plugin, under the whole auto-configuration as an application has it. Boot's gRPC
 * server configuration is processed before Synapse's JPA auto-configuration, and its {@code GrpcAdviceConfiguration}
 * has {@code @ConditionalOnBean(annotation = GrpcAdvice.class)}: that condition asks every bean definition for its
 * type, the JPA store's EntityManagerFactory included, before {@code synapseJpaStoreFactory}, the factory bean that
 * definition names, is defined. Both stores must start, on the JVM and through AOT processing (which refreshes the
 * context the same way at build time).
 */
// Generated code calls package-private members, so it must be defined in the same class loader as the test classes.
@CompileWithForkedClassLoader
class StoresWithGrpcTest {

    private static final String HIBERNATE_SERVICE = "org.hibernate.orm.service";

    private static final Map<String, Object> STORES = Map
            .of("synapse.data.stores.books.url", "jdbc:h2:mem:grpc-books;DB_CLOSE_DELAY=-1",
                    "synapse.data.stores.books.username", "sa", "synapse.data.stores.books.password", "",
                    "synapse.data.stores.legacy.url", "jdbc:h2:mem:grpc-legacy;DB_CLOSE_DELAY=-1",
                    "synapse.data.stores.legacy.username", "sa", "synapse.data.stores.legacy.password", "",
                    "spring.grpc.server.port", "0");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Service.class)
            .withPropertyValues(STORES
                    .entrySet()
                    .stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toArray(String[]::new));

    /**
     * Silences Hibernate's harmless HHH010505 WARN while this class runs, as {@code StoreAotProcessingTest} does: the
     * forked class loader makes Hibernate's ServiceLoader see its ByteBuddy {@code BytecodeProvider} twice.
     */
    @BeforeAll
    static void silenceTheForkedLoadersServiceWarning() {
        LoggingSystem.get(StoresWithGrpcTest.class.getClassLoader()).setLogLevel(HIBERNATE_SERVICE, LogLevel.ERROR);
    }

    @AfterAll
    static void restoreTheServiceLogger() {
        LoggingSystem.get(StoresWithGrpcTest.class.getClassLoader()).setLogLevel(HIBERNATE_SERVICE, null);
    }

    @Test
    void storesStartBesideTheGrpcServer() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GrpcServerLifecycle.class);
            assertThat(context.getBean("booksEntityManagerFactory")).isInstanceOf(EntityManagerFactory.class);
            assertThat(context
                    .getBean("legacyJdbcClient", JdbcClient.class)
                    .sql("SELECT 1")
                    .query(Integer.class)
                    .single()).isOne();
        });
    }

    @Test
    void storesAreProcessedAheadOfTimeBesideTheGrpcServer() {
        runner.prepare(context -> {
            TestGenerationContext generationContext = new TestGenerationContext();
            ClassName initializerName = new ApplicationContextAotGenerator()
                    .processAheadOfTime((GenericApplicationContext) context.getSourceApplicationContext(),
                            generationContext);
            generationContext.writeGeneratedContent();
            TestCompiler.forSystem().with(generationContext).compile(compiled -> {
                try (GenericApplicationContext aotContext = new GenericApplicationContext()) {
                    aotContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("run", STORES));
                    @SuppressWarnings("unchecked")
                    ApplicationContextInitializer<GenericApplicationContext> initializer = compiled
                            .getInstance(ApplicationContextInitializer.class, initializerName.toString());
                    initializer.initialize(aotContext);
                    aotContext.refresh();

                    assertThat(aotContext.getBeansOfType(EntityManagerFactory.class))
                            .containsOnlyKeys(
                                    "booksEntityManagerFactory");
                    assertThat(aotContext.getBeansOfType(GrpcServerLifecycle.class)).hasSize(1);
                }
            });
        });
    }

    /** The application: a JPA store, a JDBC store, and one gRPC service, which turns Boot's gRPC server on. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({H2TestVendor.class, BooksStore.class, LegacyStore.class})
    static class Service {

        @Bean
        BindableService pingService() {
            return () -> ServerServiceDefinition.builder("synapse.test.Ping").build();
        }
    }
}
