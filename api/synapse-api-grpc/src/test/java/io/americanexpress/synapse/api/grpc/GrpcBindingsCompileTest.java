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
package io.americanexpress.synapse.api.grpc;

import org.junit.jupiter.api.Test;
import org.springframework.core.test.tools.CompilationException;
import org.springframework.core.test.tools.SourceFile;
import org.springframework.core.test.tools.TestCompiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * gRPC binding mistakes that the compiler catches, before the application ever starts.
 */
class GrpcBindingsCompileTest {

    private static final SourceFile REQUEST = SourceFile.of("""
            package test;
            public record GetItemRequest(String id) {}
            """);

    private static final SourceFile ITEM = SourceFile.of("""
            package test;
            public record ItemRecord(String id, String name) {}
            """);

    private static final SourceFile OPERATION = SourceFile.of("""
            package test;
            public class GetItemOperation
                    implements io.americanexpress.synapse.core.operation.QueryOperation<GetItemRequest, ItemRecord> {
                public ItemRecord execute(GetItemRequest input) { return new ItemRecord(input.id(), "item"); }
            }
            """);

    @Test
    void bindingWithAnExistingComponentCompiles() {
        SourceFile bindings = bindings("ItemRecord::name");

        TestCompiler
                .forSystem()
                .withSources(REQUEST, ITEM, OPERATION, bindings)
                .compile(compiled -> assertThat(compiled.getAllCompiledClasses()).hasSizeGreaterThanOrEqualTo(4));
    }

    @Test
    void renamedComponentBreaksCompilation() {
        SourceFile bindings = bindings("ItemRecord::nam");

        assertThatExceptionOfType(CompilationException.class)
                .isThrownBy(() -> TestCompiler
                        .forSystem()
                        .withSources(REQUEST, ITEM, OPERATION, bindings)
                        .compile(compiled -> {
                        }))
                .withMessageContaining("nam");
    }

    @Test
    void bindingABusinessOperationDoesNotCompile() {
        SourceFile bindings = SourceFile.of("""
                package test;
                import io.americanexpress.synapse.api.grpc.GrpcBindings;
                import io.americanexpress.synapse.core.operation.BusinessOperation;
                import io.grpc.MethodDescriptor;
                import io.grpc.ServiceDescriptor;
                class Bindings {
                    GrpcBindings bindings(ServiceDescriptor service, MethodDescriptor<?, ?> method,
                            BusinessOperation<String, String> b) {
                        return GrpcBindings.service(service).unary(method, b).build();
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
                import io.americanexpress.synapse.api.grpc.GrpcBindings;
                import io.grpc.MethodDescriptor;
                import io.grpc.ServiceDescriptor;
                class Bindings {
                    GrpcBindings bindings(ServiceDescriptor service, MethodDescriptor<?, ?> getItem,
                            GetItemOperation operation) {
                        return GrpcBindings.service(service).unary(getItem, operation).map("name", %s).build();
                    }
                }
                """.formatted(reference));
    }
}
