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
package io.americanexpress.synapse.core.internal.pipeline;

import java.lang.reflect.Method;
import java.util.function.Supplier;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.util.function.SingletonSupplier;

/**
 * An advisor that applies one of the pipeline's interceptors to the {@code execute} methods of the beans implementing
 * a contract. The interceptor is resolved on the first call, so creating the advisor (which Spring's auto-proxy
 * creator does before it proxies the first bean) does not create the pipeline and its dependencies early.
 */
abstract sealed class PipelineAdvisor extends AbstractPointcutAdvisor permits OperationAdvisor, BusinessAdvisor {

    private static final long serialVersionUID = 1L;

    private final transient Pointcut pointcut;

    private final transient Advice advice;

    PipelineAdvisor(Class<?> contract, Supplier<MethodInterceptor> interceptor, int order) {
        this.pointcut = new ExecutePointcut(contract);
        this.advice = new LazyInterceptor(SingletonSupplier.of(interceptor));
        setOrder(order);
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return advice;
    }

    /**
     * Matches the single-argument {@code execute} methods of classes implementing the contract. The interceptor then
     * runs the pipeline only for the method that implements the contract (an overload passes straight through).
     */
    private static final class ExecutePointcut extends StaticMethodMatcherPointcut {

        private final Class<?> contract;

        ExecutePointcut(Class<?> contract) {
            this.contract = contract;
            setClassFilter(contract::isAssignableFrom);
        }

        @Override
        public boolean matches(Method method, Class<?> targetClass) {
            return contract.isAssignableFrom(targetClass) && "execute".equals(method.getName())
                    && method.getParameterCount() == 1;
        }
    }

    /** Delegates to the interceptor, resolving it on the first call. */
    private record LazyInterceptor(SingletonSupplier<MethodInterceptor> interceptor) implements MethodInterceptor {

        @Override
        public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
            return interceptor.obtain().invoke(invocation);
        }
    }
}
