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
package io.americanexpress.synapse.core.internal.context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdPolicyTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdPolicy.class);

    private Level previousLevel;

    @BeforeEach
    void debugLevel() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void restoreLevel() {
        logger.setLevel(previousLevel);
    }

    @Test
    void acceptsValidId() {
        assertThat(new CorrelationIdPolicy(128).acceptOrGenerate("abc-123_x.y:z")).isEqualTo("abc-123_x.y:z");
    }

    @Test
    void acceptsAnIdOfExactlyMaxLength() {
        String id = "x".repeat(128);
        assertThat(new CorrelationIdPolicy(128).acceptOrGenerate(id)).isEqualTo(id);
    }

    @Test
    void generatesWhenAbsent() {
        CorrelationIdPolicy policy = new CorrelationIdPolicy(128);
        assertThat(UUID.fromString(policy.acceptOrGenerate(null))).isNotNull();
        assertThat(UUID.fromString(policy.acceptOrGenerate(""))).isNotNull();
        assertThat(policy.acceptOrGenerate(null)).isNotEqualTo(policy.acceptOrGenerate(null));
    }

    @Test
    void replacesInvalidIds() {
        CorrelationIdPolicy policy = new CorrelationIdPolicy(128);
        for (String invalid : new String[]{"a b", "a\nb", "x".repeat(129), "é"}) {
            String result = policy.acceptOrGenerate(invalid);
            assertThat(result).isNotEqualTo(invalid);
            assertThat(UUID.fromString(result)).isNotNull();
        }
    }

    @Test
    void logsReplacedIdSanitizedAndTruncatedAtMostOncePerMinute(CapturedOutput output) {
        MutableClock clock = new MutableClock();
        CorrelationIdPolicy policy = new CorrelationIdPolicy(128, clock);

        policy.acceptOrGenerate("a\nb");
        assertThat(output.getOut()).contains("WARN").contains("'a_b'");
        assertThat(countOf(output.getOut(), "WARN")).isEqualTo(1);

        clock.advance(Duration.ofSeconds(59));
        policy.acceptOrGenerate("c d");
        assertThat(countOf(output.getOut(), "WARN")).isEqualTo(1);
        assertThat(output.getOut()).contains("DEBUG").contains("'c_d'");

        clock.advance(Duration.ofSeconds(1));
        policy.acceptOrGenerate("e" + "x".repeat(499));
        assertThat(countOf(output.getOut(), "WARN")).isEqualTo(2);
        assertThat(output.getOut()).contains("'e" + "x".repeat(127) + "'").doesNotContain("x".repeat(128));
    }

    @Test
    void doesNotLogAcceptedOrAbsentIds(CapturedOutput output) {
        CorrelationIdPolicy policy = new CorrelationIdPolicy(128);
        policy.acceptOrGenerate("fine");
        policy.acceptOrGenerate(null);
        policy.acceptOrGenerate("");
        assertThat(output.getOut()).doesNotContain("WARN").doesNotContain("DEBUG");
    }

    private static int countOf(String text, String token) {
        return text.split(token, -1).length - 1;
    }

    /** A clock that only moves when told to. */
    static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
