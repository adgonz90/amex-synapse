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
package io.americanexpress.synapse.core.internal.paging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CursorKeysTest {

    private static final String PROPERTY = "synapse.paging.cursor.key";

    private final SecureRandom random = new SecureRandom();

    @Test
    void decodesStandardAndUrlSafeBase64() throws NoSuchAlgorithmException {
        byte[] bytes = new byte[32];
        // fb ef be encodes as "++++" and ff ff ff as "////", so the two alphabets differ.
        bytes[0] = (byte) 0xfb;
        bytes[1] = (byte) 0xef;
        bytes[2] = (byte) 0xbe;
        bytes[3] = (byte) 0xff;
        bytes[4] = (byte) 0xff;
        bytes[5] = (byte) 0xff;
        for (int i = 6; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 11);
        }
        String standard = Base64.getEncoder().encodeToString(bytes);
        String urlSafe = Base64.getUrlEncoder().encodeToString(bytes);
        assertThat(standard).contains("+", "/", "=");
        assertThat(urlSafe).contains("-", "_", "=");

        byte[] expectedId = Arrays
                .copyOf(MessageDigest
                        .getInstance("SHA-256")
                        .digest(concat("synapse-cursor-key-id:".getBytes(StandardCharsets.US_ASCII), bytes)), 4);
        for (String text : List.of(standard, standard.replace("=", ""), urlSafe, urlSafe.replace("=", ""))) {
            CursorKeys keys = keys(text, List.of());
            assertThat(keys.current().id()).as(text).isEqualTo(expectedId);
            assertThat(keys.current().secret().getEncoded()).as(text).isEqualTo(bytes);
            assertThat(keys.current().secret().getAlgorithm()).isEqualTo("AES");
            assertThat(keys.generated()).isFalse();
            assertThat(keys.previous()).isEmpty();
        }
    }

    @Test
    void rejectsWrongLengthWithoutEchoingTheKey() {
        String sixteen = Base64.getEncoder().encodeToString(new byte[16]);
        String thirtyThree = Base64.getEncoder().encodeToString(new byte[33]);
        for (String key : List.of(sixteen, thirtyThree, "not base64!", "abc+def_ghi", " " + sixteen + "\n")) {
            assertThatExceptionOfType(SynapseConfigurationException.class)
                    .as(key)
                    .isThrownBy(() -> keys(key, List.of()))
                    .satisfies(e -> {
                        assertThat(e.description()).contains(PROPERTY).contains("32 bytes");
                        assertThat(e.action()).contains(PROPERTY).contains("openssl rand -base64 32");
                        assertThat(e.description()).doesNotContain(key.strip());
                        assertThat(e.action()).doesNotContain(key.strip());
                        assertThat(e.getCause()).isNull();
                    });
        }
        String good = Base64.getEncoder().encodeToString(new byte[32]);
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> keys(good, List.of(good.replace("A", "B"), sixteen)))
                .satisfies(e -> {
                    assertThat(e.description()).contains(PROPERTY.replace("key", "previous-keys[1]"));
                    assertThat(e.description()).doesNotContain(sixteen);
                });
    }

    @Test
    void blankKeyIsUnset() {
        for (String blank : List.of("", "   ", "\n")) {
            CursorKeys keys = keys(blank, List.of());

            assertThat(keys.generated()).as("'%s'", blank).isTrue();
            assertThat(keys.current().secret().getEncoded()).hasSize(32);
            assertThat(keys.current().id()).hasSize(4);
        }
    }

    @Test
    void trimsWhitespaceAroundKeys() {
        String valid = Base64.getEncoder().encodeToString(filled(1));
        String previous = Base64.getUrlEncoder().withoutPadding().encodeToString(filled(2));
        CursorKeys plain = keys(valid, List.of(previous));

        // A trailing newline is what "openssl rand -base64 32 > file" leaves.
        CursorKeys padded = keys(" " + valid + "\n", List.of("\t" + previous + "\r\n"));

        assertThat(padded.generated()).isFalse();
        assertThat(padded.current().id()).isEqualTo(plain.current().id());
        assertThat(padded.current().secret().getEncoded()).isEqualTo(filled(1));
        assertThat(padded.previous()).singleElement().satisfies(key -> {
            assertThat(key.id()).isEqualTo(plain.previous().get(0).id());
            assertThat(key.secret().getEncoded()).isEqualTo(filled(2));
        });
    }

    @Test
    void ignoresBlankPreviousKeys() {
        String valid = Base64.getEncoder().encodeToString(filled(1));
        String previous = Base64.getEncoder().encodeToString(filled(2));
        String sixteen = Base64.getEncoder().encodeToString(new byte[16]);

        CursorKeys keys = keys(valid, List.of("", previous, "  "));

        assertThat(keys.previous())
                .singleElement()
                .satisfies(key -> assertThat(key.secret().getEncoded())
                        .isEqualTo(filled(2)));
        // Blank entries keep their index, so a bad entry is still named by its position in the list.
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> keys(valid, List.of(" ", sixteen)))
                .satisfies(e -> assertThat(e.description())
                        .contains("synapse.paging.cursor.previous-keys[1]")
                        .doesNotContain(sixteen));
    }

    @Test
    void rejectsDuplicateKeyIds() {
        String current = Base64.getEncoder().encodeToString(new byte[32]);
        String other = Base64.getUrlEncoder().withoutPadding().encodeToString(filled(9));

        // The same key in another alphabet still has the same id.
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> keys(current, List.of(other, current.replace("=", ""))))
                .satisfies(e -> {
                    assertThat(e.description())
                            .contains("synapse.paging.cursor.previous-keys[1]")
                            .contains(PROPERTY)
                            .doesNotContain(current.replace("=", ""));
                    assertThat(e.action()).contains("previous-keys");
                });
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> keys(current, List.of(other, other)))
                .satisfies(e -> assertThat(e.description())
                        .contains("synapse.paging.cursor.previous-keys[1]")
                        .contains("synapse.paging.cursor.previous-keys[0]")
                        .doesNotContain(other));
    }

    @Test
    void generatesAKeyWhenUnset() {
        SecureRandom fixed = new SecureRandom() {
            private static final long serialVersionUID = 1L;

            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, (byte) 7);
            }
        };

        CursorKeys keys = CursorKeys.from(new SynapsePagingProperties.Cursor(null, List.of(), null), fixed);

        assertThat(keys.generated()).isTrue();
        assertThat(keys.current().secret().getEncoded()).isEqualTo(filled(7));
        assertThat(keys.current().id()).hasSize(4);
        assertThat(keys.previous()).isEmpty();
        assertThat(CursorKeys
                .from(new SynapsePagingProperties.Cursor(null, List.of(), null), random)
                .current()
                .secret()
                .getEncoded()).hasSize(32).isNotEqualTo(filled(7));
    }

    @Test
    void generatedKeyStillReadsPreviousKeys() {
        String previous = Base64.getEncoder().encodeToString(filled(3));

        CursorKeys keys = CursorKeys
                .from(new SynapsePagingProperties.Cursor(null, List.of(previous), null), random);

        assertThat(keys.generated()).isTrue();
        assertThat(keys.previous())
                .singleElement()
                .satisfies(key -> assertThat(key.secret().getEncoded())
                        .isEqualTo(filled(3)));
    }

    @Test
    void findsCurrentAndPreviousKeysById() {
        CursorKeys keys = keys(Base64.getEncoder().encodeToString(filled(1)),
                List.of(Base64.getEncoder().encodeToString(filled(2))));
        CursorKeys.Key current = keys.current();
        CursorKeys.Key previous = keys.previous().get(0);

        assertThat(keys.find(current.id())).isSameAs(current);
        assertThat(keys.find(previous.id())).isSameAs(previous);
        assertThat(keys.find(new byte[4])).isNull();
        assertThat(keys.find(new byte[3])).isNull();
        // The id is copied in and out.
        current.id()[0] ^= 1;
        assertThat(keys.find(current.id())).isSameAs(current);
        // SecretKeySpec's hash code is computed from the key bytes, so toString leaves the secret out entirely.
        assertThat(current.toString()).isEqualTo("Key[id=" + HexFormat.of().formatHex(current.id()) + "]");
        assertThat(keys.toString()).doesNotContain("SecretKey").contains("generated=false");
    }

    private CursorKeys keys(String key, List<String> previousKeys) {
        return CursorKeys.from(new SynapsePagingProperties.Cursor(key, previousKeys, null), random);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
