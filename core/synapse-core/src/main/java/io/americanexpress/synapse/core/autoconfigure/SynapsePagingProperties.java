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
package io.americanexpress.synapse.core.autoconfigure;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Paging settings applied by every API plugin.
 *
 * @param defaultSize Page size used when the caller does not ask for one. Must be at least 1 and at most max-size.
 * @param maxSize Largest page size a caller may ask for.
 * @param maxOffset Largest offset a caller may request; a larger one is rejected before the operation runs. Must not
 *     be negative.
 * @param cursor Settings of the opaque cursors the API plugins issue.
 */
@ConfigurationProperties("synapse.paging")
public record SynapsePagingProperties(@DefaultValue("20") int defaultSize, @DefaultValue("100") int maxSize,
        @DefaultValue("1000") long maxOffset, @DefaultValue Cursor cursor) {

    /**
     * Validates the properties.
     *
     * @throws IllegalArgumentException unless {@code 1 <= defaultSize <= maxSize}, or when the offset limit is
     *     negative
     */
    public SynapsePagingProperties {
        if (defaultSize < 1 || defaultSize > maxSize) {
            throw new IllegalArgumentException("synapse.paging.default-size (" + defaultSize
                    + ") must be at least 1 and at most synapse.paging.max-size (" + maxSize + ")");
        }
        if (maxOffset < 0) {
            throw new IllegalArgumentException("synapse.paging.max-offset must not be negative but was " + maxOffset);
        }
    }

    /**
     * Cursor settings.
     *
     * @param key Base64 key that encrypts cursors; whitespace around it is ignored. When unset or blank, a key is
     *     generated at startup.
     * @param previousKeys Earlier keys, used only to read cursors issued before a key rotation. Blank entries are
     *     ignored.
     * @param maxAge How long a cursor stays valid. When unset, cursors do not expire. Must be positive when set.
     */
    public record Cursor(@Nullable String key, List<String> previousKeys, @Nullable Duration maxAge) {

        /**
         * Normalizes and validates the properties.
         *
         * @throws IllegalArgumentException when the maximum age is not positive
         */
        public Cursor {
            previousKeys = previousKeys == null ? List.of() : List.copyOf(previousKeys);
            if (maxAge != null && (maxAge.isZero() || maxAge.isNegative())) {
                throw new IllegalArgumentException(
                        "synapse.paging.cursor.max-age must be positive but was " + maxAge);
            }
        }

        /**
         * Describes the settings without key material.
         *
         * @return whether the key is set (a blank key is not), how many non-blank previous keys there are, and the
         *     maximum age
         */
        @Override
        public String toString() {
            long previous = previousKeys.stream().filter(previousKey -> !previousKey.isBlank()).count();
            return "Cursor[key=" + (key == null || key.isBlank() ? "unset" : "set") + ", previousKeys=" + previous
                    + ", maxAge=" + maxAge + "]";
        }
    }
}
