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
package example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The test JVM's arguments: what the parent's argLine and the project put together. */
class ArgLineIT {

    private final List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();

    @Test
    void getsTheMockitoAgentAndTurnsSharingOff() {
        assertTrue(arguments.stream().anyMatch(argument -> argument.matches("-javaagent:.*mockito-core-.*[.]jar")),
                arguments::toString);
        assertTrue(arguments.contains("-Xshare:off"), arguments::toString);
    }

    @Test
    void getsTheJacocoAgent() {
        assertTrue(arguments.stream().anyMatch(argument -> argument.matches("-javaagent:.*org[.]jacoco[.]agent-.*-runtime[.]jar=.*")),
                arguments::toString);
    }
}
