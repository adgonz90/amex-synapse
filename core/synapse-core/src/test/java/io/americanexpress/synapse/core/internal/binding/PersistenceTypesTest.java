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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.test.tools.SourceFile;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.data.corefixture.DataCapability;
import io.americanexpress.synapse.data.corefixture.DataRow;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceTypesTest {

    /**
     * Annotations are matched by name, so each test compiles its own copy: none of the persistence libraries is on
     * core's class path.
     */
    @ParameterizedTest
    @ValueSource(strings = {"jakarta.persistence.Entity", "org.springframework.data.mongodb.core.mapping.Document",
            "org.springframework.data.relational.core.mapping.Table"})
    void entityIsAPersistenceType(String annotation) {
        int dot = annotation.lastIndexOf('.');
        SourceFile annotationType = SourceFile
                .of("package " + annotation.substring(0, dot) + ";\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface " + annotation.substring(dot + 1) + " {}\n");
        SourceFile entity = SourceFile.of("package test;\n@" + annotation + "\npublic class BookEntity {}\n");
        SourceFile subclass = SourceFile.of("package test;\npublic class AudioBookEntity extends BookEntity {}\n");

        TestCompiler.forSystem().withSources(annotationType, entity, subclass).compile(compiled -> {
            Class<?> bookEntity = ClassUtils.resolveClassName("test.BookEntity", compiled.getClassLoader());
            assertThat(PersistenceTypes.isPersistenceType(bookEntity)).isTrue();
            assertThat(PersistenceTypes.persistenceReason(bookEntity)).isEqualTo("annotated @" + annotation);
            Class<?> audioBook = ClassUtils.resolveClassName("test.AudioBookEntity", compiled.getClassLoader());
            assertThat(PersistenceTypes.persistenceReason(audioBook)).isEqualTo("annotated @" + annotation);
        });
    }

    @Test
    void recordIsNotAPersistenceType() {
        assertThat(PersistenceTypes.isPersistenceType(Book.class)).isFalse();
        assertThat(PersistenceTypes.persistenceReason(Book.class)).isNull();
        assertThat(PersistenceTypes.isPersistenceType(String.class)).isFalse();
        assertThat(PersistenceTypes.isPersistenceType(Runnable.class)).isFalse();
    }

    @Test
    void typeImplementingASynapseDataInterfaceIsAPersistenceType() {
        assertThat(PersistenceTypes.isPersistenceType(CapableRow.class)).isTrue();
        assertThat(PersistenceTypes.persistenceReason(CapableRow.class))
                .isEqualTo("implements " + DataCapability.class.getName());
    }

    @Test
    void typeExtendingASynapseDataClassIsAPersistenceType() {
        assertThat(PersistenceTypes.isPersistenceType(BookRow.class)).isTrue();
        assertThat(PersistenceTypes.persistenceReason(BookRow.class)).isEqualTo("extends " + DataRow.class.getName());
    }

    record Book(String title) {
    }

    record CapableRow(String id) implements DataCapability {
    }

    static class BookRow extends DataRow {
    }
}
