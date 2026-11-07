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
package io.americanexpress.synapse.core.internal.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceHeadersTest {

    @Test
    void traceHeadersCoverW3cAndB3() {
        assertThat(TraceHeaders.NAMES)
                .containsExactly("traceparent", "tracestate", "b3", "X-B3-TraceId", "X-B3-SpanId",
                        "X-B3-ParentSpanId", "X-B3-Sampled", "X-B3-Flags");
    }
}
