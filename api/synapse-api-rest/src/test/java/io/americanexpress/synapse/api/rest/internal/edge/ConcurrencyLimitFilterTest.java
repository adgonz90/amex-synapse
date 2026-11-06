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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.internal.error.ProblemTitles;
import io.americanexpress.synapse.api.rest.internal.error.ProblemWriter;
import io.americanexpress.synapse.api.rest.internal.error.RestErrorStatuses;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ConcurrencyLimitFilterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final StaticMessageSource messages = new StaticMessageSource();

    private final ProblemWriter writer = new ProblemWriter(new RestErrorStatuses(Map.of(), Map.of()),
            new ErrorMessages(messages), new ProblemTitles(messages), new SynapseErrorProperties(false, null),
            MAPPER, "X-Correlation-Id");

    private final AtomicInteger chainCalls = new AtomicInteger();

    private final FilterChain counting = (request, response) -> chainCalls.incrementAndGet();

    @Test
    void rejectsWith503AndRetryAfterWhenFull() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, List.of(), writer);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FilterChain blocking = (request, response) -> {
            chainCalls.incrementAndGet();
            entered.countDown();
            await(release);
        };
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            try {
                filter.doFilter(get("/books/1"), firstResponse, blocking);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("first request entered the chain").isTrue();

        MockHttpServletRequest second = get("/books/2");
        second.setAttribute(RestRequestAttributes.CORRELATION_ID, "c-2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(second, response, blocking);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asString()).isEqualTo("UNAVAILABLE");
        assertThat(body.get("correlationId").asString()).isEqualTo("c-2");
        assertThat(chainCalls.get()).isEqualTo(1);

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        assertThat(firstResponse.getStatus()).isEqualTo(200);

        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(get("/books/3"), third, counting);
        assertThat(third.getStatus()).isEqualTo(200);
        assertThat(chainCalls.get()).isEqualTo(2);
    }

    @Test
    void managementPathsAreNeverLimited() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, List.of("/actuator", "/health"), writer);
        startAsync(filter, get("/books/1"));

        for (String path : List.of("/actuator", "/actuator/health", "/actuator/health/readiness", "/health")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(get(path), response, counting);
            assertThat(response.getStatus()).as(path).isEqualTo(200);
        }
        // One call started the held request.
        assertThat(chainCalls.get()).isEqualTo(5);

        for (String path : List.of("/actuatorx", "/healthy", "/books/2", "/")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(get(path), response, counting);
            assertThat(response.getStatus()).as(path).isEqualTo(503);
        }
        assertThat(chainCalls.get()).isEqualTo(5);
    }

    @Test
    void pathThatOnlyNormalizesToAManagementPathIsLimited() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, List.of("/actuator"), writer);
        startAsync(filter, get("/books/1"));

        // The container maps the normalized path; the raw URI still holds the dot segments.
        for (String uri : List.of("/files/../actuator/health", "/app/files/../actuator/health")) {
            MockHttpServletRequest traversal = new MockHttpServletRequest("GET", uri);
            if (uri.startsWith("/app")) {
                traversal.setContextPath("/app");
            }
            traversal.setServletPath("/actuator/health");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(traversal, response, counting);
            assertThat(response.getStatus()).as(uri).isEqualTo(503);
        }

        // The raw URI under the prefix but a mapped path outside it is limited too.
        MockHttpServletRequest dotted = new MockHttpServletRequest("GET", "/actuator/../books/1");
        dotted.setServletPath("/books/1");
        MockHttpServletResponse dottedResponse = new MockHttpServletResponse();
        filter.doFilter(dotted, dottedResponse, counting);
        assertThat(dottedResponse.getStatus()).isEqualTo(503);

        MockHttpServletResponse plain = new MockHttpServletResponse();
        filter.doFilter(get("/actuator/health"), plain, counting);
        assertThat(plain.getStatus()).isEqualTo(200);
    }

    @Test
    void managementPathIsMatchedWithinTheApplication() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, List.of("/actuator"), writer);
        startAsync(filter, get("/books/1"));

        // Behind a servlet mapped to /api/*, Tomcat splits the path into servlet path and path info.
        MockHttpServletRequest split = new MockHttpServletRequest("GET", "/app/api/actuator/health");
        split.setContextPath("/app");
        split.setServletPath("/api");
        split.setPathInfo("/actuator/health");
        MockHttpServletResponse splitResponse = new MockHttpServletResponse();
        filter.doFilter(split, splitResponse, counting);
        assertThat(splitResponse.getStatus()).isEqualTo(503);

        ConcurrencyLimitFilter apiFilter = new ConcurrencyLimitFilter(1, List.of("/api/actuator"), writer);
        startAsync(apiFilter, get("/books/1"));
        MockHttpServletResponse apiResponse = new MockHttpServletResponse();
        apiFilter.doFilter(split, apiResponse, counting);
        assertThat(apiResponse.getStatus()).isEqualTo(200);

        // Without servlet path and path info (no container mapping), the URI minus the context path is used.
        MockHttpServletRequest unmapped = new MockHttpServletRequest("GET", "/app/actuator/health");
        unmapped.setContextPath("/app");
        MockHttpServletResponse unmappedResponse = new MockHttpServletResponse();
        filter.doFilter(unmapped, unmappedResponse, counting);
        assertThat(unmappedResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void permitIsReleasedWhenTheHandlerThrows() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, List.of(), writer);
        FilterChain failing = (request, response) -> {
            chainCalls.incrementAndGet();
            throw new IllegalStateException("handler failed");
        };

        for (int i = 0; i < 200; i++) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> filter.doFilter(get("/books/1"), new MockHttpServletResponse(), failing));
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(get("/books/201"), response, counting);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalls.get()).isEqualTo(201);
    }

    @Test
    void permitIsReleasedOnAsyncCompletion() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, List.of(), writer);
        MockHttpServletRequest async = get("/books/1");
        MockAsyncContext context = startAsync(filter, async);

        MockHttpServletResponse whileHeld = new MockHttpServletResponse();
        filter.doFilter(get("/books/2"), whileHeld, counting);
        assertThat(whileHeld.getStatus()).isEqualTo(503);

        context.complete();

        MockHttpServletResponse afterCompletion = new MockHttpServletResponse();
        filter.doFilter(get("/books/3"), afterCompletion, counting);
        assertThat(afterCompletion.getStatus()).isEqualTo(200);
    }

    @Test
    void permitIsReleasedOnAsyncErrorAndTimeout() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, List.of(), writer);

        MockAsyncContext failed = startAsync(filter, get("/books/1"));
        listener(failed).onError(new AsyncEvent(failed));
        MockAsyncContext timedOut = startAsync(filter, get("/books/2"));
        listener(timedOut).onTimeout(new AsyncEvent(timedOut));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(get("/books/3"), response, counting);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void permitIsReleasedOnlyOnce() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, List.of(), writer);
        MockAsyncContext first = startAsync(filter, get("/books/1"));
        AsyncListener listener = listener(first);

        // A timeout is followed by the completion of the same request: one permit comes back, not two.
        listener.onTimeout(new AsyncEvent(first));
        listener.onError(new AsyncEvent(first));
        first.complete();

        startAsync(filter, get("/books/2"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(get("/books/3"), response, counting);
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void restartedAsyncKeepsThePermitUntilItsCompletion() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, List.of(), writer);
        MockHttpServletRequest request = get("/books/1");
        MockAsyncContext first = startAsync(filter, request);
        AsyncListener listener = listener(first);

        // Restarting async clears the listeners; the filter registers itself on the new context.
        MockAsyncContext restarted = new MockAsyncContext(request, new MockHttpServletResponse());
        listener.onStartAsync(new AsyncEvent(restarted));
        assertThat(restarted.getListeners()).containsExactly(listener);

        MockHttpServletResponse whileHeld = new MockHttpServletResponse();
        filter.doFilter(get("/books/2"), whileHeld, counting);
        assertThat(whileHeld.getStatus()).isEqualTo(503);

        restarted.complete();

        MockHttpServletResponse afterCompletion = new MockHttpServletResponse();
        filter.doFilter(get("/books/3"), afterCompletion, counting);
        assertThat(afterCompletion.getStatus()).isEqualTo(200);
    }

    @Test
    void permitIsReleasedWhenTheAsyncRequestEndedBeforeTheListenerIsAdded() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1, List.of(), writer);
        MockHttpServletRequest ended = new MockHttpServletRequest("GET", "/books/1") {
            @Override
            public boolean isAsyncStarted() {
                return true;
            }

            @Override
            public AsyncContext getAsyncContext() {
                return new MockAsyncContext(this, null) {
                    @Override
                    public void addListener(AsyncListener listener) {
                        throw new IllegalStateException("async request already completed");
                    }
                };
            }
        };
        filter.doFilter(ended, new MockHttpServletResponse(), counting);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(get("/books/2"), response, counting);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void filterOrderFollowsTheContextFilter() {
        assertThat(new ConcurrencyLimitFilter(1, List.of(), writer).getOrder())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 3);
    }

    /** Runs a request whose handler starts async processing and returns, so the request keeps its permit. */
    private MockAsyncContext startAsync(ConcurrencyLimitFilter filter, MockHttpServletRequest request)
            throws Exception {
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            chainCalls.incrementAndGet();
            req.startAsync();
        });
        assertThat(response.getStatus()).isEqualTo(200);
        return (MockAsyncContext) Objects.requireNonNull(request.getAsyncContext());
    }

    private static AsyncListener listener(MockAsyncContext context) {
        assertThat(context.getListeners()).hasSize(1);
        return context.getListeners().getFirst();
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the latch was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
