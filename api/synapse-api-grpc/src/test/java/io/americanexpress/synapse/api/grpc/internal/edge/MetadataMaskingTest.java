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
package io.americanexpress.synapse.api.grpc.internal.edge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.internal.context.TraceHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataMaskingTest {

    /** Ordered, as the auto-configuration builds it: candidates are read in this order. */
    private static final Set<String> SYNAPSE_FIELDS = new LinkedHashSet<>(List.of("correlationId", "tenant"));

    private static final Set<String> REMOTE_FIELDS = Set.of("correlationId", "tenant", "region");

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private static Metadata.Key<String> key(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }

    private static Metadata metadata(String... pairs) {
        Metadata metadata = new Metadata();
        for (int i = 0; i < pairs.length; i += 2) {
            metadata.put(key(pairs[i]), pairs[i + 1]);
        }
        return metadata;
    }

    /** Every key and its values, in order, so two metadata can be compared. */
    private static Map<String, List<String>> entries(Metadata metadata) {
        Map<String, List<String>> entries = new LinkedHashMap<>();
        for (String name : metadata.keys()) {
            Iterable<String> values = metadata.getAll(key(name));
            List<String> list = new ArrayList<>();
            if (values != null) {
                values.forEach(list::add);
            }
            entries.put(name, list);
        }
        return entries;
    }

    private static MetadataMasking masking(boolean trusted) {
        return new MetadataMasking(trusted, SYNAPSE_FIELDS, REMOTE_FIELDS);
    }

    @Test
    void traceKeysAreTheCoreTraceHeadersInLowerCase() {
        assertThat(MetadataMasking.TRACE_KEYS)
                .containsExactlyInAnyOrder("traceparent", "tracestate", "b3", "x-b3-traceid", "x-b3-spanid",
                        "x-b3-parentspanid", "x-b3-sampled", "x-b3-flags");
        assertThat(MetadataMasking.TRACE_KEYS)
                .hasSameSizeAs(TraceHeaders.NAMES)
                .allSatisfy(name -> assertThat(name).isEqualTo(name.toLowerCase(Locale.ROOT)));
    }

    @Test
    void untrustedHidesTraceBaggageAndRemoteFields() {
        Metadata inbound = metadata("traceparent", TRACEPARENT, "tracestate", "vendor=1", "baggage", "partner=p",
                "x-b3-traceid", "4bf92f3577b34da6a3ce929d0e0e4736", "b3", "4bf92f3577b34da6a3ce929d0e0e4736-1",
                "x-b3-spanid", "00f067aa0ba902b7", "x-b3-parentspanid", "00f067aa0ba902b7", "x-b3-sampled", "1",
                "x-b3-flags", "1", "tenant", "t1", "region", "eu", "x-other", "o");

        MetadataMasking.Result result = masking(false).apply(inbound);

        assertThat(result.metadata().keys()).containsExactly("x-other");
        assertThat(result.metadata().get(key("x-other"))).isEqualTo("o");
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void untrustedHidesSynapseFieldKeysThatAreNotRemoteFields() {
        // Core merges the Synapse fields into the remote fields; the edge hides their keys even if that did not run.
        MetadataMasking masking = new MetadataMasking(false, Set.of("correlationId", "tenant"), Set.of());

        MetadataMasking.Result result = masking.apply(metadata("tenant", "t1", "correlationid", "c-1", "x-other", "o"));

        assertThat(result.metadata().keys()).containsExactly("x-other");
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void trustedStripsSynapseFieldsFromBaggage() {
        Metadata inbound = metadata("baggage", "correlationId=evil,tenant=t1,partner=p", "x-other", "o");

        MetadataMasking.Result result = masking(true).apply(inbound);

        assertThat(result.metadata().getAll(key("baggage"))).containsExactly("partner=p");
        assertThat(result.metadata().get(key("x-other"))).isEqualTo("o");
        assertThat(result.candidates()).containsExactly(Map.entry("correlationId", "evil"), Map.entry("tenant", "t1"));
    }

    @Test
    void trustedHidesBaggageWithOnlySynapseFields() {
        MetadataMasking.Result result = masking(true).apply(metadata("baggage", "correlationId=c-1"));

        assertThat(result.metadata().keys()).doesNotContain("baggage");
        assertThat(result.candidates()).containsExactly(Map.entry("correlationId", "c-1"));
    }

    @Test
    void trustedJoinsEveryBaggageValueBeforeStripping() {
        Metadata inbound = metadata("baggage", "tenant=t1", "baggage", "partner=p");

        MetadataMasking.Result result = masking(true).apply(inbound);

        assertThat(result.metadata().getAll(key("baggage"))).containsExactly("partner=p");
        assertThat(result.candidates()).containsExactly(Map.entry("tenant", "t1"));
    }

    @Test
    void remoteFieldKeyWinsOverBaggageEntry() {
        Metadata inbound = metadata("tenant", "t2", "baggage", "tenant=t1");

        MetadataMasking.Result result = masking(true).apply(inbound);

        assertThat(result.candidates()).containsExactly(Map.entry("tenant", "t2"));
        assertThat(result.metadata().keys()).doesNotContain("tenant", "baggage");
    }

    @Test
    void synapseFieldsMatchMetadataKeysAndBaggageCaseInsensitively() {
        // gRPC metadata keys are lower case; the field names are mixed case.
        Metadata inbound = metadata("correlationid", "c-9", "baggage", "CorrelationID=evil,TENANT=t1,partner=p");

        MetadataMasking.Result result = masking(true).apply(inbound);

        assertThat(result.candidates()).containsExactly(Map.entry("correlationId", "c-9"), Map.entry("tenant", "t1"));
        assertThat(result.metadata().keys()).doesNotContain("correlationid");
        assertThat(result.metadata().getAll(key("baggage"))).containsExactly("partner=p");
    }

    @Test
    void trustedLeavesTraceKeys() {
        Metadata inbound = metadata("traceparent", TRACEPARENT, "tracestate", "vendor=1", "b3", "b", "x-b3-traceid",
                "4bf92f3577b34da6a3ce929d0e0e4736", "x-b3-spanid", "00f067aa0ba902b7", "x-b3-parentspanid", "p",
                "x-b3-sampled", "1", "x-b3-flags", "1", "region", "eu", "baggage", "partner=p");

        MetadataMasking.Result result = masking(true).apply(inbound);

        assertThat(entries(result.metadata())).isEqualTo(entries(inbound));
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void inboundMetadataIsNotChanged() {
        for (boolean trusted : List.of(true, false)) {
            Metadata inbound = metadata("traceparent", TRACEPARENT, "baggage", "correlationId=evil,partner=p",
                    "tenant", "t1", "region", "eu", "x-other", "o");
            Map<String, List<String>> before = entries(inbound);

            MetadataMasking.Result result = masking(trusted).apply(inbound);

            assertThat(entries(inbound)).as("trusted=%s", trusted).isEqualTo(before);
            assertThat(result.metadata()).isNotSameAs(inbound);
        }
    }

    @Test
    void binaryMetadataIsCopied() {
        Metadata.Key<byte[]> binary = Metadata.Key.of("x-data-bin", Metadata.BINARY_BYTE_MARSHALLER);
        Metadata inbound = metadata("traceparent", TRACEPARENT);
        inbound.put(binary, new byte[]{1, 2, 3});

        for (boolean trusted : List.of(true, false)) {
            assertThat(masking(trusted).apply(inbound).metadata().get(binary)).containsExactly(1, 2, 3);
        }
    }

    @Test
    void untrustedHidesEveryValueOfARepeatedTraceKey() {
        Metadata inbound = metadata("traceparent", TRACEPARENT, "traceparent",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "x-other", "o");

        MetadataMasking.Result result = masking(false).apply(inbound);

        assertThat(result.metadata().keys()).containsExactly("x-other");
        assertThat(result.metadata().getAll(key("traceparent"))).isNull();
    }

    @Test
    void untrustedHidesAMixedCaseRemoteField() {
        // management.tracing.baggage.remote-fields may be written in any case; metadata keys are lower case.
        MetadataMasking masking = new MetadataMasking(false, SYNAPSE_FIELDS, Set.of("Region"));

        MetadataMasking.Result result = masking.apply(metadata("region", "eu", "x-other", "o"));

        assertThat(result.metadata().keys()).containsExactly("x-other");
    }

    @Test
    void repeatedFieldKeyGivesItsFirstValue() {
        // As the REST edge reads the first header line; grpc-java's Metadata.get returns the last value.
        Metadata inbound = metadata("tenant", "t1", "tenant", "t2");

        MetadataMasking.Result result = masking(true).apply(inbound);

        assertThat(result.candidates()).containsExactly(Map.entry("tenant", "t1"));
        assertThat(result.metadata().keys()).doesNotContain("tenant");
    }

    @Test
    void keysThatAreNotValidMetadataKeysAreSkipped() {
        // HTTP/2 allows header names that Metadata.Key rejects, and a configured field may be named like one.
        Metadata inbound = InternalMetadata
                .newMetadata(ascii("x!field"), ascii("v"), ascii("traceparent"), ascii(TRACEPARENT), ascii("tenant"),
                        ascii("t1"));
        Set<String> fields = new LinkedHashSet<>(List.of("correlationId", "x!field", "tenant"));

        MetadataMasking.Result untrusted = new MetadataMasking(false, fields, Set.of("x!field")).apply(inbound);
        MetadataMasking.Result trusted = new MetadataMasking(true, fields, Set.of("x!field")).apply(inbound);

        // No Metadata.Key can name such a key, so nothing downstream can read it either; it is left as it is.
        assertThat(untrusted.metadata().keys()).containsExactly("x!field");
        assertThat(trusted.candidates()).containsExactly(Map.entry("tenant", "t1"));
        assertThat(trusted.metadata().keys()).containsExactly("x!field", "traceparent");
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
