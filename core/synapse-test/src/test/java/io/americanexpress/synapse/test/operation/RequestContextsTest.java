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
package io.americanexpress.synapse.test.operation;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.context.RequestContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestContextsTest {

    @Test
    void contextIsClearedAfterRun() {
        RequestContexts.with().correlationId("c-1").run(() -> assertThat(RequestContext.find()).isPresent());

        assertThat(RequestContext.find()).isEmpty();
    }

    @Test
    void contextIsClearedWhenTheBodyThrows() {
        assertThatThrownBy(() -> RequestContexts.with().run(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(RequestContext.find()).isEmpty();
    }

    @Test
    void contextCarriesEveryGivenValue() {
        Principal principal = () -> "tony";
        Instant deadline = Instant.parse("2026-01-01T00:00:05Z");

        RequestContext seen = RequestContexts
                .with()
                .correlationId("c-2")
                .principal(principal)
                .locale(Locale.FRANCE)
                .deadline(deadline)
                .attribute("tenant", "acme")
                .call(RequestContext::current);

        assertThat(seen.correlationId()).isEqualTo("c-2");
        assertThat(seen.principal()).contains(principal);
        assertThat(seen.locale()).contains(Locale.FRANCE);
        assertThat(seen.deadline()).contains(deadline);
        assertThat(seen.attribute("tenant")).contains("acme");
    }

    @Test
    void keepsAttributesInTheOrderTheyWereAdded() {
        RequestContexts.Builder builder = RequestContexts.with();
        List<String> names = new ArrayList<>();
        for (int i = 20; i > 0; i--) {
            names.add("attribute-" + i);
            builder.attribute("attribute-" + i, "v");
        }

        Map<String, String> seen = builder.call(() -> RequestContext.current().attributes());

        // Like an inbound edge, the context keeps the first 16 attributes and drops the rest.
        assertThat(seen.keySet()).containsExactlyElementsOf(names.subList(0, 16));
    }

    @Test
    void callReturnsTheResult() {
        assertThat(RequestContexts.with().call(() -> 42)).isEqualTo(42);
    }

    @Test
    void outerContextIsRestoredAfterANestedOne() {
        RequestContexts.with().correlationId("outer").run(() -> {
            RequestContexts
                    .with()
                    .correlationId("inner")
                    .run(
                            () -> assertThat(RequestContext.current().correlationId()).isEqualTo("inner"));

            assertThat(RequestContext.current().correlationId()).isEqualTo("outer");
        });

        assertThat(RequestContext.find()).isEmpty();
    }
}
