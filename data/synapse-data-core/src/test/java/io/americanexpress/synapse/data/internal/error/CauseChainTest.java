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
package io.americanexpress.synapse.data.internal.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class CauseChainTest {

    /** STALE_VERSION for an {@link IllegalStateException}, ALREADY_EXISTS for an {@link IllegalArgumentException}. */
    private static final Function<Throwable, @Nullable SynapseErrors> ERRORS = CauseChainTest::errorFor;

    @Test
    void theDepthIsTen() {
        assertThat(CauseChain.MAX_DEPTH).isEqualTo(10);
    }

    @Test
    void translatesTheThrownExceptionKeepingItAsTheCause() {
        IllegalStateException thrown = new IllegalStateException("stale");

        SynapseException translated = translated(thrown, ERRORS);

        assertThat(translated).hasCode(SynapseErrors.STALE_VERSION);
        assertThat(translated.getMessage()).isEqualTo(SynapseErrors.STALE_VERSION.messageTemplate());
        assertThat(translated.args()).isEmpty();
        assertThat(translated.getCause()).isSameAs(thrown);
    }

    @Test
    void translatesACauseKeepingTheThrownExceptionAsTheCause() {
        RuntimeException thrown = new RuntimeException("at commit", new IllegalArgumentException("duplicate"));

        SynapseException translated = translated(thrown, ERRORS);

        assertThat(translated).hasCode(SynapseErrors.ALREADY_EXISTS);
        assertThat(translated.getCause()).isSameAs(thrown);
    }

    @Test
    void theOutermostMatchWins() {
        IllegalStateException thrown = new IllegalStateException("stale", new IllegalArgumentException("duplicate"));

        assertThat(translated(thrown, ERRORS)).hasCode(SynapseErrors.STALE_VERSION);
    }

    @Test
    void looksAtTheThrownExceptionAndTenCauses() {
        List<String> seen = new ArrayList<>();

        Optional<SynapseException> translation = translation(wrapped(new RuntimeException("innermost"), 12),
                exception -> {
                    seen.add(String.valueOf(exception.getMessage()));
                    return null;
                });

        assertThat(translation).isEmpty();
        assertThat(seen).hasSize(11).startsWith("wrapper 11").endsWith("wrapper 1");
    }

    @Test
    void findsAMatchTenCausesDeepButNotEleven() {
        assertThat(translation(wrapped(new IllegalStateException("stale"), 10), ERRORS)).isPresent();
        assertThat(translation(wrapped(new IllegalStateException("stale"), 11), ERRORS)).isEmpty();
    }

    @Test
    void aCauseCycleEnds() {
        IllegalStateException first = new IllegalStateException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(translation(second, exception -> null)).isEmpty();
    }

    private static @Nullable SynapseErrors errorFor(Throwable exception) {
        if (exception instanceof IllegalStateException) {
            return SynapseErrors.STALE_VERSION;
        }
        return exception instanceof IllegalArgumentException ? SynapseErrors.ALREADY_EXISTS : null;
    }

    private static Optional<SynapseException> translation(Throwable thrown,
            Function<Throwable, @Nullable SynapseErrors> errors) {
        return Optional.ofNullable(CauseChain.translate(thrown, errors));
    }

    private static SynapseException translated(Throwable thrown, Function<Throwable, @Nullable SynapseErrors> errors) {
        return translation(thrown, errors)
                .orElseThrow(() -> new AssertionError("Expected a translation of " + thrown.getClass().getName()));
    }

    /** Wraps the exception in {@code depth} runtime exceptions, so it is that many causes deep. */
    private static RuntimeException wrapped(RuntimeException exception, int depth) {
        RuntimeException current = exception;
        for (int i = 0; i < depth; i++) {
            current = new RuntimeException("wrapper " + i, current);
        }
        return current;
    }
}
