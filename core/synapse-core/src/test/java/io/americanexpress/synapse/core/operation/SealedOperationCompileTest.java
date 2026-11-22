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

import org.junit.jupiter.api.Test;
import org.springframework.core.test.tools.CompilationException;
import org.springframework.core.test.tools.SourceFile;
import org.springframework.core.test.tools.TestCompiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SealedOperationCompileTest {

    @Test
    void operationThatIsNeitherQueryNorCommandDoesNotCompile() {
        SourceFile bad = SourceFile.of("""
                package test;
                class Bad implements io.americanexpress.synapse.core.operation.ServiceOperation<String,String> {
                    public String execute(String i) { return i; }
                }
                """);
        assertThatThrownBy(() -> TestCompiler.forSystem().compile(bad, compiled -> {
        })).isInstanceOf(CompilationException.class).hasMessageContaining("sealed");
    }

    @Test
    void queryOperationCompiles() {
        SourceFile good = SourceFile.of("""
                package test;
                class Good implements io.americanexpress.synapse.core.operation.QueryOperation<String,String> {
                    public String execute(String i) { return i; }
                }
                """);
        TestCompiler.forSystem().compile(good, compiled -> assertThat(compiled.getAllCompiledClasses()).hasSize(1));
    }
}
