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

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.context.RequestContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextBinderTest {

    private static final Principal ALICE = () -> "alice";

    private final Tracer tracer = otelTracer();

    private final ContextBinder binder = new ContextBinder(tracer, new CorrelationIdPolicy(128), true, 16, 4096);

    static Tracer otelTracer() {
        OtelCurrentTraceContext current = new OtelCurrentTraceContext();
        OtelBaggageManager baggage = new OtelBaggageManager(current, List.of("correlationId", "tenant"), List.of());
        return new OtelTracer(SdkTracerProvider.builder().build().get("test"), current, event -> {
        }, baggage);
    }

    private @Nullable String baggage(String name) {
        Baggage baggage = tracer.getBaggage(name);
        return baggage == null ? null : baggage.get();
    }

    @Test
    void opensContextWithAllValues() {
        Instant deadline = Instant.parse("2026-01-01T00:00:10Z");
        InboundContext inbound = new InboundContext("req-1", Map.of("tenant", "t1"), ALICE, Locale.CANADA_FRENCH,
                deadline);
        try (ContextScope scope = binder.open(inbound)) {
            RequestContext context = RequestContext.current();
            assertThat(context).isSameAs(scope.context());
            assertThat(context.correlationId()).isEqualTo("req-1");
            assertThat(context.principal()).containsSame(ALICE);
            assertThat(context.locale()).contains(Locale.CANADA_FRENCH);
            assertThat(context.deadline()).contains(deadline);
            assertThat(context.attributes()).containsExactly(Map.entry("tenant", "t1"));
            assertThat(context.attribute("tenant")).contains("t1");
            assertThat(context.attribute("other")).isEmpty();
            assertThat(context.remaining(clockAt("2026-01-01T00:00:04Z"))).contains(Duration.ofSeconds(6));
        }
    }

    @Test
    void emptyInboundGeneratesAnIdAndHasNothingElse() {
        try (ContextScope scope = binder.open(InboundContext.empty())) {
            RequestContext context = RequestContext.current();
            assertThat(context.correlationId()).isNotBlank();
            assertThat(context.principal()).isEmpty();
            assertThat(context.locale()).isEmpty();
            assertThat(context.deadline()).isEmpty();
            assertThat(context.remaining(Clock.systemUTC())).isEmpty();
            assertThat(context.attributes()).isEmpty();
        }
    }

    @Test
    void currentThrowsWhenNoContextIsActive() {
        assertThat(RequestContext.find()).isEmpty();
        assertThatThrownBy(RequestContext::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No Synapse request context is active");
    }

    @Test
    void attributesAreUnmodifiable() {
        try (ContextScope scope = binder.open(new InboundContext("r", Map.of("tenant", "t1"), null, null, null))) {
            Map<String, String> attributes = RequestContext.current().attributes();
            assertThatThrownBy(() -> attributes.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void opensBaggageForCorrelationIdAndAttributes() {
        try (ContextScope scope = binder.open(new InboundContext("req-1", Map.of("tenant", "t1"), null, null, null))) {
            assertThat(baggage("correlationId")).isEqualTo("req-1");
            assertThat(baggage("tenant")).isEqualTo("t1");
        }
    }

    @Test
    void closingRestoresPreviousState() {
        try (ContextScope outer = binder
                .open(new InboundContext("outer", Map.of("tenant", "t-outer"), null, null, null))) {
            try (ContextScope inner = binder
                    .open(new InboundContext("inner", Map.of("tenant", "t-inner"), null, null, null))) {
                assertThat(RequestContext.current().correlationId()).isEqualTo("inner");
                assertThat(baggage("correlationId")).isEqualTo("inner");
                assertThat(baggage("tenant")).isEqualTo("t-inner");
            }
            assertThat(RequestContext.current().correlationId()).isEqualTo("outer");
            assertThat(baggage("correlationId")).isEqualTo("outer");
            assertThat(baggage("tenant")).isEqualTo("t-outer");
        }
        assertThat(RequestContext.find()).isEmpty();
        assertThat(baggage("correlationId")).isNull();
        assertThat(baggage("tenant")).isNull();
    }

    @Test
    void scopeIsClosedWhenBodyThrows() {
        assertThatThrownBy(() -> {
            try (ContextScope scope = binder
                    .open(new InboundContext("boom", Map.of("tenant", "t1"), null, null, null))) {
                throw new IllegalArgumentException("body failed");
            }
        }).isInstanceOf(IllegalArgumentException.class);
        assertThat(RequestContext.find()).isEmpty();
        assertThat(baggage("correlationId")).isNull();
        assertThat(baggage("tenant")).isNull();
    }

    @Test
    void closingTwiceDoesNotDisturbAnOuterContext() {
        try (ContextScope outer = binder.open(new InboundContext("outer", Map.of(), null, null, null))) {
            ContextScope inner = binder.open(new InboundContext("inner", Map.of(), null, null, null));
            inner.close();
            inner.close();
            assertThat(RequestContext.current().correlationId()).isEqualTo("outer");
            assertThat(baggage("correlationId")).isEqualTo("outer");
        }
        assertThat(RequestContext.find()).isEmpty();
    }

    @Test
    void failureOpeningBaggageClosesWhatWasOpenedAndLeavesNoContextBehind() {
        Tracer failing = mock(Tracer.class);
        BaggageInScope opened = mock(BaggageInScope.class);
        when(failing.createBaggageInScope(eq("correlationId"), anyString())).thenReturn(opened);
        when(failing.createBaggageInScope(eq("tenant"), anyString()))
                .thenThrow(new IllegalStateException("no baggage"));
        ContextBinder failingBinder = new ContextBinder(failing, new CorrelationIdPolicy(128), true, 16, 4096);

        assertThatThrownBy(() -> failingBinder.open(new InboundContext("x", Map.of("tenant", "t"), null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("no baggage");

        verify(opened).close();
        assertThat(RequestContext.find()).isEmpty();
    }

    @Test
    void contextIsRestoredEvenWhenClosingBaggageFails() {
        Tracer failing = mock(Tracer.class);
        BaggageInScope baggage = mock(BaggageInScope.class);
        when(failing.createBaggageInScope(anyString(), anyString())).thenReturn(baggage);
        org.mockito.Mockito.doThrow(new IllegalStateException("close failed")).when(baggage).close();
        ContextBinder failingBinder = new ContextBinder(failing, new CorrelationIdPolicy(128), true, 16, 4096);

        ContextScope scope = failingBinder.open(new InboundContext("x", Map.of(), null, null, null));
        assertThatThrownBy(scope::close).isInstanceOf(IllegalStateException.class).hasMessage("close failed");
        assertThat(RequestContext.find()).isEmpty();
    }

    @Test
    void untrustedBinderDropsAttributes() {
        ContextBinder untrusted = new ContextBinder(tracer, new CorrelationIdPolicy(128), false, 16, 4096);
        try (ContextScope scope = untrusted
                .open(new InboundContext("req-1", Map.of("tenant", "t1"), null, null, null))) {
            assertThat(RequestContext.current().attributes()).isEmpty();
            assertThat(RequestContext.current().correlationId()).isEqualTo("req-1");
            assertThat(baggage("tenant")).isNull();
        }
    }

    @Test
    void dropsEntriesOverCountLimit() {
        ContextBinder limited = new ContextBinder(tracer, new CorrelationIdPolicy(128), true, 2, 4096);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a", "1");
        attributes.put("b", "2");
        attributes.put("c", "3");
        try (ContextScope scope = limited.open(new InboundContext("r", attributes, null, null, null))) {
            assertThat(RequestContext.current().attributes())
                    .containsExactly(Map.entry("a", "1"),
                            Map.entry("b", "2"));
        }
    }

    @Test
    void dropsEntriesOverSizeLimit() {
        ContextBinder limited = new ContextBinder(tracer, new CorrelationIdPolicy(128), true, 16, 10);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a", "12345");
        attributes.put("b", "123456");
        try (ContextScope scope = limited.open(new InboundContext("r", attributes, null, null, null))) {
            assertThat(RequestContext.current().attributes()).containsExactly(Map.entry("a", "12345"));
        }
    }

    @Test
    void sizeLimitCountsUtf8Bytes() {
        ContextBinder limited = new ContextBinder(tracer, new CorrelationIdPolicy(128), true, 16, 4);
        try (ContextScope scope = limited
                .open(new InboundContext("r", Map.of("a", "éé"), null, null, null))) {
            assertThat(RequestContext.current().attributes()).isEmpty();
        }
        try (ContextScope scope = limited.open(new InboundContext("r", Map.of("a", "é"), null, null, null))) {
            assertThat(RequestContext.current().attributes()).containsOnlyKeys("a");
        }
    }

    @Test
    void worksWithNoopTracer() {
        ContextBinder noop = new ContextBinder(Tracer.NOOP, new CorrelationIdPolicy(128), true, 16, 4096);
        try (ContextScope scope = noop.open(new InboundContext("req-1", Map.of("tenant", "t1"), null, null, null))) {
            assertThat(RequestContext.current().correlationId()).isEqualTo("req-1");
            assertThat(RequestContext.current().attribute("tenant")).isEqualTo(Optional.of("t1"));
        }
        assertThat(RequestContext.find()).isEmpty();
    }

    @Test
    void remainingIsNeverNegative() {
        Instant deadline = Instant.parse("2026-01-01T00:00:00Z");
        try (ContextScope scope = binder.open(new InboundContext("r", Map.of(), null, null, deadline))) {
            assertThat(RequestContext.current().remaining(clockAt("2026-01-01T00:00:30Z")))
                    .isEqualTo(Optional.of(Duration.ZERO));
        }
    }

    private static Clock clockAt(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
