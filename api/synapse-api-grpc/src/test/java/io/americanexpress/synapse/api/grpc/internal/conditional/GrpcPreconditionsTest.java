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
package io.americanexpress.synapse.api.grpc.internal.conditional;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcPreconditionsTest {

    @Test
    void staleVersionWithAnEtagIsPreconditionFailed() {
        SynapseException stale = new SynapseException(SynapseErrors.STALE_VERSION);

        SynapseException translated = GrpcPreconditions.translate(true, stale);

        assertThat(translated.details()).isEqualTo(SynapseErrors.PRECONDITION_FAILED);
        assertThat(translated).hasCause(stale);
    }

    @Test
    void staleVersionWithoutAnEtagStays() {
        SynapseException stale = new SynapseException(SynapseErrors.STALE_VERSION);

        assertThat(GrpcPreconditions.translate(false, stale)).isSameAs(stale);
    }

    @Test
    void otherFailuresAreUnchanged() {
        for (SynapseErrors code : List
                .of(SynapseErrors.ALREADY_EXISTS, SynapseErrors.NOT_FOUND,
                        SynapseErrors.PRECONDITION_FAILED, SynapseErrors.VALIDATION_FAILED)) {
            SynapseException failure = new SynapseException(code);

            assertThat(GrpcPreconditions.translate(true, failure)).as(code.name()).isSameAs(failure);
            assertThat(GrpcPreconditions.translate(false, failure)).as(code.name()).isSameAs(failure);
        }
    }
}
