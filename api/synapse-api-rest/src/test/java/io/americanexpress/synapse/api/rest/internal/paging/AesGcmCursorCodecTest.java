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
package io.americanexpress.synapse.api.rest.internal.paging;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestProperties;
import io.americanexpress.synapse.api.rest.paging.CursorScope;
import io.americanexpress.synapse.api.rest.paging.InvalidCursorException;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.core.paging.SortOrder.Direction;
import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AesGcmCursorCodecTest {

    private static final String KEY_1 = key(1);

    private static final String KEY_2 = key(2);

    private static final String DIGEST = "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12";

    private static final CursorScope SCOPE = new CursorScope("search-books",
            List.of(new SortOrder("TITLE", Direction.ASC)), DIGEST);

    private final MutableClock clock = MutableClock.at("2026-11-04T00:00:00Z");

    private final SecureRandom random = new SecureRandom();

    @Test
    void roundTrips() {
        AesGcmCursorCodec codec = codec(KEY_1, List.of(), null);

        assertThat(codec.decode(codec.encode("raw-7", SCOPE), SCOPE)).isEqualTo("raw-7");
        assertThat(codec.decode(codec.encode("", SCOPE), SCOPE)).isEmpty();
        String unicode = "title=Dune é世😀|id=2";
        assertThat(codec.decode(codec.encode(unicode, SCOPE), SCOPE)).isEqualTo(unicode);
        assertThat(codec.keyGenerated()).isFalse();
    }

    @Test
    void tokenIsOpaqueAndUrlSafe() {
        AesGcmCursorCodec codec = codec(KEY_1, List.of(), null);

        String token = codec.encode("raw-7", SCOPE);
        String again = codec.encode("raw-7", SCOPE);

        assertThat(token).matches("[A-Za-z0-9_-]+");
        byte[] bytes = Base64.getUrlDecoder().decode(token);
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("raw-7");
        // version (1) + key id (4) + nonce (12) + issue time (8) + "raw-7" (5) + tag (16)
        assertThat(bytes).hasSize(46);
        assertThat(bytes[0]).isEqualTo((byte) 1);
        // A new nonce every time.
        assertThat(again).isNotEqualTo(token);
        assertThat(Arrays.copyOfRange(Base64.getUrlDecoder().decode(again), 5, 17))
                .isNotEqualTo(Arrays.copyOfRange(bytes, 5, 17));
    }

    @Test
    void flippedBitIsRejected() {
        AesGcmCursorCodec codec = codec(KEY_1, List.of(), null);
        byte[] bytes = Base64.getUrlDecoder().decode(codec.encode("raw-7", SCOPE));

        for (int i = 0; i < bytes.length; i++) {
            byte[] tampered = bytes.clone();
            tampered[i] ^= 0x01;
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tampered);
            // Byte 0 is the version, bytes 1-4 the key id; the nonce, ciphertext and tag fail authentication.
            String reason = i == 0 ? "malformed" : i <= 4 ? "unknown key id" : "authentication failed";
            int position = i;
            List<ILoggingEvent> lines = debugLines(() -> assertThatExceptionOfType(InvalidCursorException.class)
                    .as("byte %d", position)
                    .isThrownBy(() -> codec.decode(token, SCOPE))
                    .withMessage(reason));
            assertThat(lines)
                    .as("byte %d", i)
                    .singleElement()
                    .satisfies(line -> assertThat(line.getFormattedMessage()).endsWith(": " + reason));
        }
    }

    @Test
    void keyIdSwappedToAPreviousKeyFailsAuthentication() {
        AesGcmCursorCodec codec = codec(KEY_2, List.of(KEY_1), null);
        byte[] bytes = Base64.getUrlDecoder().decode(codec.encode("raw-7", SCOPE));
        byte[] previousId = keyId(KEY_1);
        System.arraycopy(previousId, 0, bytes, 1, previousId.length);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        List<ILoggingEvent> lines = debugLines(() -> assertThatExceptionOfType(InvalidCursorException.class)
                .isThrownBy(() -> codec.decode(token, SCOPE))
                .withMessage("authentication failed"));

        assertThat(lines)
                .singleElement()
                .satisfies(line -> assertThat(line.getFormattedMessage()).endsWith(": authentication failed"));
    }

    @Test
    void otherOperationSortOrDigestIsRejected() {
        AesGcmCursorCodec codec = codec(KEY_1, List.of(), null);
        String token = codec.encode("raw-7", SCOPE);

        List<CursorScope> others = List
                .of(new CursorScope("other", SCOPE.sort(), DIGEST),
                        new CursorScope("search-books", List.of(new SortOrder("TITLE", Direction.DESC)), DIGEST),
                        new CursorScope("search-books", List.of(), DIGEST),
                        new CursorScope("search-books",
                                List.of(new SortOrder("TITLE", Direction.ASC), new SortOrder("YEAR", Direction.ASC)),
                                DIGEST),
                        new CursorScope("search-books", SCOPE.sort(), "cd34" + DIGEST.substring(4)));
        for (CursorScope other : others) {
            List<ILoggingEvent> lines = debugLines(() -> assertThatExceptionOfType(InvalidCursorException.class)
                    .as("%s", other)
                    .isThrownBy(() -> codec.decode(token, other))
                    .withMessage("authentication failed"));
            assertThat(lines)
                    .singleElement()
                    .satisfies(line -> assertThat(line.getFormattedMessage())
                            .contains("authentication failed"));
        }
    }

    @Test
    void lengthPrefixesKeepTheScopeUnambiguous() {
        AesGcmCursorCodec codec = codec(KEY_1, List.of(), null);
        // Concatenated without length prefixes, both scopes would be "ab" + "" + "c".
        CursorScope issued = new CursorScope("ab", List.of(), "c");
        CursorScope shifted = new CursorScope("a", List.of(), "bc");
        String token = codec.encode("raw-7", issued);

        assertThat(codec.decode(token, issued)).isEqualTo("raw-7");
        assertThatExceptionOfType(InvalidCursorException.class).isThrownBy(() -> codec.decode(token, shifted));
    }

    @Test
    void previousKeyDecryptsAndCurrentKeyEncrypts() {
        AesGcmCursorCodec a = codec(KEY_1, List.of(), null);
        AesGcmCursorCodec b = codec(KEY_2, List.of(KEY_1), null);
        String fromA = a.encode("raw-7", SCOPE);

        assertThat(b.decode(fromA, SCOPE)).isEqualTo("raw-7");
        byte[] fromB = Base64.getUrlDecoder().decode(b.encode("raw-8", SCOPE));
        assertThat(Arrays.copyOfRange(fromB, 1, 5)).isEqualTo(keyId(KEY_2)).isNotEqualTo(keyId(KEY_1));
        assertThat(Arrays.copyOfRange(Base64.getUrlDecoder().decode(fromA), 1, 5))
                .isEqualTo(keyId(KEY_1));
        // A only knows key 1.
        assertThatExceptionOfType(InvalidCursorException.class)
                .isThrownBy(() -> a.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(fromB), SCOPE));
    }

    @Test
    void unknownKeyIdIsRejected() {
        String token = codec(KEY_1, List.of(), null).encode("raw-7", SCOPE);
        AesGcmCursorCodec other = codec(KEY_2, List.of(), null);

        List<ILoggingEvent> lines = debugLines(() -> assertThatExceptionOfType(InvalidCursorException.class)
                .isThrownBy(() -> other.decode(token, SCOPE))
                .withMessage("unknown key id"));

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(line.getLoggerName()).isEqualTo("io.americanexpress.synapse.api.rest");
            assertThat(line.getFormattedMessage())
                    .contains("unknown key id")
                    .doesNotContain(token)
                    .doesNotContain("raw-7");
            assertThat(line.getThrowableProxy()).isNull();
        });
    }

    @Test
    void expiredTokenIsRejectedOnlyWithMaxAge() {
        AesGcmCursorCodec expiring = codec(KEY_1, List.of(), Duration.ofHours(1));
        AesGcmCursorCodec lasting = codec(KEY_1, List.of(), null);
        String token = expiring.encode("raw-7", SCOPE);

        clock.advance(Duration.ofHours(1));
        assertThat(expiring.decode(token, SCOPE)).isEqualTo("raw-7");
        clock.advance(Duration.ofSeconds(1));
        List<ILoggingEvent> lines = debugLines(() -> assertThatExceptionOfType(InvalidCursorException.class)
                .isThrownBy(() -> expiring.decode(token, SCOPE))
                .withMessage("expired"));
        assertThat(lines).singleElement().satisfies(line -> assertThat(line.getFormattedMessage()).contains("expired"));
        clock.advance(Duration.ofHours(1));
        assertThatExceptionOfType(InvalidCursorException.class).isThrownBy(() -> expiring.decode(token, SCOPE));
        assertThat(lasting.decode(token, SCOPE)).isEqualTo("raw-7");
    }

    @Test
    void malformedTokensAreRejected() {
        AesGcmCursorCodec codec = codec(KEY_1, List.of(), null);
        byte[] valid = Base64.getUrlDecoder().decode(codec.encode("raw-7", SCOPE));
        byte[] wrongVersion = valid.clone();
        wrongVersion[0] = 2;
        // The shortest token with a version and key id that is too short to hold a nonce, issue time and tag.
        byte[] truncated = Arrays.copyOf(valid, 40);

        List<String> tokens = List
                .of("", "!!", "AA", "A".repeat(1025), "abc+/def",
                        Base64.getUrlEncoder().withoutPadding().encodeToString(wrongVersion),
                        Base64.getUrlEncoder().withoutPadding().encodeToString(truncated));
        for (String token : tokens) {
            List<ILoggingEvent> lines = debugLines(() -> assertThatExceptionOfType(InvalidCursorException.class)
                    .as("%s", token)
                    .isThrownBy(() -> codec.decode(token, SCOPE))
                    .withMessage("malformed"));
            assertThat(lines)
                    .singleElement()
                    .satisfies(line -> assertThat(line.getFormattedMessage())
                            .contains("malformed"));
        }
    }

    @Test
    void longestTokenIsDecoded() {
        AesGcmCursorCodec codec = codec(KEY_1, List.of(), null);
        String longest = codec.encode("x".repeat(512), SCOPE);

        assertThat(longest.length()).isLessThanOrEqualTo(1024);
        assertThat(codec.decode(longest, SCOPE)).isEqualTo("x".repeat(512));
    }

    @Test
    void oversizedRawCursorFailsEncode() {
        AesGcmCursorCodec codec = codec(KEY_1, List.of(), null);

        assertThatIllegalStateException()
                .isThrownBy(() -> codec.encode("x".repeat(513), SCOPE))
                .withMessageContaining("512")
                .withMessageNotContaining("xxx");
        // The limit is in UTF-8 bytes: 257 characters of 2 bytes each.
        assertThatIllegalStateException().isThrownBy(() -> codec.encode("é".repeat(257), SCOPE));
        assertThat(codec.decode(codec.encode("é".repeat(256), SCOPE), SCOPE)).isEqualTo("é".repeat(256));
    }

    @Test
    void reportsAGeneratedKey() {
        CursorKeys keys = CursorKeys.from(new SynapseRestProperties.Paging.Cursor(null, List.of(), null), random);
        AesGcmCursorCodec codec = new AesGcmCursorCodec(keys, clock, null, random);

        assertThat(codec.keyGenerated()).isTrue();
        assertThat(codec.decode(codec.encode("raw-7", SCOPE), SCOPE)).isEqualTo("raw-7");
    }

    @Test
    void scopeNormalizesTheSort() {
        CursorScope scope = new CursorScope("search-books",
                List.of(new SortOrder("TITLE", Direction.ASC), new SortOrder("PUBLISHED_YEAR", Direction.DESC)),
                DIGEST);

        assertThat(scope.normalizedSort()).isEqualTo("TITLE:ASC,PUBLISHED_YEAR:DESC");
        assertThat(new CursorScope("search-books", List.of(), DIGEST).normalizedSort()).isEmpty();
    }

    private AesGcmCursorCodec codec(String key, List<String> previousKeys, @Nullable Duration maxAge) {
        CursorKeys keys = CursorKeys.from(new SynapseRestProperties.Paging.Cursor(key, previousKeys, maxAge), random);
        return new AesGcmCursorCodec(keys, clock, maxAge, random);
    }

    private byte[] keyId(String key) {
        return CursorKeys
                .from(new SynapseRestProperties.Paging.Cursor(key, List.of(), null), random)
                .current()
                .id();
    }

    /** A base64 32-byte key whose bytes depend on the seed. */
    private static String key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 31 + i * 7);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static List<ILoggingEvent> debugLines(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level level = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            action.run();
        } finally {
            logger.setLevel(level);
            logger.detachAppender(appender);
        }
        return new ArrayList<>(appender.list);
    }
}
