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
package io.americanexpress.synapse.core.internal.pipeline;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import ch.qos.logback.classic.Level;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.InboundContext;
import io.americanexpress.synapse.core.masking.LogMasking;
import io.americanexpress.synapse.core.masking.MaskType;
import io.americanexpress.synapse.core.masking.Masked;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.CLOCK;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.FAILURE_LOGGER;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.PAYLOAD_LOGGER;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.VALIDATOR;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.linesContaining;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.setLevel;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class PayloadLoggingTest {

    record CardRequest(@Masked(MaskType.LAST_FOUR) String number) {
    }

    record Card(@Masked(MaskType.LAST_FOUR) String number, String holder) {
    }

    /** A query named {@code get-card}. */
    static class GetCardOperation<O> implements QueryOperation<CardRequest, O> {

        private final Function<CardRequest, O> body;

        GetCardOperation(Function<CardRequest, O> body) {
            this.body = body;
        }

        @Override
        public O execute(CardRequest input) {
            return body.apply(input);
        }
    }

    private final ContextBinder binder = PipelineFixtures.binder();

    private @Nullable Level previousPayloadLevel;

    private @Nullable Level previousFailureLevel;

    @BeforeEach
    void levels() {
        previousFailureLevel = setLevel(FAILURE_LOGGER, Level.INFO);
        previousPayloadLevel = setLevel(PAYLOAD_LOGGER, Level.DEBUG);
    }

    @AfterEach
    void restoreLevels() {
        setLevel(PAYLOAD_LOGGER, previousPayloadLevel);
        setLevel(FAILURE_LOGGER, previousFailureLevel);
    }

    private <O> GetCardOperation<O> operation(Predicate<String> payloads, Function<CardRequest, O> body) {
        return OperationPipeline
                .builder()
                .clock(CLOCK)
                .validator(VALIDATOR)
                .contextBinder(binder)
                .logMasking(LogMasking.standard())
                .payloadLogging(payloads)
                .build()
                .wrapOperation(new GetCardOperation<>(body));
    }

    private static Card card(CardRequest request) {
        return new Card(request.number(), "Ann");
    }

    @Test
    void logsMaskedPayloadsAtDebugWhenEnabled(CapturedOutput output) {
        GetCardOperation<Card> op = operation("get-card"::equals, PayloadLoggingTest::card);

        try (ContextScope scope = binder.open(new InboundContext("c-9", Map.of(), null, null, null))) {
            assertThat(op.execute(new CardRequest("4111111111111111")).holder()).isEqualTo("Ann");
        }

        assertThat(linesContaining(output.getOut(), PAYLOAD_LOGGER)).isEqualTo(2);
        assertThat(output.getOut())
                .contains(" DEBUG ", "Operation get-card input: {\"number\":\"************1111\"}",
                        "Operation get-card output: {\"number\":\"************1111\",\"holder\":\"Ann\"}",
                        "operation=\"get-card\"", "correlationId=\"c-9\"")
                .doesNotContain("4111111111111111");
    }

    @Test
    void logsNoPayloadsWhenDisabled(CapturedOutput output) {
        GetCardOperation<Card> op = operation(name -> !name.equals("get-card"), PayloadLoggingTest::card);

        op.execute(new CardRequest("4111111111111111"));

        assertThat(output).doesNotContain(PAYLOAD_LOGGER);
    }

    @Test
    void logsNoPayloadsBelowDebug(CapturedOutput output) {
        setLevel(PAYLOAD_LOGGER, Level.INFO);
        GetCardOperation<Card> op = operation(name -> true, PayloadLoggingTest::card);

        op.execute(new CardRequest("4111111111111111"));

        assertThat(output).doesNotContain(PAYLOAD_LOGGER);
    }

    @Test
    void payloadLoggingIsOffByDefault(CapturedOutput output) {
        GetCardOperation<Card> op = OperationPipeline
                .builder()
                .build()
                .wrapOperation(new GetCardOperation<>(PayloadLoggingTest::card));

        op.execute(new CardRequest("4111111111111111"));

        assertThat(output).doesNotContain(PAYLOAD_LOGGER);
    }

    @Test
    void unwrapsOptionalOutput(CapturedOutput output) {
        GetCardOperation<Optional<Card>> present = operation(name -> true, in -> Optional.of(card(in)));
        GetCardOperation<Optional<Card>> absent = operation(name -> true, in -> Optional.empty());

        present.execute(new CardRequest("4111111111111111"));
        absent.execute(new CardRequest("4111111111111111"));

        assertThat(output.getOut())
                .contains("Operation get-card output: {\"number\":\"************1111\",\"holder\":\"Ann\"}",
                        "Operation get-card output: null");
    }
}
