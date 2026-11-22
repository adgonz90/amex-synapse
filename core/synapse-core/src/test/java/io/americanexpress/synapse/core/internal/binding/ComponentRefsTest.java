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

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.binding.ComponentRef;
import io.americanexpress.synapse.core.internal.binding.marked.Counted;
import io.americanexpress.synapse.core.internal.binding.marked.GetBookRequest;
import io.americanexpress.synapse.core.internal.binding.unmarked.Loose;
import io.americanexpress.synapse.core.internal.binding.unmarked.LooseCounted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ComponentRefsTest {

    @Test
    void resolvesAccessorMethodReference() {
        ResolvedComponent resolved = ComponentRefs.resolve(GetBookRequest::id);

        assertThat(resolved.recordType()).isEqualTo(GetBookRequest.class);
        assertThat(resolved.name()).isEqualTo("id");
        assertThat(resolved.type()).isEqualTo(UUID.class);
        assertThat(resolved.genericType()).isEqualTo(UUID.class);
        assertThat(resolved.nullable()).isFalse();
    }

    @Test
    void readsJSpecifyNullness() {
        assertThat(ComponentRefs.resolve(GetBookRequest::expand).nullable()).isTrue();
        assertThat(ComponentRefs.resolve(Loose::a).nullable()).isTrue();
    }

    @Test
    void rejectsLambda() {
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> ComponentRefs.resolve((GetBookRequest r) -> r.id()))
                .satisfies(e -> assertThat(e.description()).contains("method reference to a record component"));
    }

    @Test
    void rejectsNonAccessorMethod() {
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> ComponentRefs.resolve(GetBookRequest::toString))
                .satisfies(e -> assertThat(e.description()).contains("toString"));
    }

    @Test
    void listsComponentsInDeclarationOrder() {
        List<ResolvedComponent> components = ComponentRefs.components(GetBookRequest.class);

        assertThat(components).extracting(ResolvedComponent::name).containsExactly("id", "expand");
    }

    @Test
    void rejectsNonRecord() {
        assertThatIllegalArgumentException().isThrownBy(() -> ComponentRefs.components(String.class));
    }

    @Test
    void primitiveComponentsAreNeverNullable() {
        assertThat(ComponentRefs.resolve(Counted::n).nullable()).isFalse();
        assertThat(ComponentRefs.resolve(LooseCounted::n).nullable()).isFalse();
    }

    @Test
    void rejectsClassImplementingComponentRef() {
        ComponentRef<GetBookRequest, UUID> ref = new ComponentRef<>() {
            @Override
            public UUID apply(GetBookRequest request) {
                return request.id();
            }
        };

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> ComponentRefs.resolve(ref))
                .satisfies(e -> assertThat(e.description()).contains("method reference to a record component"));
    }

    @Test
    void rejectsBoundMethodReference() {
        GetBookRequest instance = new GetBookRequest(UUID.randomUUID(), null);
        ComponentRef<Object, Boolean> bound = instance::equals;

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> ComponentRefs.resolve(bound))
                .satisfies(e -> assertThat(e.description()).contains("bound"));
    }

    @Test
    void namesTheCauseWhenReadingTheReferenceFails() {
        ComponentRef<GetBookRequest, UUID> failing = new FailingRef();

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> ComponentRefs.resolve(failing))
                .satisfies(e -> assertThat(e.description()).contains("IllegalStateException"));
    }

    @Test
    void namesTheCauseWhenTheReferenceIsNotALambda() {
        ComponentRef<GetBookRequest, UUID> plain = new PlainRef();

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> ComponentRefs.resolve(plain))
                .satisfies(e -> assertThat(e.description()).contains("NoSuchMethodException"));
    }

    private static final class PlainRef implements ComponentRef<GetBookRequest, UUID> {

        @Override
        public UUID apply(GetBookRequest request) {
            return request.id();
        }
    }

    private static final class FailingRef implements ComponentRef<GetBookRequest, UUID> {

        @Override
        public UUID apply(GetBookRequest request) {
            return request.id();
        }

        @SuppressWarnings("unused")
        private Object writeReplace() {
            throw new IllegalStateException("boom");
        }
    }
}
