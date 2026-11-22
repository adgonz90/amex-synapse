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
package io.americanexpress.synapse.api.rest.internal.edge;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderMaskingRequestTest {

    private static final String DATE = "Wed, 21 Oct 2015 07:28:00 GMT";

    private static final long DATE_MILLIS = 1_445_412_480_000L;

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Hidden-Count", "7");
        request.addHeader("X-Hidden-Date", DATE);
        request.addHeader("X-Count", "3");
        request.addHeader("X-Date", DATE);
        return request;
    }

    @Test
    void hiddenIntHeaderIsAbsent() {
        HeaderMaskingRequest masked = new HeaderMaskingRequest(request(), Set.of("x-hidden-count"), null);

        assertThat(masked.getIntHeader("X-Hidden-Count")).isEqualTo(-1);
        assertThat(masked.getIntHeader("X-Count")).isEqualTo(3);
        assertThat(masked.getIntHeader("X-Missing")).isEqualTo(-1);
    }

    @Test
    void hiddenDateHeaderIsAbsent() {
        HeaderMaskingRequest masked = new HeaderMaskingRequest(request(), Set.of("X-HIDDEN-DATE"), null);

        assertThat(masked.getDateHeader("X-Hidden-Date")).isEqualTo(-1L);
        assertThat(masked.getDateHeader("X-Date")).isEqualTo(DATE_MILLIS);
        assertThat(masked.getDateHeader("X-Missing")).isEqualTo(-1L);
    }
}
