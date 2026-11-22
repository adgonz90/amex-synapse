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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.americanexpress.synapse.core.internal.context.TraceHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class InboundPropagationFilterTest {

    /** Ordered, as the auto-configuration builds it: candidates are read in this order. */
    private static final Set<String> SYNAPSE_FIELDS = new LinkedHashSet<>(List.of("correlationId", "tenant"));

    private static final Set<String> REMOTE_FIELDS = Set.of("correlationId", "tenant", "partner");

    private @Nullable HttpServletRequest downstream;

    private HttpServletRequest filter(boolean trusted, MockHttpServletRequest request) throws Exception {
        new InboundPropagationFilter(trusted, SYNAPSE_FIELDS, REMOTE_FIELDS)
                .doFilter(request, new MockHttpServletResponse(),
                        (seen, response) -> downstream = (HttpServletRequest) seen);
        return Objects.requireNonNull(downstream, "the chain was not called");
    }

    private static List<String> headerNames(HttpServletRequest request) {
        return Collections.list(request.getHeaderNames());
    }

    private static List<String> headers(HttpServletRequest request, String name) {
        return Collections.list(request.getHeaders(name));
    }

    @Test
    void untrustedHidesTraceBaggageAndRemoteFields() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        request.addHeader("tracestate", "vendor=1");
        request.addHeader("baggage", "partner=p");
        request.addHeader("X-B3-TraceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        request.addHeader("tenant", "t1");
        request.addHeader("Partner", "p");
        request.addHeader("Accept", "application/json");

        HttpServletRequest seen = filter(false, request);

        for (String name : List.of("traceparent", "tracestate", "baggage", "X-B3-TraceId", "tenant", "Partner")) {
            assertThat(seen.getHeader(name)).as(name).isNull();
            assertThat(headers(seen, name)).as(name).isEmpty();
        }
        assertThat(seen.getHeader("Accept")).isEqualTo("application/json");
        assertThat(headers(seen, "Accept")).containsExactly("application/json");
        assertThat(headerNames(seen)).containsExactly("Accept");
        assertThat(InboundBaggage.candidates(seen)).isEmpty();
    }

    @Test
    void untrustedIgnoresSynapseFieldsInBaggage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("baggage", "correlationId=evil");

        HttpServletRequest seen = filter(false, request);

        assertThat(InboundBaggage.candidates(seen)).isEmpty();
        assertThat(InboundBaggage.candidates(request)).isEmpty();
    }

    @Test
    void trustedStripsSynapseFieldsFromBaggage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("baggage", "correlationId=evil,tenant=t1,partner=p");

        HttpServletRequest seen = filter(true, request);

        assertThat(seen.getHeader("baggage")).isEqualTo("partner=p");
        assertThat(headers(seen, "Baggage")).containsExactly("partner=p");
        assertThat(headerNames(seen)).containsExactly("baggage");
        assertThat(InboundBaggage.candidates(seen))
                .containsExactly(Map.entry("correlationId", "evil"),
                        Map.entry("tenant", "t1"));
    }

    @Test
    void trustedHidesBaggageWithOnlySynapseFields() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("baggage", "correlationId=evil%20id");

        HttpServletRequest seen = filter(true, request);

        assertThat(seen.getHeader("baggage")).isNull();
        assertThat(headers(seen, "baggage")).isEmpty();
        assertThat(headerNames(seen)).isEmpty();
        assertThat(InboundBaggage.candidates(seen)).containsExactly(Map.entry("correlationId", "evil id"));
    }

    @Test
    void trustedStripsEveryBaggageHeaderLine() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("baggage", "partner=p");
        request.addHeader("baggage", "correlationId=evil,other=o");

        HttpServletRequest seen = filter(true, request);

        assertThat(headers(seen, "baggage")).containsExactly("partner=p,other=o");
        assertThat(InboundBaggage.candidates(seen)).containsExactly(Map.entry("correlationId", "evil"));
    }

    @Test
    void trustedStripsSynapseFieldsWhateverTheirCase() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("baggage", "CorrelationID=evil,TENANT=t1,partner=p");

        HttpServletRequest seen = filter(true, request);

        assertThat(seen.getHeader("baggage")).isEqualTo("partner=p");
        assertThat(InboundBaggage.candidates(seen))
                .containsExactly(Map.entry("correlationId", "evil"),
                        Map.entry("tenant", "t1"));
    }

    @Test
    void remoteFieldHeaderWinsOverBaggageEntry() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Tenant", "t2");
        request.addHeader("baggage", "tenant=t1");

        HttpServletRequest seen = filter(true, request);

        assertThat(InboundBaggage.candidates(seen)).containsExactly(Map.entry("tenant", "t2"));
        assertThat(seen.getHeader("tenant")).isNull();
        assertThat(headers(seen, "TENANT")).isEmpty();
        assertThat(seen.getHeader("baggage")).isNull();
    }

    @Test
    void trustedPassesOtherRemoteFields() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("partner", "p");

        HttpServletRequest seen = filter(true, request);

        assertThat(seen.getHeader("partner")).isEqualTo("p");
        assertThat(InboundBaggage.candidates(seen)).isEmpty();
    }

    @Test
    void headerNamesAreCaseInsensitive() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("TRACEPARENT", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        request.addHeader("x-b3-traceid", "4bf92f3577b34da6a3ce929d0e0e4736");

        HttpServletRequest seen = filter(false, request);

        assertThat(seen.getHeader("traceparent")).isNull();
        assertThat(seen.getHeader("TRACEPARENT")).isNull();
        assertThat(seen.getHeader("X-B3-TraceId")).isNull();
        assertThat(headerNames(seen)).isEmpty();
    }

    @Test
    void trustedLeavesTraceHeaders() throws Exception {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", traceparent);
        request.addHeader("b3", "4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1");

        HttpServletRequest seen = filter(true, request);

        assertThat(seen.getHeader("traceparent")).isEqualTo(traceparent);
        assertThat(seen.getHeader("b3")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1");
        assertThat(headerNames(seen)).containsExactly("traceparent", "b3");
        assertThat(InboundBaggage.candidates(seen)).isEmpty();
    }

    @Test
    void untrustedHidesEveryTraceHeaderInAnyCase() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        for (String name : TraceHeaders.NAMES) {
            request.addHeader(name.toLowerCase(Locale.ROOT), "v");
        }
        request.addHeader("Accept", "application/json");

        HttpServletRequest seen = filter(false, request);

        assertThat(headerNames(seen)).containsExactly("Accept");
    }

    @Test
    void candidatesAreEmptyWhenTheFilterDidNotRun() {
        assertThat(InboundBaggage.candidates(new MockHttpServletRequest())).isEmpty();
    }
}
