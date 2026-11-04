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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.context.RequestContext;

import static org.assertj.core.api.Assertions.assertThat;

class ContextPropagationTest {

    private final ContextBinder binder = new ContextBinder(Tracer.NOOP, new CorrelationIdPolicy(128), true, 16, 4096);

    @Test
    void contextFollowsTaskThroughContextSnapshot() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Optional<String>> seen = new CompletableFuture<>();
            Runnable wrapped;
            try (ContextScope scope = binder
                    .open(new InboundContext("req-1", Map.of("tenant", "t1"), null, null, null))) {
                wrapped = ContextSnapshotFactory
                        .builder()
                        .build()
                        .captureAll()
                        .wrap((Runnable) () -> seen.complete(RequestContext.find().map(RequestContext::correlationId)));
            }
            pool.submit(wrapped).get();
            assertThat(seen.get()).contains("req-1");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pooledThreadHasNoContextAfterTheWrappedTaskEnds() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Runnable wrapped;
            try (ContextScope scope = binder.open(new InboundContext("req-2", Map.of(), null, null, null))) {
                wrapped = ContextSnapshotFactory.builder().build().captureAll().wrap(() -> {
                });
            }
            pool.submit(wrapped).get();
            Optional<RequestContext> leaked = pool.submit(RequestContext::find).get();
            assertThat(leaked).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void accessorIsRegisteredUnderTheSynapseKey() {
        assertThat(new ContextValueAccessor().key()).isEqualTo("synapse.context");
        assertThat(new ContextValueAccessor().getValue()).isNull();
    }
}
