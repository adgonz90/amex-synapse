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
package io.americanexpress.synapse.samples.bookstore.scenario;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AppProcessTest {

    /**
     * The process keeps what the operating system and the JVM need, the locale included: a Unix runner may set only
     * {@code LC_ALL} or {@code LC_CTYPE}, and without them the child falls back to the POSIX locale. Anything else
     * could configure the bookstore (relaxed binding, {@code JAVA_TOOL_OPTIONS}), so it is dropped.
     */
    @ParameterizedTest
    @CsvSource({"PATH, true", "Path, true", "LANG, true", "LC_ALL, true", "LC_CTYPE, true", "lc_messages, true",
            "TZ, true", "JAVA_TOOL_OPTIONS, false", "SERVER_PORT, false", "SYNAPSE_PAGING_CURSOR_KEY, false",
            "LCX, false"})
    void keepsOnlyTheVariablesTheProcessNeeds(String name, boolean kept) {
        assertThat(AppProcess.Builder.kept(name)).as(name).isEqualTo(kept);
    }
}
