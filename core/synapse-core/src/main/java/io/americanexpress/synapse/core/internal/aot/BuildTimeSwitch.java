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
package io.americanexpress.synapse.core.internal.aot;

import org.jspecify.annotations.Nullable;

/**
 * A property whose value is fixed when an application is built ahead of time, because it decides which beans exist.
 * Every switch is a bean; {@link BuildTimeSwitchCheck} asks them all in AOT mode and reports every difference at once.
 */
public interface BuildTimeSwitch {

    /**
     * Compares the property's value now with the one the build used.
     *
     * @return the mismatch, or {@code null} when the value now agrees with the build or the check doesn't apply
     */
    @Nullable
    Mismatch mismatch();

    /**
     * A property whose value now differs from the build.
     *
     * @param property the property's key
     * @param now the value now
     * @param build the value the build used
     */
    record Mismatch(String property, String now, String build) {
    }
}
