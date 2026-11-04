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
package io.americanexpress.synapse.api.rest;

import org.junit.jupiter.api.Test;
import org.springframework.core.test.tools.CompilationException;
import org.springframework.core.test.tools.SourceFile;
import org.springframework.core.test.tools.TestCompiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Binding mistakes that the compiler catches, before the application ever starts.
 */
class BindingsCompileTest {

    private static final SourceFile REQUEST = SourceFile.of("""
            package test;
            public record GetBookRequest(java.util.UUID id) {}
            """);

    private static final SourceFile OPERATION = SourceFile.of("""
            package test;
            public class GetBookOperation
                    implements io.americanexpress.synapse.core.operation.QueryOperation<GetBookRequest, String> {
                public String execute(GetBookRequest input) { return "book"; }
            }
            """);

    @Test
    void bindingWithAnExistingComponentCompiles() {
        SourceFile bindings = bindings("GetBookRequest::id");

        TestCompiler
                .forSystem()
                .withSources(REQUEST, OPERATION, bindings)
                .compile(compiled -> assertThat(compiled.getAllCompiledClasses()).hasSizeGreaterThanOrEqualTo(3));
    }

    @Test
    void renamedComponentBreaksCompilation() {
        SourceFile bindings = bindings("GetBookRequest::idd");

        assertThatExceptionOfType(CompilationException.class)
                .isThrownBy(
                        () -> TestCompiler.forSystem().withSources(REQUEST, OPERATION, bindings).compile(compiled -> {
                        }))
                .withMessageContaining("idd");
    }

    @Test
    void bindingABusinessOperationDoesNotCompile() {
        SourceFile bindings = SourceFile.of("""
                package test;
                import io.americanexpress.synapse.api.rest.RestBindings;
                import io.americanexpress.synapse.core.operation.BusinessOperation;
                class Bindings {
                    RestBindings bindings(BusinessOperation<String, String> b) {
                        return RestBindings.builder().get("/x", b).build();
                    }
                }
                """);

        assertThatExceptionOfType(CompilationException.class)
                .isThrownBy(() -> TestCompiler.forSystem().withSources(bindings).compile(compiled -> {
                }))
                .withMessageContaining("BusinessOperation")
                .withMessageContaining("BusinessOperation<java.lang.String,java.lang.String> cannot be converted to "
                        + "io.americanexpress.synapse.core.operation.ServiceOperation<I,O>");
    }

    private static SourceFile bindings(String reference) {
        return SourceFile.of("""
                package test;
                import io.americanexpress.synapse.api.rest.RestBindings;
                class Bindings {
                    RestBindings bindings(GetBookOperation getBook) {
                        return RestBindings.builder().get("/books/{bookId}", getBook).path("bookId", %s).build();
                    }
                }
                """.formatted(reference));
    }
}
