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
package io.americanexpress.synapse.core.operation;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationNamesTest {

    @ParameterizedTest
    @CsvSource({"GetBookOperation, get-book", "SearchBooksOperation, search-books", "GetISBNOperation, get-isbn",
            "ISBNLookupOperation, isbn-lookup", "GetV2BookOperation, get-v2-book",
            "PriceCalculator, price-calculator", "Operation, operation"})
    void derivesKebabCaseNameFromSimpleClassName(String simpleName, String expected) throws Exception {
        Class<?> type = Class.forName(Fixtures.class.getName() + "$" + simpleName);
        assertThat(OperationNames.nameOf(type)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"GetBook, get-book", "SearchBooks, search-books", "GetHTTPStatus, get-http-status", "Ping, ping",
            "GetBookOperation, get-book-operation"})
    void methodNamesMapToOperationNames(String method, String operation) {
        assertThat(OperationNames.ofMethodName(method)).isEqualTo(operation);
    }

    @Test
    void annotationOverridesDerivedName() {
        assertThat(OperationNames.nameOf(Renamed.class)).isEqualTo("fetch-book");
    }

    @Test
    void rejectsInvalidAnnotatedNameOnClass() {
        assertThatThrownBy(() -> OperationNames.nameOf(BadlyNamed.class))
                .isInstanceOfSatisfying(
                        SynapseConfigurationException.class,
                        e -> assertThat(e.description()).contains("Get_Book").contains(BadlyNamed.class.getName()));
    }

    @Test
    void usesUserClassForCglibSubclass() {
        assertThat(OperationNames.nameOf(Fixtures.GetBookOperation.class)).isEqualTo("get-book");
        assertThat(OperationNames.nameOf(Fixtures.GetBookOperation$$SpringCGLIB$$0.class)).isEqualTo("get-book");
    }

    @Test
    void rejectsInvalidAnnotatedName() {
        assertThat(OperationNames.isValidName("Get_Book")).isFalse();
        assertThat(OperationNames.isValidName("get-book")).isTrue();
        assertThat(OperationNames.isValidName("a")).isTrue();
        assertThat(OperationNames.isValidName("-a")).isFalse();
        assertThat(OperationNames.isValidName("a-")).isFalse();
        assertThat(OperationNames.isValidName("a--b")).isFalse();
        assertThat(OperationNames.isValidName("1a")).isFalse();
        assertThat(OperationNames.isValidName("")).isFalse();
    }

    @Test
    void groupIsReadFromAnnotation() {
        assertThat(OperationNames.groupOf(Grouped.class)).isEqualTo(Optional.of("books"));
        assertThat(OperationNames.groupOf(Renamed.class)).isEmpty();
    }

    @Test
    void kindFollowsSealedSubtype() {
        assertThat(OperationKind.of(Fixtures.GetBookOperation.class)).isEqualTo(OperationKind.QUERY);
        assertThat(OperationKind.of(Fixtures.SaveBookOperation.class)).isEqualTo(OperationKind.COMMAND);
        assertThat(OperationKind.QUERY.tagValue()).isEqualTo("query");
        assertThat(OperationKind.COMMAND.tagValue()).isEqualTo("command");
    }

    @Test
    void kindRejectsClassImplementingBoth() {
        assertThatThrownBy(() -> OperationKind.of(Both.class))
                .isInstanceOfSatisfying(
                        SynapseConfigurationException.class,
                        e -> assertThat(e.description()).contains(Both.class.getName()));
    }

    @Test
    void kindRejectsClassImplementingNeither() {
        assertThatThrownBy(() -> OperationKind.of(String.class))
                .isInstanceOfSatisfying(
                        SynapseConfigurationException.class,
                        e -> assertThat(e.description()).contains("String").contains("neither"));
    }

    @OperationName("fetch-book")
    static class Renamed {
    }

    @OperationName("Get_Book")
    static class BadlyNamed {
    }

    @OperationGroup("books")
    static class Grouped {
    }

    static class Both implements QueryOperation<String, String>, CommandOperation<String, String> {
        @Override
        public String execute(String input) {
            return input;
        }
    }

    static class Fixtures {

        static class GetBookOperation implements QueryOperation<String, String> {
            @Override
            public String execute(String input) {
                return input;
            }
        }

        static class GetBookOperation$$SpringCGLIB$$0 extends GetBookOperation {
        }

        static class SaveBookOperation implements CommandOperation<String, String> {
            @Override
            public String execute(String input) {
                return input;
            }
        }

        static class SearchBooksOperation {
        }

        static class GetISBNOperation {
        }

        static class ISBNLookupOperation {
        }

        static class GetV2BookOperation {
        }

        static class PriceCalculator {
        }

        static class Operation {
        }
    }
}
