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
package io.americanexpress.synapse.core.internal.conditional;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.internal.conditional.EntityTags.Parsed;
import io.americanexpress.synapse.core.internal.conditional.EntityTags.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EntityTagsTest {

    @Test
    void parsesStrongWeakListsAndWildcard() {
        assertThat(parse("\"5\"")).isEqualTo(tags(strong("5")));
        assertThat(parse("W/\"5\"")).isEqualTo(tags(weak("5")));
        assertThat(parse("\"5\", W/\"6\"")).isEqualTo(tags(strong("5"), weak("6")));
        assertThat(parse(" \t\"5\"\t,W/\"6\" ")).isEqualTo(tags(strong("5"), weak("6")));
        assertThat(parse("*")).isEqualTo(new Parsed.Any());
        assertThat(parse(" * ")).isEqualTo(new Parsed.Any());
        // Every etagc character: %x21, %x23-7E and obs-text.
        assertThat(parse("\"!#~\u0080ÿ\"")).isEqualTo(tags(strong("!#~\u0080ÿ")));
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
        assertThat(EntityTags.parse(List.of("\"1\"", "\"2\""))).isEqualTo(tags(strong("1"), strong("2")));
        assertThat(EntityTags.parse(List.of("\"1\", W/\"2\"", "\"3\"")))
                .isEqualTo(tags(strong("1"), weak("2"), strong("3")));
        // The wildcard stands alone, even across lines; an empty line is an empty list member.
        assertThatIllegalArgumentException().isThrownBy(() -> EntityTags.parse(List.of("*", "\"1\"")));
        assertThatIllegalArgumentException().isThrownBy(() -> EntityTags.parse(List.of("\"1\"", "")));
    }

    @Test
    void acceptsEmptyQuotedTag() {
        assertThat(parse("\"\"")).isEqualTo(tags(strong("")));
        assertThat(parse("W/\"\"")).isEqualTo(tags(weak("")));
    }

    @Test
    void parsesOneValueAndLines() {
        // One field value, as a gRPC etag field carries it, reads as the same header sent on one line.
        assertThat(EntityTags.parse("\"5\"")).isEqualTo(EntityTags.parse(List.of("\"5\"")));
        assertThat(EntityTags.parse("\"1\", W/\"2\"")).isEqualTo(tags(strong("1"), weak("2")));
        assertThat(EntityTags.parse(List.of("\"1\"", "W/\"2\""))).isEqualTo(EntityTags.parse("\"1\", W/\"2\""));
    }

    @Test
    void starIsAny() {
        assertThat(EntityTags.parse("*")).isInstanceOf(Parsed.Any.class);
        assertThat(EntityTags.parse(List.of("*"))).isEqualTo(new Parsed.Any());
        // A quoted star is a tag whose opaque value is "*", not the wildcard.
        assertThat(EntityTags.parse("\"*\"")).isEqualTo(tags(strong("*")));
    }

    @Test
    void rejectsUnquoted() {
        for (String unquoted : List.of("5", "W/5", "abc", "5, 6", "*5")) {
            assertThatIllegalArgumentException()
                    .as(unquoted)
                    .isThrownBy(() -> EntityTags.parse(unquoted))
                    .withMessage("The value is not an entity tag list (RFC 9110 §8.8.3)");
        }
    }

    @Test
    void strongQuotes() {
        assertThat(EntityTags.strong("7")).isEqualTo("\"7\"");
        assertThat(EntityTags.strong("!#~\u0080ÿ")).isEqualTo("\"!#~\u0080ÿ\"");
        // What strong writes, parse reads back.
        assertThat(EntityTags.parse(EntityTags.strong("v3"))).isEqualTo(tags(strong("v3")));
    }

    @Test
    void strongRejectsANonEtagcChar() {
        for (String opaque : List.of("", "a b", "a\"b", "a\u0001", "a\u007f", "aĀ", "\t")) {
            assertThatIllegalArgumentException()
                    .as(opaque)
                    .isThrownBy(() -> EntityTags.strong(opaque))
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(opaque.isEmpty() ? "\"\"" : opaque));
        }
    }

    @Test
    void etagcIsTheRfcRange() {
        List<Character> allowed = new ArrayList<>();
        for (char c = 0; c < 0x200; c++) {
            if (EntityTags.isEtagc(c)) {
                allowed.add(c);
            }
        }
        assertThat(allowed).hasSize(1 + (0x7e - 0x23 + 1) + (0xff - 0x80 + 1));
        assertThat(EntityTags.isEtagc('!')).isTrue();
        assertThat(EntityTags.isEtagc('"')).isFalse();
        assertThat(EntityTags.isEtagc(' ')).isFalse();
        assertThat(EntityTags.isEtagc('\u007f')).isFalse();
        assertThat(EntityTags.isEtagc('Ā')).isFalse();
    }

    @Test
    void tagsAreCopiedAndNeverEmpty() {
        List<Tag> source = new ArrayList<>(List.of(strong("1")));
        Parsed.Tags parsed = new Parsed.Tags(source);
        source.add(strong("2"));

        assertThat(parsed.tags()).containsExactly(strong("1"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Parsed.Tags(List.of()));
    }

    private static Parsed parse(String fieldValue) {
        return EntityTags.parse(List.of(fieldValue));
    }

    private static Parsed tags(Tag... tags) {
        return new Parsed.Tags(List.of(tags));
    }

    private static Tag strong(String opaque) {
        return new Tag(opaque, false);
    }

    private static Tag weak(String opaque) {
        return new Tag(opaque, true);
    }
}
