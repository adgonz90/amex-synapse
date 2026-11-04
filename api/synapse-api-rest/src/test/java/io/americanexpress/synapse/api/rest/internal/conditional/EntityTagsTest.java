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
package io.americanexpress.synapse.api.rest.internal.conditional;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.ETag;

import io.americanexpress.synapse.api.rest.internal.conditional.EntityTags.Parsed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EntityTagsTest {

    @Test
    void parsesStrongWeakListsAndWildcard() {
        assertThat(parse("\"5\"")).isEqualTo(new Parsed(false, List.of(strong("5"))));
        assertThat(parse("W/\"5\"")).isEqualTo(new Parsed(false, List.of(weak("5"))));
        assertThat(parse("\"5\", W/\"6\"")).isEqualTo(new Parsed(false, List.of(strong("5"), weak("6"))));
        assertThat(parse(" \t\"5\"\t,W/\"6\" ")).isEqualTo(new Parsed(false, List.of(strong("5"), weak("6"))));
        assertThat(parse("*")).isEqualTo(new Parsed(true, List.of()));
        assertThat(parse(" * ")).isEqualTo(new Parsed(true, List.of()));
        // Every etagc character: %x21, %x23-7E and obs-text.
        assertThat(parse("\"!#~\u0080ÿ\"").tags()).containsExactly(strong("!#~\u0080ÿ"));
    }

    @Test
    void rejectsWhatSpringAccepts() {
        for (String malformed : List
                .of("\"5\"x", "5", "\"a b\"", "\"\"\"", "*, \"5\"", "\"5\", *", "", " ", "\"5\",,", "\"5\",",
                        ",\"5\"", "\"5", "w/\"5\"", "W/ \"5\"", "W/5", "\"5\" \"6\"", "**", "\"a\u0001\"",
                        "\"a\u007f\"", "\"aĀ\"")) {
            assertThatIllegalArgumentException().as(malformed).isThrownBy(() -> parse(malformed));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> EntityTags.parse(List.of()));
    }

    @Test
    void joinsSeveralHeaderLines() {
        assertThat(EntityTags.parse(List.of("\"1\"", "\"2\"")))
                .isEqualTo(new Parsed(false, List.of(strong("1"), strong("2"))));
        assertThat(EntityTags.parse(List.of("\"1\", W/\"2\"", "\"3\"")).tags())
                .containsExactly(strong("1"), weak("2"), strong("3"));
        // The wildcard stands alone, even across lines; an empty line is an empty list member.
        assertThatIllegalArgumentException().isThrownBy(() -> EntityTags.parse(List.of("*", "\"1\"")));
        assertThatIllegalArgumentException().isThrownBy(() -> EntityTags.parse(List.of("\"1\"", "")));
    }

    @Test
    void acceptsEmptyQuotedTag() {
        assertThat(parse("\"\"")).isEqualTo(new Parsed(false, List.of(strong(""))));
        assertThat(parse("W/\"\"")).isEqualTo(new Parsed(false, List.of(weak(""))));
    }

    private static Parsed parse(String fieldValue) {
        return EntityTags.parse(List.of(fieldValue));
    }

    private static ETag strong(String tag) {
        return new ETag(tag, false);
    }

    private static ETag weak(String tag) {
        return new ETag(tag, true);
    }
}
