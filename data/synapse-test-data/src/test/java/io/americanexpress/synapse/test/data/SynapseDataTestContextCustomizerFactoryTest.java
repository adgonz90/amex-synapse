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
package io.americanexpress.synapse.test.data;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.internal.store.StoreDefinition;
import io.americanexpress.synapse.data.internal.store.StoreNames;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SynapseDataTestContextCustomizerFactoryTest {

    @Test
    void withoutADeclaredStoreTheMessageSaysSo() {
        SynapseDataTestContextCustomizerFactory.StoreCheck check = new SynapseDataTestContextCustomizerFactory.StoreCheck(
                "books", new Class<?>[0]);

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> check.postProcessBeanFactory(new DefaultListableBeanFactory()))
                .withMessage("@SynapseDataTest(\"books\") names a store that no @SynapseDataStore(\"books\") declares. "
                        + "Declared stores: none. Scanned packages: none.")
                .satisfies(failure -> assertThat(failure.action())
                        .isEqualTo("Declare the store with @SynapseDataStore in the application's packages."));
    }

    /**
     * Minor 9: the hint lists the stores already registered in the bean factory, such as one a configuration imports
     * from another package, as well as those in the scanned packages, and names the packages it scanned.
     */
    @Test
    void theMessageListsRegisteredStoresAndScannedPackages() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("legacyOrdersStoreDefinition", storeDefinition("legacy-orders"));
        SynapseDataTestContextCustomizerFactory.StoreCheck check = new SynapseDataTestContextCustomizerFactory.StoreCheck(
                "books", new Class<?>[]{Test.class});

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> check.postProcessBeanFactory(beanFactory))
                .withMessage("@SynapseDataTest(\"books\") names a store that no @SynapseDataStore(\"books\") declares. "
                        + "Declared stores: legacy-orders. Scanned packages: org.junit.jupiter.api.")
                .satisfies(failure -> assertThat(failure.action())
                        .isEqualTo("Name one of the declared stores, such as @SynapseDataTest(\"legacy-orders\")."));
    }

    private static RootBeanDefinition storeDefinition(String name) {
        RootBeanDefinition definition = new RootBeanDefinition(StoreDefinition.class);
        var arguments = definition.getConstructorArgumentValues();
        arguments.addIndexedArgumentValue(0, name);
        arguments.addIndexedArgumentValue(1, StoreNames.beanPrefix(name));
        arguments.addIndexedArgumentValue(2, "com.example.other");
        arguments.addIndexedArgumentValue(3, DataAccess.JPA);
        arguments.addIndexedArgumentValue(4, false);
        arguments.addIndexedArgumentValue(5, "com.example.other.OtherStore");
        return definition;
    }

    @Test
    void aRegisteredStorePasses() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("legacyOrdersStoreDefinition", new RootBeanDefinition(Object.class));

        assertThatCode(() -> new SynapseDataTestContextCustomizerFactory.StoreCheck("legacy-orders", new Class<?>[0])
                .postProcessBeanFactory(beanFactory)).doesNotThrowAnyException();
    }
}
