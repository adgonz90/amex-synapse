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
package io.americanexpress.synapse.core.autoconfigure;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.KeyValuePair;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots a real application in which a {@code @Scheduled} method calls an operation (P1 acceptance 20): the call gets a
 * generated correlation id and no principal, the id is in the MDC during {@code execute}, and the failure log line
 * carries it.
 */
@SpringBootTest(classes = SynapseOperationIT.TestApplication.class, properties = {
        "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"})
class SynapseOperationIT {

    @Autowired
    private Probe probe;

    @Autowired
    private RecordContextOperation operation;

    private final ListAppender<ILoggingEvent> failures = new ListAppender<>();

    private final Logger failureLogger = (Logger) LoggerFactory.getLogger(OperationFixtures.FAILURE_LOGGER);

    @BeforeEach
    void captureFailureLog() {
        // Attached once the context has started, after Boot's logging initialization has reset Logback.
        failures.start();
        failureLogger.addAppender(failures);
    }

    @AfterEach
    void stopCapturing() {
        failureLogger.detachAppender(failures);
        failures.stop();
    }

    @Test
    void scheduledOperationGetsAGeneratedCorrelationIdAndNoPrincipal() throws Exception {
        assertThat(AopUtils.isCglibProxy(operation)).isTrue();
        probe.armed = true;
        Seen seen = probe.seen.get(10, TimeUnit.SECONDS);

        assertThat(seen.correlationId()).isNotBlank();
        assertThat(seen.hasPrincipal()).isFalse();
        assertThat(seen.mdcCorrelationId()).isEqualTo(seen.correlationId());
        assertThat(probe.thrown.get(10, TimeUnit.SECONDS).details()).isEqualTo(SynapseErrors.CONFLICT);

        assertThat(failures.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("record-context").contains("CONFLICT");
            assertThat(keyValues(event)).containsEntry("correlationId", seen.correlationId());
            assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", seen.correlationId());
        });
    }

    private static Map<String, @Nullable Object> keyValues(ILoggingEvent event) {
        Map<String, @Nullable Object> values = new HashMap<>();
        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                values.put(pair.key, pair.value);
            }
        }
        return values;
    }

    record Seen(String correlationId, boolean hasPrincipal, @Nullable String mdcCorrelationId) {
    }

    /** What the scheduled call saw, shared between the operation, the job and the test. */
    static class Probe {

        volatile boolean armed;

        final CompletableFuture<Seen> seen = new CompletableFuture<>();

        final CompletableFuture<SynapseException> thrown = new CompletableFuture<>();
    }

    /** A command named {@code record-context} that records what it sees, then fails with a conflict. */
    static class RecordContextOperation implements CommandOperation<String, String> {

        private final Probe probe;

        RecordContextOperation(Probe probe) {
            this.probe = probe;
        }

        @Override
        public String execute(String input) {
            RequestContext context = RequestContext.current();
            probe.seen
                    .complete(new Seen(context.correlationId(), context.principal().isPresent(),
                            MDC.get("correlationId")));
            throw new SynapseException(SynapseErrors.CONFLICT);
        }
    }

    /** Calls the operation once, from the scheduler thread, after the test arms the probe. */
    static class ScheduledJob {

        private final RecordContextOperation operation;

        private final Probe probe;

        ScheduledJob(RecordContextOperation operation, Probe probe) {
            this.operation = operation;
            this.probe = probe;
        }

        @Scheduled(fixedDelay = 20)
        void run() {
            if (!probe.armed || probe.thrown.isDone()) {
                return;
            }
            try {
                operation.execute("x");
            } catch (SynapseException failure) {
                probe.thrown.complete(failure);
            }
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableScheduling
    static class TestApplication {

        @Bean
        Probe probe() {
            return new Probe();
        }

        @Bean
        RecordContextOperation recordContextOperation(Probe probe) {
            return new RecordContextOperation(probe);
        }

        @Bean
        ScheduledJob scheduledJob(RecordContextOperation operation, Probe probe) {
            return new ScheduledJob(operation, probe);
        }
    }
}
