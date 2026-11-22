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

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class VersionTokensTest {

    private static final LocalDateTime STAMP = LocalDateTime.of(2026, 11, 7, 15, 4, 5);

    @Test
    void formatsAndParsesEveryVersionType() {
        Map<Object, String> tokens = Map
                .ofEntries(Map.entry("v-7", "v-7"), Map.entry((byte) -3, "-3"), Map.entry((short) 12, "12"),
                        Map.entry(42, "42"), Map.entry(7L, "7"), Map
                                .entry(new BigInteger("123456789012345678901"),
                                        "123456789012345678901"),
                        Map.entry(Instant.parse("2026-11-07T10:15:30.5Z"), "2026-11-07T10:15:30.500Z"),
                        Map.entry(LocalDate.of(2026, 11, 7), "2026-11-07"),
                        Map.entry(STAMP, "2026-11-07T15:04:05"),
                        Map.entry(LocalTime.of(15, 4, 5), "15:04:05"),
                        Map.entry(OffsetDateTime.of(STAMP, ZoneOffset.ofHours(2)), "2026-11-07T15:04:05+02:00"),
                        Map.entry(OffsetTime.of(15, 4, 5, 0, ZoneOffset.UTC), "15:04:05Z"),
                        Map
                                .entry(ZonedDateTime.of(STAMP, ZoneId.of("Europe/Paris")),
                                        "2026-11-07T15:04:05+01:00[Europe/Paris]"),
                        Map.entry(Year.of(2026), "2026"), Map.entry(YearMonth.of(2026, 11), "2026-11"));

        tokens.forEach((value, token) -> {
            assertThat(VersionTokens.supports(value.getClass())).as(token).isTrue();
            assertThat(VersionTokens.format(value)).as(token).isEqualTo(token);
            assertThat(VersionTokens.parse(token, value.getClass())).as(token).isEqualTo(value);
            // Every token is an entity tag value.
            assertThat(EntityTags.strong(token)).isEqualTo("\"" + token + "\"");
        });
        assertThat(VersionTokens.supports(long.class)).isTrue();
        assertThat(VersionTokens.parse("7", long.class)).isEqualTo(7L);
        assertThat(VersionTokens.supports(CharSequence.class)).isTrue();
        assertThat(VersionTokens.parse("v-7", CharSequence.class)).isEqualTo("v-7");
    }

    @Test
    void integralNumbersArePlainDecimal() {
        for (String token : List.of("+5", "007", "-0", "0x5", "#5", "5.0", "5e2", " 5", "5 ", "", "-", "1_000")) {
            assertThatIllegalArgumentException().as(token).isThrownBy(() -> VersionTokens.parse(token, Long.class));
        }
        assertThat(VersionTokens.parse("0", Long.class)).isEqualTo(0L);
        assertThat(VersionTokens.parse("-12", Integer.class)).isEqualTo(-12);
        assertThatIllegalArgumentException().isThrownBy(() -> VersionTokens.parse("2147483648", Integer.class));
        assertThatIllegalArgumentException().isThrownBy(() -> VersionTokens.parse("128", Byte.class));
    }

    @Test
    void javaTimeIsIsoInEveryLocale() {
        Locale saved = Locale.getDefault();
        try {
            for (Locale locale : List
                    .of(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar-EG"),
                            Locale.forLanguageTag("th-TH-u-ca-buddhist"), Locale.forLanguageTag("hi-IN"))) {
                Locale.setDefault(locale);
                assertThat(VersionTokens.format(STAMP)).as(locale.toLanguageTag()).isEqualTo("2026-11-07T15:04:05");
                assertThat(VersionTokens.format(LocalDate.of(2026, 11, 7))).isEqualTo("2026-11-07");
                assertThat(VersionTokens.format(1234567L)).isEqualTo("1234567");
                assertThat(VersionTokens.parse("2026-11-07", LocalDate.class)).isEqualTo(LocalDate.of(2026, 11, 7));
            }
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void javaTimeParsesIsoOnly() {
        for (String token : List.of("11/7/26", "07/11/2026", "2026-11-07 15:04:05", "2026-13-01", "")) {
            assertThatIllegalArgumentException()
                    .as(token)
                    .isThrownBy(() -> VersionTokens.parse(token, LocalDate.class))
                    .withMessageNotContaining(token.isEmpty() ? "\u0000" : token);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> VersionTokens.parse("15:04", LocalDateTime.class));
    }

    @Test
    void stringsAreCheckedAgainstEtagc() {
        for (String bad : List.of("", "a b", "a\"b", "a\u0001", "a\u007f", "aĀ", "11/7/26, 3:04 PM")) {
            assertThatIllegalArgumentException()
                    .as(bad)
                    .isThrownBy(() -> VersionTokens.format(bad))
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(bad.isEmpty() ? "\u0000" : bad));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> VersionTokens.format(""))
                .withMessage("The version token is empty");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> VersionTokens.format("a b"))
                .withMessage("The version token has a character an entity tag can't carry (a double quote, a space, "
                        + "a control character, DEL or a character above U+00FF)");
        // An empty value names no version, for a String as for a number.
        assertThatIllegalArgumentException().isThrownBy(() -> VersionTokens.parse("", String.class));
    }

    @Test
    void otherTypesAreNotVersions() {
        for (Class<?> type : List
                .of(Double.class, double.class, Float.class, BigDecimal.class, Boolean.class,
                        TimeUnit.class, UUID.class, Object.class, Serializable.class, Comparable.class,
                        java.time.Duration.class,
                        List.class)) {
            assertThat(VersionTokens.supports(type)).as(type.getName()).isFalse();
            assertThatIllegalArgumentException()
                    .as(type.getName())
                    .isThrownBy(() -> VersionTokens.parse("1", type))
                    .withMessageContaining("is not a version type");
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> VersionTokens.format(1.5))
                .withMessage("Double is not a version type: a version is " + VersionTokens.TYPES);
    }
}
