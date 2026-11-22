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
package io.americanexpress.synapse.core.internal.masking;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ser.std.ToStringSerializer;

import io.americanexpress.synapse.core.masking.LogMasking;
import io.americanexpress.synapse.core.masking.Masked;

import static org.assertj.core.api.Assertions.assertThat;

/** A {@code @Masked} component is masked whatever serializer it has of its own. */
class MaskingModuleTest {

    private final JsonMapper mapper = JsonMapper.builder().addModule(new MaskingModule()).build();

    record Card(@Masked @JsonSerialize(using = ToStringSerializer.class) Long pan, String holder) {
    }

    record PlainCard(@Masked String pan, @Masked @Nullable String cvv, String holder) {
    }

    record Account(@JsonSerialize(using = ToStringSerializer.class) Long number, @Masked String pin) {
    }

    record Holder(@JsonUnwrapped(prefix = "card_") Card card, String name) {
    }

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    record Row(@Masked @JsonSerialize(using = ToStringSerializer.class) Long pan, @Masked @Nullable String cvv,
            String holder) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Note(@Masked String text, @Masked @Nullable String cvv, String holder) {
    }

    @Test
    void maskedWithItsOwnSerializerIsMasked() {
        assertThat(mapper.writeValueAsString(new Card(4_111_111_111_111_111L, "A")))
                .isEqualTo("{\"pan\":\"****************\",\"holder\":\"A\"}");
        assertThat(LogMasking.standard().toJson(new Card(4_111_111_111_111_111L, "A")))
                .isEqualTo("{\"pan\":\"****************\",\"holder\":\"A\"}");
    }

    @Test
    void maskedWithoutASerializerIsUnchanged() {
        assertThat(mapper.writeValueAsString(new PlainCard("4111111111111111", null, "A")))
                .isEqualTo("{\"pan\":\"****************\",\"cvv\":null,\"holder\":\"A\"}");
    }

    @Test
    void anUnmaskedComponentKeepsItsSerializer() {
        assertThat(mapper.writeValueAsString(new Account(7L, "1234")))
                .isEqualTo("{\"number\":\"7\",\"pin\":\"****\"}");
    }

    /** An unwrapping prefix renames the masked writer, which stays a masked one. */
    @Test
    void aMaskedComponentOfAnUnwrappedRecordIsMasked() {
        assertThat(mapper.writeValueAsString(new Holder(new Card(4_111L, "A"), "n")))
                .isEqualTo("{\"card_pan\":\"****\",\"card_holder\":\"A\",\"name\":\"n\"}");
    }

    /** A record written as an array writes each masked element without its name, and a null as null. */
    @Test
    void aMaskedElementIsMaskedInAnArray() {
        assertThat(mapper.writeValueAsString(new Row(4_111L, null, "A"))).isEqualTo("[\"****\",null,\"A\"]");
    }

    /**
     * A masked writer writes every non-null value, so an inclusion rule such as {@code NON_EMPTY} doesn't omit a masked
     * empty string: it is written masked, as {@code ""}. A null keeps the rule, so it is omitted.
     */
    @Test
    void aMaskedEmptyValueIsWrittenWhateverTheInclusionRule() {
        assertThat(mapper.writeValueAsString(new Note("", null, ""))).isEqualTo("{\"text\":\"\"}");
    }
}
