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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class StoreNamesTest {

    @ParameterizedTest
    @CsvSource({"books, true", "legacy-orders, true", "b2, true", "a-2b, true", "Books, false", "2b, false",
            "legacy_orders, false", "-x, false", "x-, false", "a--b, false", "'', false", "legacy.orders, false"})
    void validNames(String name, boolean valid) {
        assertThat(StoreNames.isValid(name)).isEqualTo(valid);
    }

    @Test
    void beanPrefixIsLowerCamel() {
        assertThat(StoreNames.beanPrefix("legacy-orders")).isEqualTo("legacyOrders");
        assertThat(StoreNames.beanPrefix("books")).isEqualTo("books");
        assertThat(StoreNames.beanPrefix("a-b-c2")).isEqualTo("aBC2");
        assertThat(StoreNames.beanPrefix("a-2b")).isEqualTo("a2b");
    }

    @Test
    void environmentVariableDropsDashesAndUpperCases() {
        assertThat(StoreNames.environmentVariable("legacy-orders", "password"))
                .isEqualTo("SYNAPSE_DATA_STORES_LEGACYORDERS_PASSWORD");
        assertThat(StoreNames.environmentVariable("books", "username")).isEqualTo("SYNAPSE_DATA_STORES_BOOKS_USERNAME");
        assertThat(StoreNames.environmentVariable("books", "hikari.maximum-pool-size"))
                .isEqualTo("SYNAPSE_DATA_STORES_BOOKS_HIKARI_MAXIMUMPOOLSIZE");
    }

    @Test
    void namesThatBindTheSamePropertiesAreTheSameStore() {
        assertThat(StoreNames.sameStore("legacy-orders", "legacyorders")).isTrue();
        assertThat(StoreNames.sameStore("books", "books")).isTrue();
        assertThat(StoreNames.sameStore("books", "book")).isFalse();
    }
}
