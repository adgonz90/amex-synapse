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

import io.grpc.Status;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.error.ErrorCategory;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcErrorsTest {

    @Test
    void methodNotFoundIsUnimplemented() {
        GrpcErrors error = GrpcErrors.METHOD_NOT_FOUND;

        assertThat(error.code()).isEqualTo(error.name()).isEqualTo("METHOD_NOT_FOUND");
        assertThat(error.messageTemplate()).isEqualTo("No method matches the call");
        assertThat(error.category()).isEqualTo(ErrorCategory.NOT_FOUND);
        assertThat(error.status()).isEqualTo(Status.Code.UNIMPLEMENTED);
        assertThat(GrpcErrors.values()).hasSize(1);
    }
}
