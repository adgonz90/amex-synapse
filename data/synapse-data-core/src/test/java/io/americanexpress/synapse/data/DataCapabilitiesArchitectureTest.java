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
package io.americanexpress.synapse.data;

import com.example.arch.bad.datatype.data.BookRecord;
import com.example.arch.bad.datatype.operation.GetBookOperation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.test.architecture.SynapseArchitecture;

import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.NO_ENTITIES_IN_OPERATION_SIGNATURES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Synapse architecture rules see the real data capability types: synapse-test matches them by package
 * ({@code io.americanexpress.synapse.data..}), so this test lives here rather than in synapse-test, which can't
 * depend on synapse-data-core.
 */
class DataCapabilitiesArchitectureTest {

    private static final String ROOT = "com.example.arch.bad.datatype";

    @Test
    void synapseDataCapabilitiesMarkEntities() {
        JavaClasses classes = new ClassFileImporter().importPackages(ROOT);

        // Every other rule passes on this fixture, so the one violation is NO_ENTITIES_IN_OPERATION_SIGNATURES'.
        assertThat(SynapseArchitecture.forRoots(ROOT).asArchRule().evaluate(classes).getFailureReport().getDetails())
                .singleElement()
                .asString()
                .contains(GetBookOperation.class.getName())
                .contains("has output type " + BookRecord.class.getName())
                .contains("implements " + Identifiable.class.getName());
        assertThat(SynapseArchitecture
                .forRoots(ROOT)
                .except(NO_ENTITIES_IN_OPERATION_SIGNATURES, "the fixture's violation")
                .asArchRule()
                .evaluate(classes)
                .hasViolation()).isFalse();
    }
}
