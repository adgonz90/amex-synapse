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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the policy against a real tracer and Boot's {@code RestClient.Builder}: a request interceptor sees the
 * {@code baggage} header Boot injects, and applying the policy leaves only allow-listed fields on the wire.
 */
@Tag("smoke")
@SpringBootTest(classes = OutboundBaggagePolicyIT.TestApp.class, properties = {
        "management.tracing.baggage.remote-fields=tenant,secret",
        "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"})
class OutboundBaggagePolicyIT {

    private static final AtomicReference<@Nullable Map<String, String>> RECORDED = new AtomicReference<>();
    private static final AtomicReference<@Nullable String> SEEN_BY_INTERCEPTOR = new AtomicReference<>();
    private static final AtomicReference<@Nullable Map<String, String>> HEADERS_BEFORE_FILTER = new AtomicReference<>();

    private static HttpServer server;

    @Autowired
    private Tracer tracer;

    @Autowired
    private RestClient.Builder builder;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Map<String, String> headers = new HashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
                RECORDED.set(headers);
                exchange.sendResponseHeaders(204, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void resetRecordings() {
        RECORDED.set(null);
        SEEN_BY_INTERCEPTOR.set(null);
        HEADERS_BEFORE_FILTER.set(null);
    }

    @Test
    @SuppressWarnings("unused")
    void outboundCallCarriesOnlyAllowListedBaggage() {
        OutboundBaggagePolicy policy = OutboundBaggagePolicy.allowing(Set.of("tenant"));
        RestClient client = builder.requestInterceptor((request, body, execution) -> {
            Map<String, String> before = new HashMap<>();
            request.getHeaders().forEach((k, v) -> before.put(k.toLowerCase(Locale.ROOT), v.get(0)));
            HEADERS_BEFORE_FILTER.set(before);
            String baggage = request.getHeaders().getFirst(BaggageHeaders.HEADER);
            SEEN_BY_INTERCEPTOR.set(baggage);
            request.getHeaders().remove(BaggageHeaders.HEADER);
            String filtered = policy.filterBaggageHeader(baggage);
            if (filtered != null) {
                request.getHeaders().set(BaggageHeaders.HEADER, filtered);
            }
            for (String name : policy.headersToRemove(List.of("tenant", "secret", "correlationId"))) {
                request.getHeaders().remove(name);
            }
            return execution.execute(request, body);
        }).build();

        try (BaggageInScope correlation = tracer.createBaggageInScope("correlationId", "c-1");
                BaggageInScope tenant = tracer.createBaggageInScope("tenant", "acme");
                BaggageInScope secret = tracer.createBaggageInScope("secret", "s3cr3t")) {
            client
                    .get()
                    .uri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                    .retrieve()
                    .toBodilessEntity();
        }

        // What Boot injected before the policy ran: the negative assertions below are only meaningful against this.
        assertThat(SEEN_BY_INTERCEPTOR.get())
                .isNotNull()
                .contains("correlationId=c-1")
                .contains("tenant=acme")
                .contains("secret=s3cr3t");
        Map<String, String> before = HEADERS_BEFORE_FILTER.get();
        assertThat(before).isNotNull().containsKey("secret");

        Map<String, String> headers = Objects.requireNonNull(RECORDED.get(), "the downstream recorded no request");
        assertThat(headers.get("baggage"))
                .contains("correlationId=c-1;propagation%3Dunlimited")
                .contains("tenant=acme;propagation%3Dunlimited")
                .doesNotContain("secret")
                .doesNotContain("s3cr3t");
        assertThat(headers).doesNotContainKey("secret");
        assertThat(headers.get("tenant")).isEqualTo("acme");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }
}
