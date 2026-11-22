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
package io.americanexpress.synapse.core.internal.binding;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

class OperationTypesTest {

    @Test
    void plainOperationIsItsOwnClass() {
        assertThat(OperationTypes.userClassOf(new GetBookOperation())).isEqualTo(GetBookOperation.class);
    }

    @Test
    void classProxyResolvesToTheUserClass() {
        ProxyFactory factory = new ProxyFactory(new GetBookOperation());
        factory.setProxyTargetClass(true);
        Object proxy = factory.getProxy();

        assertThat(proxy.getClass()).isNotEqualTo(GetBookOperation.class);
        assertThat(OperationTypes.userClassOf(proxy)).isEqualTo(GetBookOperation.class);
    }

    @Test
    void interfaceProxyResolvesToTheTargetClass() {
        ProxyFactory factory = new ProxyFactory(new GetBookOperation());
        factory.setInterfaces(QueryOperation.class);
        Object proxy = factory.getProxy();

        assertThat(proxy).isNotInstanceOf(GetBookOperation.class);
        assertThat(OperationTypes.userClassOf(proxy)).isEqualTo(GetBookOperation.class);
    }

    @Test
    void cglibSubclassResolvesToTheUserClass() {
        assertThat(OperationTypes.userClassOf(new ListBooksOperation$$SpringCGLIB$$0()))
                .isEqualTo(ListBooksOperation.class);
    }

    public static class GetBookOperation implements QueryOperation<String, String> {
        @Override
        public String execute(String input) {
            return input;
        }
    }

    static class ListBooksOperation implements QueryOperation<String, String> {
        @Override
        public String execute(String input) {
            return input;
        }
    }

    /** Named like a CGLIB subclass, which Spring's proxies are; a real proxy of this class would take the name. */
    static class ListBooksOperation$$SpringCGLIB$$0 extends ListBooksOperation {
    }
}
