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
package io.americanexpress.synapse.api.rest.internal.error;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.core.error.SynapseErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KeptStatusExceptionTest {

    @Test
    void keptStatusesBelongToTheirCodes() {
        assertThat(new KeptStatusException(RestErrors.CLIENT_ERROR, 422, Map.of(), null, null).status()).isEqualTo(422);
        assertThat(new KeptStatusException(RestErrors.SERVER_ERROR, 502, Map.of(), null, null).status()).isEqualTo(502);
        // A table code carries headers and a reason, never a status of its own.
        assertThat(new KeptStatusException(SynapseErrors.UNAVAILABLE, null, Map.of("Retry-After", "5"), "busy", null)
                .status()).isNull();
    }

    @Test
    void anyOtherCodeWithAStatusIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KeptStatusException(SynapseErrors.UNAVAILABLE, 422, Map.of(), null, null))
                .withMessageContaining("UNAVAILABLE");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KeptStatusException(RestErrors.NOT_ACCEPTABLE, 406, Map.of(), null, null));
    }

    @Test
    void theTwoCodesNeedAStatusOfTheirClass() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KeptStatusException(RestErrors.CLIENT_ERROR, null, Map.of(), null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KeptStatusException(RestErrors.CLIENT_ERROR, 502, Map.of(), null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KeptStatusException(RestErrors.SERVER_ERROR, 422, Map.of(), null, null));
        // 500 is INTERNAL, never kept.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KeptStatusException(RestErrors.SERVER_ERROR, 500, Map.of(), null, null));
    }
}
