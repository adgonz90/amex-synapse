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
package io.americanexpress.synapse.core.conditional;

import java.util.Objects;

/**
 * The version a write is conditional on, as a client sent it: one version ({@link Version}), or any current version
 * ({@link AnyVersion}).
 *
 * <p>
 * Its nullability in a model record is the operation's contract, the same over every protocol:
 * <ul>
 * <li>{@code @Nullable VersionCondition<V>}: the condition is optional, and {@code null} means the request has none (an
 * unconditional write);</li>
 * <li>{@code VersionCondition<V>}, not {@code @Nullable}: the condition is required. The operation never sees a request
 * without one: the pipeline answers a missing one
 * {@link io.americanexpress.synapse.core.error.SynapseErrors#PRECONDITION_REQUIRED} before the operation runs, for
 * every caller (an API plugin answers it first, while binding the request); in a nested operation call it is the
 * service's bug, so the caller gets {@code INTERNAL}. {@code AnyVersion} counts as a
 * condition.</li>
 * </ul>
 * In REST, it binds from {@code If-Match} or {@code If-None-Match}: {@code "5"} is {@code Version(5)}, {@code *} is
 * {@code AnyVersion}, and an absent header is {@code null} for an optional condition, or 428 for a required one.
 *
 * <p>
 * The cases are exhaustive, so an operation reads it with a {@code switch}: an optional condition has a
 * {@code null} case, and a required one doesn't need it.
 *
 * <pre>{@code
 * // record UpdateBookRequest(UUID id, String title, @Nullable VersionCondition<Long> version)
 * Long expected = switch (update.version()) {
 *     case null -> null; // no condition: an unconditional update
 *     case AnyVersion<Long> any -> null;
 *     case Version<Long>(Long version) -> version;
 * };
 *
 * // record DeleteBookRequest(UUID id, VersionCondition<Long> version)
 * long toDelete = switch (delete.version()) {
 *     case AnyVersion<Long> any -> current.version();
 *     case Version<Long>(Long version) -> version;
 * };
 * }</pre>
 *
 * @param <V> the version type, such as {@code Long}
 */
public sealed interface VersionCondition<V> permits VersionCondition.AnyVersion, VersionCondition.Version {

    /**
     * Returns the condition that any current version satisfies ({@code *}).
     *
     * @param <V> the version type
     * @return the one shared {@link AnyVersion} instance
     */
    @SuppressWarnings("unchecked")
    static <V> VersionCondition<V> any() {
        return (VersionCondition<V>) AnyVersion.INSTANCE;
    }

    /**
     * Returns the condition that only {@code version} satisfies.
     *
     * @param version the version
     * @param <V> the version type
     * @return the condition
     * @throws NullPointerException when {@code version} is {@code null}
     */
    static <V> VersionCondition<V> of(V version) {
        return new Version<>(version);
    }

    /**
     * Tells whether the current version satisfies this condition.
     *
     * @param current the resource's current version
     * @return {@code true} for {@link AnyVersion}; for {@link Version}, whether its value equals {@code current}
     */
    boolean matches(V current);

    /**
     * Any current version ({@code *}). {@link VersionCondition#any()} returns one shared instance; every instance is
     * equal to it.
     *
     * @param <V> the version type
     */
    record AnyVersion<V>() implements VersionCondition<V> {

        private static final AnyVersion<?> INSTANCE = new AnyVersion<>();

        @Override
        public boolean matches(V current) {
            return true;
        }

        /**
         * Returns the wire form, {@code *}.
         *
         * @return {@code *}
         */
        @Override
        public String toString() {
            return "*";
        }
    }

    /**
     * One version.
     *
     * @param value the version; never {@code null}
     * @param <V> the version type
     */
    record Version<V>(V value) implements VersionCondition<V> {

        /**
         * Creates the condition.
         *
         * @throws NullPointerException when {@code value} is {@code null}
         */
        public Version {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean matches(V current) {
            return value.equals(current);
        }

        /**
         * Returns the wire form, the value in double quotes, such as {@code "5"}.
         *
         * @return the quoted value
         */
        @Override
        public String toString() {
            return "\"" + value + "\"";
        }
    }
}
