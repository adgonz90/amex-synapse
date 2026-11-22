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
package io.americanexpress.synapse.api.grpc.internal.aot;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.core.binding.ComponentRef;

/**
 * Registers lambda hints for the method references in gRPC bindings.
 *
 * <p>
 * Synapse reads each {@link ComponentRef} of a {@code .map}, {@code .context} or {@code .etag} declaration, such as
 * {@code ItemView::title}, by calling {@code writeReplace} on its lambda class, which a native image allows only with
 * metadata for that class. For every {@link GrpcBindings} bean defined by a factory method ({@code @Bean}, static or
 * not), this processor registers a lambda hint for the class that declares that method, matching every
 * {@code ComponentRef} lambda the class creates, in any of its methods, as the REST plugin does for its bindings.
 * Whether GraalVM also needs metadata for {@code writeReplace} itself is not yet verified; the native build in the
 * nightly run checks it.
 *
 * <p>
 * Method references created in another class, such as a helper the {@code @Bean} method calls, are not covered, and
 * neither are beans that no factory method creates (registered with an instance supplier, for example). When the
 * factory method is inherited, the superclass that declares it is registered.
 *
 * <p>
 * The records a binding reaches are its operation's input and output and the records nested in them: the binding
 * checks reject a {@code .map}, {@code .context} or {@code .etag} reference to any other record. The core's
 * {@code OperationTypesAotProcessor} registers all of them, from the operation bean's declared type. This processor
 * never creates the beans, so an operation passed to {@code .unary} that is not a bean, or a bean declared with a
 * less specific type than its class (such as {@code ServiceOperation<?, ?>}), is the case those hints miss. Listed in
 * {@code META-INF/spring/aot.factories}.
 */
public final class GrpcBindingsAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public @Nullable BeanFactoryInitializationAotContribution processAheadOfTime(
            ConfigurableListableBeanFactory beanFactory) {
        Set<Class<?>> declaringClasses = new LinkedHashSet<>();
        for (String name : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition definition = beanFactory.getMergedBeanDefinition(name);
            if (definition.isAbstract() || definition.getFactoryMethodName() == null) {
                continue;
            }
            RegisteredBean bean = RegisteredBean.of(beanFactory, name);
            if (GrpcBindings.class.isAssignableFrom(bean.getBeanClass())) {
                Executable factory = bean.resolveInstantiationDescriptor().executable();
                if (factory instanceof Method method) {
                    declaringClasses.add(declaringClass(method));
                }
            }
        }
        if (declaringClasses.isEmpty()) {
            return null;
        }
        return (generationContext, code) -> declaringClasses
                .forEach(declaring -> generationContext
                        .getRuntimeHints()
                        .reflection()
                        .registerLambda(declaring, lambda -> lambda.withInterfaces(ComponentRef.class)));
    }

    /**
     * The class whose code holds the factory method's body, and so its lambdas: never a CGLIB subclass of a
     * {@code @Configuration} class, and the superclass for an inherited method.
     */
    private static Class<?> declaringClass(Method method) {
        Class<?> userClass = ClassUtils.getUserClass(method.getDeclaringClass());
        Method declared = ReflectionUtils.findMethod(userClass, method.getName(), method.getParameterTypes());
        return declared != null ? declared.getDeclaringClass() : method.getDeclaringClass();
    }
}
