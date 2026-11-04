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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.GCMParameterSpec;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.americanexpress.synapse.api.rest.paging.CursorCodec;
import io.americanexpress.synapse.api.rest.paging.CursorScope;
import io.americanexpress.synapse.api.rest.paging.InvalidCursorException;

/**
 * The default cursor codec: AES-256-GCM authenticated encryption, bound to the cursor scope.
 *
 * <p>A token is base64url without padding of {@code 0x01 ‖ key id (4) ‖ nonce (12) ‖ ciphertext ‖ tag (16)}. The
 * plaintext is the issue time (8 bytes, big-endian epoch seconds) followed by the raw cursor in UTF-8. The additional
 * authenticated data is {@code 0x01 ‖ key id ‖ "synapse-cursor" ‖ operation ‖ normalized sort ‖ input digest}, where
 * the operation and the sort are each prefixed by their UTF-8 length as a 4-byte big-endian integer, so the scope
 * can't be read two ways. Every token gets a new 96-bit nonce from the random source, and every call a new
 * {@link Cipher}. The scope is not stored in the token: a token decrypts only with the scope it was issued for.
 *
 * <p>A rejected token logs one DEBUG line on {@code io.americanexpress.synapse.api.rest} with the reason (malformed,
 * unknown key id, authentication failed or expired), never the token or its contents.
 */
public final class AesGcmCursorCodec implements CursorCodec {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    private static final byte VERSION = 0x01;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int NONCE_LENGTH = 12;

    private static final int TAG_BITS = 128;

    private static final int TAG_LENGTH = TAG_BITS / 8;

    private static final int ISSUED_AT_LENGTH = Long.BYTES;

    /** Version, key id and nonce. */
    private static final int HEADER_LENGTH = 1 + CursorKeys.ID_LENGTH + NONCE_LENGTH;

    /** A token for an empty raw cursor: header, issue time and tag. */
    private static final int MIN_TOKEN_BYTES = HEADER_LENGTH + ISSUED_AT_LENGTH + TAG_LENGTH;

    /** The longest raw cursor, in UTF-8 bytes. */
    private static final int MAX_CURSOR_BYTES = 512;

    /** The longest token accepted, in characters. */
    private static final int MAX_TOKEN_LENGTH = 1024;

    private static final byte[] CONTEXT = "synapse-cursor".getBytes(StandardCharsets.US_ASCII);

    private final CursorKeys keys;

    private final Clock clock;

    private final @Nullable Duration maxAge;

    private final SecureRandom random;

    /**
     * Creates the codec.
     *
     * @param keys the keys: the current one encrypts, every one decrypts
     * @param clock the clock of the issue time and the expiry check
     * @param maxAge how long a token stays valid after it is issued, or {@code null} for no expiry
     * @param random the source of the nonces
     */
    public AesGcmCursorCodec(CursorKeys keys, Clock clock, @Nullable Duration maxAge, SecureRandom random) {
        this.keys = keys;
        this.clock = clock;
        this.maxAge = maxAge;
        this.random = random;
    }

    /**
     * Returns whether the key was generated at startup, so tokens fail after a restart or on another instance.
     *
     * @return whether {@code synapse.api.rest.paging.cursor.key} is unset
     */
    public boolean keyGenerated() {
        return keys.generated();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException when the raw cursor is longer than 512 UTF-8 bytes, which is a bug in the
     *     operation
     */
    @Override
    public String encode(String cursor, CursorScope scope) {
        byte[] raw = cursor.getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_CURSOR_BYTES) {
            throw new IllegalStateException("The operation " + scope.operation() + " returned a cursor of "
                    + raw.length + " UTF-8 bytes; the most a cursor may have is " + MAX_CURSOR_BYTES);
        }
        CursorKeys.Key key = keys.current();
        byte[] id = key.id();
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        byte[] plaintext = ByteBuffer
                .allocate(ISSUED_AT_LENGTH + raw.length)
                .putLong(clock.instant().getEpochSecond())
                .put(raw)
                .array();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key.secret(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(id, scope));
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] token = ByteBuffer
                    .allocate(HEADER_LENGTH + sealed.length)
                    .put(VERSION)
                    .put(id)
                    .put(nonce)
                    .put(sealed)
                    .array();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256-GCM failed to encrypt a cursor", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(raw, (byte) 0);
        }
    }

    @Override
    public String decode(String token, CursorScope scope) {
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw reject("malformed");
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw reject("malformed");
        }
        if (bytes.length < MIN_TOKEN_BYTES || bytes[0] != VERSION) {
            throw reject("malformed");
        }
        byte[] id = Arrays.copyOfRange(bytes, 1, 1 + CursorKeys.ID_LENGTH);
        CursorKeys.Key key = keys.find(id);
        if (key == null) {
            throw reject("unknown key id");
        }
        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher
                    .init(Cipher.DECRYPT_MODE, key.secret(),
                            new GCMParameterSpec(TAG_BITS, bytes, 1 + CursorKeys.ID_LENGTH, NONCE_LENGTH));
            cipher.updateAAD(aad(id, scope));
            plaintext = decrypt(cipher, bytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256-GCM failed to decrypt a cursor", e);
        }
        if (plaintext == null) {
            throw reject("authentication failed");
        }
        try {
            Instant issuedAt = Instant.ofEpochSecond(ByteBuffer.wrap(plaintext).getLong());
            // Duration.between can't overflow for two instants, where issuedAt.plus(maxAge) can for a huge max-age.
            if (maxAge != null && Duration.between(issuedAt, clock.instant()).compareTo(maxAge) > 0) {
                throw reject("expired");
            }
            return new String(plaintext, ISSUED_AT_LENGTH, plaintext.length - ISSUED_AT_LENGTH,
                    StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Decrypts the ciphertext and checks the tag, returning {@code null} when the tag does not match. */
    private static byte @Nullable [] decrypt(Cipher cipher, byte[] token) throws GeneralSecurityException {
        try {
            return cipher.doFinal(token, HEADER_LENGTH, token.length - HEADER_LENGTH);
        } catch (BadPaddingException | IllegalBlockSizeException e) {
            // AEADBadTagException is a BadPaddingException: the token, its scope or its key does not match.
            return null;
        }
    }

    /** The additional authenticated data: what the token is bound to besides its own bytes. */
    private static byte[] aad(byte[] id, CursorScope scope) {
        ByteArrayOutputStream aad = new ByteArrayOutputStream(128);
        aad.write(VERSION);
        aad.writeBytes(id);
        aad.writeBytes(CONTEXT);
        writeLengthPrefixed(aad, scope.operation());
        writeLengthPrefixed(aad, scope.normalizedSort());
        aad.writeBytes(scope.inputDigest().getBytes(StandardCharsets.UTF_8));
        return aad.toByteArray();
    }

    private static void writeLengthPrefixed(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }

    private static InvalidCursorException reject(String reason) {
        LOG.debug("Rejected a paging cursor: {}", reason);
        return new InvalidCursorException(reason);
    }
}
