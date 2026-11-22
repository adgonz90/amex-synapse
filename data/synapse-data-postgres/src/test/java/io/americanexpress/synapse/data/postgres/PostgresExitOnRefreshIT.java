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
package io.americanexpress.synapse.data.postgres;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the fixture service in its own JVM with {@code -Dspring.context.exit=onRefresh}, which halts the JVM with
 * exit code 0 once the context has refreshed (Spring's {@code DefaultLifecycleProcessor}), before any store starts.
 * Both stores point at a closed port: every bean is created, the EntityManagerFactory and the JDBC dialect included,
 * without connecting to PostgreSQL. Acceptance 17, for the PostgreSQL vendor. No Docker.
 */
class PostgresExitOnRefreshIT {

    private static final long TIMEOUT_SECONDS = 60;

    @Test
    void startsWithNoDatabaseReachable(@TempDir Path directory) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        // The class path can be longer than a Windows command line allows, so it goes in an argument file.
        Path arguments = directory.resolve("java.args");
        String classPath = System.getProperty("java.class.path").replace(File.separatorChar, '/');
        Files.writeString(arguments, "-cp \"" + classPath + "\"\n", StandardCharsets.UTF_8);
        Path output = directory.resolve("output.log");

        Process process = new ProcessBuilder(List
                .of(java, "@" + arguments, "-Dspring.context.exit=onRefresh", PostgresTestApplication.class.getName(),
                        "--spring.profiles.active=test",
                        "--synapse.data.stores.books.url=jdbc:postgresql://127.0.0.1:1/books",
                        "--synapse.data.stores.books.username=app", "--synapse.data.stores.books.password=",
                        "--synapse.data.stores.legacy.url=jdbc:postgresql://127.0.0.1:1/legacy",
                        "--synapse.data.stores.legacy.username=app", "--synapse.data.stores.legacy.password="))
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        boolean exited = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly().waitFor();
        }
        String log = Files.readString(output, StandardCharsets.UTF_8);

        assertThat(exited).as("exited within %d s; output:%n%s", TIMEOUT_SECONDS, log).isTrue();
        assertThat(process.exitValue()).as("exit code; output:%n%s", log).isZero();
        assertThat(log)
                .contains("The following 1 profile is active: \"test\"")
                .doesNotContain("APPLICATION FAILED TO START", "Connection refused", "books - Start", "legacy - Start",
                        "Exception");
    }
}
