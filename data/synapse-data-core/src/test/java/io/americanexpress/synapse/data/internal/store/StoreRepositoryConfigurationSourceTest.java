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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.data.repository.config.DefaultRepositoryBaseClass;
import org.springframework.data.repository.config.RepositoryConfigurationExtensionSupport;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.query.QueryLookupStrategy.Key;

import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.fixture.repositories.ShelfRepository;
import io.americanexpress.synapse.data.fixture.repositories.archive.ArchivedShelfRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StoreRepositoryConfigurationSourceTest {

    private static final String PACKAGE = "io.americanexpress.synapse.data.fixture.repositories";

    private static final StoreDefinition BOOKS = new StoreDefinition("books", "books", PACKAGE, DataAccess.JPA, false,
            PACKAGE + ".BooksStore");

    private static final Map<String, String> REFERENCES = Map
            .of("entityManagerFactoryRef",
                    "booksEntityManagerFactory");

    private final DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

    @Test
    void basePackageIsTheStoresPackage() {
        assertThat(source(REFERENCES).getBasePackages()).containsExactly(PACKAGE);
    }

    @Test
    void referencesAnswerTheirAttributes() {
        StoreRepositoryConfigurationSource source = source(REFERENCES);

        assertThat(source.getAttribute("entityManagerFactoryRef")).contains("booksEntityManagerFactory");
        assertThat(source.getRequiredAttribute("entityManagerFactoryRef", String.class))
                .isEqualTo("booksEntityManagerFactory");
        assertThat(source.getAttributes().getString("entityManagerFactoryRef")).isEqualTo("booksEntityManagerFactory");
    }

    @Test
    void otherAttributesAreTheAnnotationsDefaults() {
        StoreRepositoryConfigurationSource source = source(REFERENCES);

        assertThat(source.getAttribute("transactionManagerRef")).contains("transactionManager");
        assertThat(source.getAttribute("enableDefaultTransactions", Boolean.class)).contains(true);
        assertThat(source.getRequiredAttribute("escapeCharacter", Character.class)).isEqualTo('\\');
        assertThat(source.getRepositoryImplementationPostfix()).contains("Impl");
        assertThat(source.getNamedQueryLocation()).isEmpty();
        assertThat(source.getQueryLookupStrategyKey()).contains(Key.CREATE_IF_NOT_FOUND);
        assertThat(source.getRepositoryFactoryBeanClassName()).contains(RepositoryFactoryBeanSupport.class.getName());
        assertThat(source.getRepositoryBaseClassName()).isEmpty();
        assertThat(source.getBootstrapMode()).isEqualTo(BootstrapMode.DEFAULT);
        assertThat(source.shouldConsiderNestedRepositories()).isFalse();
        assertThat(source.usesExplicitFilters()).isFalse();
    }

    @Test
    void withoutReferencesEveryAttributeIsADefault() {
        assertThat(source(Map.of()).getAttribute("entityManagerFactoryRef")).contains("entityManagerFactory");
    }

    @Test
    void aReferenceTheAnnotationDoesNotDeclareIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> source(Map.of("jdbcOperationsRef", "booksJdbcTemplate")))
                .withMessageContaining("jdbcOperationsRef")
                .withMessageContaining(TestRepositories.class.getName());
    }

    @Test
    void sourceAndDescriptionNameTheStore() {
        StoreRepositoryConfigurationSource source = source(REFERENCES);

        assertThat(source.getSource()).isInstanceOfSatisfying(AnnotationMetadata.class, metadata -> {
            assertThat(metadata.getClassName()).isEqualTo(BOOKS.declaredBy());
            assertThat(metadata.hasAnnotation(TestRepositories.class.getName())).isTrue();
        });
        assertThat(source.getResourceDescription()).isEqualTo("@SynapseDataStore(\"books\") on BooksStore");
    }

    @Test
    void theStoresClassIsAPlainTopLevelClassWithoutMethods() {
        AnnotationMetadata metadata = (AnnotationMetadata) source(REFERENCES).getSource();

        assertThat(metadata.isInterface()).isFalse();
        assertThat(metadata.isAnnotation()).isFalse();
        assertThat(metadata.isAbstract()).isFalse();
        assertThat(metadata.isFinal()).isFalse();
        assertThat(metadata.isIndependent()).isTrue();
        assertThat(metadata.getEnclosingClassName()).isNull();
        assertThat(metadata.getSuperClassName()).isNull();
        assertThat(metadata.getInterfaceNames()).isEmpty();
        assertThat(metadata.getMemberClassNames()).isEmpty();
        assertThat(metadata.getAnnotatedMethods(TestRepositories.class.getName())).isEmpty();
        assertThat(metadata.getDeclaredMethods()).isEmpty();
    }

    @Test
    void candidatesAreTheRepositoriesInAndBelowTheStoresPackage() {
        assertThat(candidates(source(REFERENCES)))
                .containsExactlyInAnyOrder(ShelfRepository.class.getName(), ArchivedShelfRepository.class.getName());
    }

    @Test
    void aRepositoryWhoseNameTheRegistryHasIsSkipped() {
        registry.registerBeanDefinition("shelfRepository", new RootBeanDefinition(Object.class));

        assertThat(candidates(source(REFERENCES))).containsExactly(ArchivedShelfRepository.class.getName());
    }

    @Test
    void aNameAnotherStoreRegisteredIsNotSkipped() {
        RootBeanDefinition legacyRepository = new RootBeanDefinition(Object.class);
        legacyRepository.setAttribute(StoreRegistrar.STORE_ATTRIBUTE, "legacy");
        registry.registerBeanDefinition("shelfRepository", legacyRepository);

        assertThat(candidates(source(REFERENCES)))
                .as("registering it fails loudly instead of dropping one store's repository")
                .containsExactlyInAnyOrder(ShelfRepository.class.getName(), ArchivedShelfRepository.class.getName());
    }

    @Test
    void registeredRepositoriesBelongToTheStore() {
        List<BeanComponentDefinition> registered = source(REFERENCES).registerRepositories(new TestExtension());

        assertThat(registered)
                .extracting(BeanComponentDefinition::getBeanName)
                .containsExactlyInAnyOrder("shelfRepository", "archivedShelfRepository");
        for (BeanComponentDefinition component : registered) {
            BeanDefinition definition = registry.getBeanDefinition(component.getBeanName());
            assertThat(definition.getAttribute(StoreRegistrar.STORE_ATTRIBUTE)).isEqualTo("books");
            AutowireCandidateQualifier qualifier = ((AbstractBeanDefinition) definition)
                    .getQualifier(Qualifier.class.getName());
            assertThat(qualifier == null ? null : qualifier.getAttribute("value")).isEqualTo("books");
            assertThat(definition.isPrimary()).isFalse();
        }
    }

    @Test
    void aPrimaryStoresRepositoriesArePrimary() {
        StoreDefinition primaryBooks = new StoreDefinition("books", "books", PACKAGE, DataAccess.JPA, true,
                BOOKS.declaredBy());

        new StoreRepositoryConfigurationSource(primaryBooks, TestRepositories.class, REFERENCES,
                new StandardEnvironment(), new DefaultResourceLoader(), registry)
                .registerRepositories(
                        new TestExtension());

        assertThat(registry.getBeanDefinition("shelfRepository").isPrimary()).isTrue();
        assertThat(registry.getBeanDefinition("archivedShelfRepository").isPrimary()).isTrue();
    }

    private StoreRepositoryConfigurationSource source(Map<String, String> references) {
        return new StoreRepositoryConfigurationSource(BOOKS, TestRepositories.class, references,
                new StandardEnvironment(), new DefaultResourceLoader(), registry);
    }

    private static List<String> candidates(StoreRepositoryConfigurationSource source) {
        return source
                .getCandidates(new DefaultResourceLoader())
                .stream()
                .map(BeanDefinition::getBeanClassName)
                .toList();
    }

    /** The attributes an {@code @Enable…Repositories} annotation declares, as a module's own would. */
    @Retention(RetentionPolicy.RUNTIME)
    @interface TestRepositories {

        String[] value() default {};

        String[] basePackages() default {};

        Class<?>[] basePackageClasses() default {};

        Filter[] includeFilters() default {};

        Filter[] excludeFilters() default {};

        String repositoryImplementationPostfix() default "Impl";

        String namedQueriesLocation() default "";

        Key queryLookupStrategy() default Key.CREATE_IF_NOT_FOUND;

        Class<?> repositoryFactoryBeanClass() default RepositoryFactoryBeanSupport.class;

        Class<?> repositoryBaseClass() default DefaultRepositoryBaseClass.class;

        Class<? extends BeanNameGenerator> nameGenerator() default BeanNameGenerator.class;

        String entityManagerFactoryRef() default "entityManagerFactory";

        String transactionManagerRef() default "transactionManager";

        boolean considerNestedRepositories() default false;

        boolean enableDefaultTransactions() default true;

        BootstrapMode bootstrapMode() default BootstrapMode.DEFAULT;

        char escapeCharacter() default '\\';
    }

    /** A module's repository extension, for the registration test. */
    static class TestExtension extends RepositoryConfigurationExtensionSupport {

        @Override
        protected String getModulePrefix() {
            return "test";
        }

        @Override
        public String getRepositoryFactoryBeanClassName() {
            return RepositoryFactoryBeanSupport.class.getName();
        }
    }
}
