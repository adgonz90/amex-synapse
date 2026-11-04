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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestProperties;
import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * The AES-256 keys of the default cursor codec, read from {@code synapse.api.rest.paging.cursor}. Each key has an id:
 * the first 4 bytes of SHA-256 over {@code "synapse-cursor-key-id:"} and the key (a hash, not a MAC), which a token
 * carries so the codec can pick the key that decrypts it.
 *
 * @param current the key that encrypts new tokens; it also decrypts
 * @param previous earlier keys, which only decrypt tokens issued before a key rotation
 * @param generated whether the current key was generated at startup because {@code cursor.key} is not set
 */
public record CursorKeys(Key current, List<Key> previous, boolean generated) {

    /** The length of an AES-256 key in bytes. */
    static final int KEY_LENGTH = 32;

    /** The length of a key id in bytes. */
    static final int ID_LENGTH = 4;

    private static final String KEY_PROPERTY = "synapse.api.rest.paging.cursor.key";

    private static final String PREVIOUS_KEYS_PROPERTY = "synapse.api.rest.paging.cursor.previous-keys";

    private static final byte[] ID_LABEL = "synapse-cursor-key-id:".getBytes(StandardCharsets.US_ASCII);

    /**
     * Copies the previous keys.
     */
    public CursorKeys {
        previous = List.copyOf(previous);
    }

    /**
     * Reads the keys from the cursor properties. Whitespace around a key is ignored. When {@code cursor.key} is not set
     * or is blank, the current key is 32 bytes from the random source. Blank {@code previous-keys} entries are
     * ignored. A key that is not base64 (standard or URL-safe alphabet, padding optional) of exactly 32 bytes, or two
     * keys with the same id, fail with a message that names the property but never its value.
     *
     * @param cursor the {@code synapse.api.rest.paging.cursor} properties
     * @param random the source of a generated key
     * @return the keys
     * @throws SynapseConfigurationException when a key is invalid or two keys have the same id
     */
    public static CursorKeys from(SynapseRestProperties.Paging.Cursor cursor, SecureRandom random) {
        String configured = cursor.key();
        boolean generated = configured == null || configured.isBlank();
        Key current;
        if (configured == null || generated) {
            byte[] bytes = new byte[KEY_LENGTH];
            random.nextBytes(bytes);
            current = key(bytes);
        } else {
            current = key(decode(configured, KEY_PROPERTY));
        }
        List<String> names = new ArrayList<>();
        names.add(KEY_PROPERTY);
        List<Key> all = new ArrayList<>();
        all.add(current);
        List<Key> previous = new ArrayList<>();
        for (int i = 0; i < cursor.previousKeys().size(); i++) {
            String text = cursor.previousKeys().get(i);
            if (text.isBlank()) {
                continue;
            }
            String name = PREVIOUS_KEYS_PROPERTY + "[" + i + "]";
            Key key = key(decode(text, name));
            for (int j = 0; j < all.size(); j++) {
                if (MessageDigest.isEqual(all.get(j).id, key.id)) {
                    throw new SynapseConfigurationException(
                            name + " has the same key id as " + names.get(j),
                            "Remove the duplicate from " + PREVIOUS_KEYS_PROPERTY + ": it lists earlier keys only,"
                                    + " never the current key or one key twice. If the two keys differ, generate a"
                                    + " new key.");
                }
            }
            names.add(name);
            all.add(key);
            previous.add(key);
        }
        return new CursorKeys(current, previous, generated);
    }

    /**
     * Returns the key with the id, current or previous.
     *
     * @param id the key id from a token
     * @return the key, or {@code null} when no key has the id
     */
    public @Nullable Key find(byte[] id) {
        if (MessageDigest.isEqual(current.id, id)) {
            return current;
        }
        for (Key key : previous) {
            if (MessageDigest.isEqual(key.id, id)) {
                return key;
            }
        }
        return null;
    }

    private static byte[] decode(String value, String property) {
        // Base64 never contains whitespace, and a key read from a file often ends with a newline.
        String text = value.strip();
        boolean urlSafe = text.indexOf('-') >= 0 || text.indexOf('_') >= 0;
        byte[] bytes;
        try {
            bytes = (urlSafe ? Base64.getUrlDecoder() : Base64.getDecoder()).decode(text);
        } catch (IllegalArgumentException e) {
            // The decoder's message quotes the offending character, so neither it nor the cause is kept.
            bytes = new byte[0];
        }
        if (bytes.length != KEY_LENGTH) {
            Arrays.fill(bytes, (byte) 0);
            throw new SynapseConfigurationException(
                    property + " must be base64 (standard or URL-safe alphabet, padding optional) of exactly "
                            + KEY_LENGTH + " bytes, a 256-bit AES key",
                    "Set " + property + " to a base64 256-bit key, the same on every instance, for example the output"
                            + " of: openssl rand -base64 32");
        }
        return bytes;
    }

    private static Key key(byte[] bytes) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(ID_LABEL);
            byte[] id = Arrays.copyOf(sha256.digest(bytes), ID_LENGTH);
            return new Key(id, new SecretKeySpec(bytes, "AES"));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * One AES-256 key and its id.
     *
     * @param id the key id, 4 bytes; copied in and out
     * @param secret the AES key
     */
    // The id is a 4-byte array to match the token bytes; it is copied in and out, and equality is never used.
    @SuppressWarnings("ArrayRecordComponent")
    public record Key(byte[] id, SecretKey secret) {

        /**
         * Copies the id.
         */
        public Key {
            id = id.clone();
        }

        /**
         * Returns a copy of the id.
         *
         * @return the key id
         */
        @Override
        public byte[] id() {
            return id.clone();
        }

        /**
         * Describes the key by its id only. {@code SecretKeySpec}'s hash code is computed from the key bytes, so the
         * secret is left out entirely.
         *
         * @return the key id in hex
         */
        @Override
        public String toString() {
            return "Key[id=" + HexFormat.of().formatHex(id) + "]";
        }
    }
}
