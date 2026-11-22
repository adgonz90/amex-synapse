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

/**
 * How a {@link Masked} value is hidden. Masking counts Unicode code points, so it never splits a surrogate pair.
 */
public enum MaskType {

    /** Replaces every code point with {@code *}. */
    ALL {
        @Override
        public String apply(String value) {
            return "*".repeat(value.codePointCount(0, value.length()));
        }
    },

    /** Replaces the whole value with {@code ***}, hiding its length. */
    FIXED_LENGTH {
        @Override
        public String apply(String value) {
            return "***";
        }
    },

    /** Keeps the last four code points and masks the rest; masks everything when there are four or fewer. */
    LAST_FOUR {
        @Override
        public String apply(String value) {
            int count = value.codePointCount(0, value.length());
            if (count <= 4) {
                return "*".repeat(count);
            }
            int start = value.offsetByCodePoints(0, count - 4);
            return "*".repeat(count - 4) + value.substring(start);
        }
    };

    /**
     * Masks a value.
     *
     * @param value the value to mask
     * @return the masked value
     */
    public abstract String apply(String value);
}
