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
package io.americanexpress.synapse.test.aot;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.assertj.core.api.AbstractAssert;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * Assertions on {@link RuntimeHints}. Each failure names the hint that is missing.
 */
public final class RuntimeHintsAssert extends AbstractAssert<RuntimeHintsAssert, RuntimeHints> {

    RuntimeHintsAssert(RuntimeHints actual) {
        super(actual, RuntimeHintsAssert.class);
    }

    /**
     * Verifies that reflection is registered for the type.
     *
     * @param type the type
     * @return this assertion
     */
    public RuntimeHintsAssert registersReflection(Class<?> type) {
        isNotNull();
        if (!RuntimeHintsPredicates.reflection().onType(type).test(actual)) {
            failWithMessage("Expected a reflection hint for <%s> but none is registered", type.getName());
        }
        return this;
    }

    /**
     * Verifies that reflection is registered for the type with every member category.
     *
     * @param type the type
     * @param categories the member categories
     * @return this assertion
     */
    public RuntimeHintsAssert registersReflection(Class<?> type, MemberCategory... categories) {
        isNotNull();
        if (!RuntimeHintsPredicates.reflection().onType(type).withMemberCategories(categories).test(actual)) {
            failWithMessage("Expected a reflection hint for <%s> with member categories <%s> but none is registered",
                    type.getName(), Arrays.toString(categories));
        }
        return this;
    }

    /**
     * Verifies that a classpath resource is registered.
     *
     * @param resource the resource name or path
     * @return this assertion
     */
    public RuntimeHintsAssert registersResource(String resource) {
        isNotNull();
        if (!RuntimeHintsPredicates.resource().forResource(resource).test(actual)) {
            failWithMessage("Expected a resource hint for <%s> but none is registered", resource);
        }
        return this;
    }

    /**
     * Verifies that a JDK proxy is registered for the interfaces.
     *
     * @param interfaces the proxied interfaces, in order
     * @return this assertion
     */
    public RuntimeHintsAssert registersJdkProxy(Class<?>... interfaces) {
        isNotNull();
        if (!RuntimeHintsPredicates.proxies().forInterfaces(interfaces).test(actual)) {
            failWithMessage("Expected a JDK proxy hint for <%s> but none is registered",
                    Arrays.stream(interfaces).map(Class::getName).collect(Collectors.joining(", ")));
        }
        return this;
    }
}
