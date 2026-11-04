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
package io.americanexpress.synapse.core.masking;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LogMaskingTest {

    record Address(String street) {
    }

    record Card(@Masked(MaskType.LAST_FOUR) String number, @Masked @Nullable String cvv,
            @Masked(MaskType.FIXED_LENGTH) String pin, @Masked Integer zip, @Masked List<String> aliases,
            @Masked Address address, String name) {
    }

    record Misc(@Masked UUID id, @Masked Instant at, @Masked boolean flag, @Masked MaskType kind) {
    }

    static class Bean {

        @Masked
        private final String secret = "hunter2";

        public String getSecret() {
            return secret;
        }

        @Masked(MaskType.FIXED_LENGTH)
        public String getOther() {
            return "visible";
        }
    }

    static class Exploding {

        public String getBoom() {
            throw new IllegalStateException("secret 4111");
        }
    }

    private static Card card(@Nullable String cvv) {
        return new Card("4111111111111111", cvv, "9876", 12345, List.of("a", "b"), new Address("1 Main"), "Ann");
    }

    @Test
    void masksAnnotatedComponents() {
        String json = LogMasking.standard().toJson(card("123"));
        assertThat(json)
                .contains("\"number\":\"************1111\"", "\"cvv\":\"***\"", "\"pin\":\"***\"",
                        "\"zip\":\"*****\"", "\"name\":\"Ann\"");
    }

    @Test
    void nonScalarMaskedValuesBecomeFixedMask() {
        String json = LogMasking.standard().toJson(card("123"));
        assertThat(json).contains("\"aliases\":\"***\"", "\"address\":\"***\"").doesNotContain("1 Main");
    }

    @Test
    void nullStaysNull() {
        assertThat(LogMasking.standard().toJson(card(null))).contains("\"cvv\":null");
    }

    @Test
    void masksFieldsAndGetters() {
        String json = LogMasking.standard().toJson(new Bean());
        assertThat(json).contains("\"secret\":\"*******\"", "\"other\":\"***\"").doesNotContain("hunter2", "visible");
    }

    @Test
    void masksOtherScalars() {
        String json = LogMasking
                .standard()
                .toJson(new Misc(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), Instant.EPOCH, true,
                        MaskType.ALL));
        assertThat(json)
                .contains("\"id\":\"" + "*".repeat(36) + "\"", "\"flag\":\"****\"", "\"kind\":\"***\"",
                        "\"at\":\"" + "*".repeat(20) + "\"");
    }

    @Test
    void unannotatedTypesAreUntouched() {
        assertThat(LogMasking.standard().toJson(Map.of("a", "b"))).isEqualTo("{\"a\":\"b\"}");
    }

    @Test
    void nullValueIsNull() {
        assertThat(LogMasking.standard().toJson(null)).isEqualTo("null");
    }

    @Test
    void neverThrows() {
        assertThat(LogMasking.standard().toJson(new Exploding())).isEqualTo("<unserializable Exploding>");
    }

    @Test
    void doesNotChangeBootsMapper() {
        JsonMapper boots = JsonMapper.builder().build();
        LogMasking masking = new LogMasking(boots);
        assertThat(masking.toJson(card("123"))).contains("\"cvv\":\"***\"");
        assertThat(boots.writeValueAsString(card("123")))
                .contains("\"cvv\":\"123\"")
                .contains("\"number\":\"4111111111111111\"");
    }
}
