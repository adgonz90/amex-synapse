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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.data.repository.config.AnnotationRepositoryConfigurationSource;
import org.springframework.data.repository.config.RepositoryConfigurationDelegate;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;
import org.springframework.data.util.Streamable;
import org.springframework.util.ClassUtils;

/**
 * The Spring Data repository configuration of one store, as if the store's class carried the access module's
 * {@code @Enable…Repositories} annotation with the store's references: the repositories are the interfaces in and below
 * the store's package, and every attribute the references don't set keeps the annotation's default.
 * <p>
 * It is an {@link AnnotationRepositoryConfigurationSource} because Spring Data's
 * {@link RepositoryConfigurationDelegate} accepts only annotation or XML sources. A repository whose bean name the
 * registry already has, such as a bean of the store's class, is skipped, unless another store registered that name:
 * then registering it fails, rather than one store losing a repository.
 */
public final class StoreRepositoryConfigurationSource extends AnnotationRepositoryConfigurationSource {

    private final StoreDefinition store;

    private final Environment environment;

    private final ResourceLoader resourceLoader;

    private final BeanDefinitionRegistry registry;

    /**
     * Creates the configuration of a store's repositories.
     *
     * @param store the store
     * @param annotation the access module's {@code @Enable…Repositories} annotation, whose defaults apply
     * @param references the attributes that name the store's beans, such as
     *        {@code entityManagerFactoryRef=booksEntityManagerFactory}
     * @param environment the environment
     * @param resourceLoader the resource loader
     * @param registry the registry
     * @throws IllegalArgumentException when the annotation declares no attribute of a reference's name
     */
    public StoreRepositoryConfigurationSource(StoreDefinition store, Class<? extends Annotation> annotation,
            Map<String, String> references, Environment environment, ResourceLoader resourceLoader,
            BeanDefinitionRegistry registry) {
        super(new StoreMetadata(store.declaredBy(), MergedAnnotations.of(List.of(annotation(annotation, references)))),
                annotation, resourceLoader, environment, registry, null);
        this.store = store;
        this.environment = environment;
        this.resourceLoader = resourceLoader;
        this.registry = registry;
    }

    /**
     * Returns the store's package: its repositories are in it and below.
     *
     * @return the store's package
     */
    @Override
    public Streamable<String> getBasePackages() {
        return Streamable.of(store.basePackage());
    }

    /**
     * Returns the repository interfaces to register, leaving out those whose bean name the registry has.
     *
     * @param loader the resource loader to scan with
     * @return the candidates
     */
    @Override
    public Streamable<BeanDefinition> getCandidates(ResourceLoader loader) {
        return super.getCandidates(loader).filter(candidate -> isToRegister(generateBeanName(candidate)));
    }

    /**
     * Describes the declaration, for bean definitions and error messages.
     *
     * @return such as {@code @SynapseDataStore("books") on BooksStore}
     */
    @Override
    public String getResourceDescription() {
        return "@SynapseDataStore(\"" + store.name() + "\") on " + ClassUtils.getShortName(store.declaredBy());
    }

    /**
     * Registers the store's repositories with Spring Data's {@link RepositoryConfigurationDelegate}. As the store's
     * other beans, each one is qualified with the store name and is primary exactly when the store is.
     *
     * @param extension the access module's repository extension
     * @return the registered repositories
     */
    public List<BeanComponentDefinition> registerRepositories(RepositoryConfigurationExtension extension) {
        List<BeanComponentDefinition> registered = new RepositoryConfigurationDelegate(this, resourceLoader,
                environment).registerRepositoriesIn(registry, extension);
        for (BeanComponentDefinition component : registered) {
            BeanDefinition definition = component.getBeanDefinition();
            definition.setAttribute(StoreRegistrar.STORE_ATTRIBUTE, store.name());
            // As for the store's other beans: a single store's are made primary later, by StoreDefinitionsPostProcessor.
            definition.setPrimary(store.primary());
            if (definition instanceof AbstractBeanDefinition qualified) {
                qualified.addQualifier(new AutowireCandidateQualifier(Qualifier.class, store.name()));
            }
        }
        return registered;
    }

    private boolean isToRegister(String beanName) {
        if (!registry.containsBeanDefinition(beanName)) {
            return true;
        }
        Object owner = registry.getBeanDefinition(beanName).getAttribute(StoreRegistrar.STORE_ATTRIBUTE);
        return owner != null && !store.name().equals(owner);
    }

    private static MergedAnnotation<?> annotation(Class<? extends Annotation> type, Map<String, String> references) {
        Method[] attributes = type.getDeclaredMethods();
        for (String name : references.keySet()) {
            if (Arrays.stream(attributes).noneMatch(attribute -> attribute.getName().equals(name))) {
                throw new IllegalArgumentException(
                        "The repository annotation " + type.getName() + " has no attribute '" + name + "'.");
            }
        }
        return MergedAnnotation.of(null, null, type, references);
    }

    /**
     * The metadata of the store's class as Spring Data reads it: its name, and the access module's annotation.
     */
    private record StoreMetadata(String className, MergedAnnotations annotations) implements AnnotationMetadata {

        @Override
        public String getClassName() {
            return className;
        }

        @Override
        public MergedAnnotations getAnnotations() {
            return annotations;
        }

        @Override
        public boolean isInterface() {
            return false;
        }

        @Override
        public boolean isAnnotation() {
            return false;
        }

        @Override
        public boolean isAbstract() {
            return false;
        }

        @Override
        public boolean isFinal() {
            return false;
        }

        @Override
        public boolean isIndependent() {
            return true;
        }

        @Override
        public @Nullable String getEnclosingClassName() {
            return null;
        }

        @Override
        public @Nullable String getSuperClassName() {
            return null;
        }

        @Override
        public String[] getInterfaceNames() {
            return new String[0];
        }

        @Override
        public String[] getMemberClassNames() {
            return new String[0];
        }

        @Override
        public Set<MethodMetadata> getAnnotatedMethods(String annotationName) {
            return Set.of();
        }

        @Override
        public Set<MethodMetadata> getDeclaredMethods() {
            return Set.of();
        }
    }
}
