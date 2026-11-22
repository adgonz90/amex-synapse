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
package io.americanexpress.synapse.core.internal.environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.scanner.ScannerException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ConfigFileFailuresTest {

    /** Line 2 holds a credential, and the parser fails on that line, at the second colon (column 17). */
    private static final String MALFORMED = "user: app\npassword: s3cr3t: unquoted\n";

    @Test
    void aParseErrorKeepsOnlyItsTypeAndOneBasedPosition() {
        Throwable parseError = parse(MALFORMED);
        // The parser's own message quotes the line, credential included, and prints the position 1-based.
        assertThat(parseError).isInstanceOf(MarkedYAMLException.class).hasMessageContaining("s3cr3t");
        assertThat(parseError.getMessage()).contains("line 2, column 17");

        RuntimeException cause = ConfigFileFailures.sanitized(parseError);

        assertThat(cause)
                .hasMessage(parseError.getClass().getSimpleName() + " at line 2, column 17")
                .hasNoCause()
                .hasNoSuppressedExceptions();
        assertThat(cause.toString()).doesNotContain("s3cr3t").doesNotContain("password").doesNotContain("^");
        assertThat(cause.getStackTrace()).isEqualTo(parseError.getStackTrace());
    }

    @Test
    void aParseErrorInsideAWrapperGivesItsPositionAndTheWrappersType() {
        Throwable parseError = parse(MALFORMED);
        IllegalStateException wrapper = new IllegalStateException("Failed to load s3cr3t", parseError);

        RuntimeException cause = ConfigFileFailures.sanitized(wrapper);

        assertThat(cause).hasMessage("IllegalStateException at line 2, column 17").hasNoCause();
        assertThat(cause.getStackTrace()).isEqualTo(wrapper.getStackTrace());
    }

    @Test
    void aParseErrorWithoutAPositionGivesOnlyItsType() {
        ScannerException parseError = new ScannerException("while scanning s3cr3t", null, "found s3cr3t", null);

        assertThat(ConfigFileFailures.sanitized(parseError)).hasMessage("ScannerException").hasNoCause();
    }

    @Test
    void anyOtherFailureGivesOnlyItsType() {
        IOException readError = new IOException("/srv/s3cr3t/application.yaml", new IOException("s3cr3t"));

        RuntimeException cause = ConfigFileFailures.sanitized(readError);

        assertThat(cause).hasMessage("IOException").hasNoCause();
        assertThat(cause.getStackTrace()).isEqualTo(readError.getStackTrace());
    }

    @Test
    void aCauseChainWithACycleEnds() {
        IOException first = new IOException("first");
        IOException second = new IOException("second", first);
        first.initCause(second);

        assertThat(ConfigFileFailures.sanitized(first)).hasMessage("IOException").hasNoCause();
    }

    private static Throwable parse(String yaml) {
        Throwable failure = catchThrowable(() -> new YamlPropertySourceLoader()
                .load("application.yaml", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8))));
        assertThat(failure).as("the parse failure").isNotNull();
        return failure;
    }
}
