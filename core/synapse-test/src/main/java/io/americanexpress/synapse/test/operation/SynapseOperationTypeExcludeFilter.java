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
package io.americanexpress.synapse.test.operation;

import java.util.Set;

import org.springframework.boot.test.context.filter.annotation.AnnotationCustomizableTypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan.Filter;

import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Keeps component scanning to {@link ServiceOperation} and {@link BusinessOperation} beans for
 * {@link SynapseOperationTest}. Every other component, such as controllers and plain services, is left out.
 */
public final class SynapseOperationTypeExcludeFilter extends AnnotationCustomizableTypeExcludeFilter {

    private static final Set<Class<?>> DEFAULT_INCLUDES = Set.of(ServiceOperation.class, BusinessOperation.class);

    @Override
    protected boolean hasAnnotation() {
        return true;
    }

    @Override
    protected Filter[] getFilters(FilterType type) {
        return new Filter[0];
    }

    @Override
    protected boolean isUseDefaultFilters() {
        return true;
    }

    @Override
    protected Set<Class<?>> getDefaultIncludes() {
        return DEFAULT_INCLUDES;
    }

    @Override
    protected Set<Class<?>> getComponentIncludes() {
        return Set.of();
    }
}
