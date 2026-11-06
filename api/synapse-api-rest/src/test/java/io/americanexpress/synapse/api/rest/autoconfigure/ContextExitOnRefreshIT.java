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
package io.americanexpress.synapse.api.rest.autoconfigure;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts a REST service in its own JVM with {@code -Dspring.context.exit=onRefresh}, which halts the JVM with exit
 * code 0 once the context has refreshed (Spring's {@code DefaultLifecycleProcessor}): every bean is created, nothing
 * remote is reachable, and the web server is never started. Acceptance 17, for the REST plugin.
 */
class ContextExitOnRefreshIT {

    private static final long TIMEOUT_SECONDS = 60;

    @Test
    void startsAndExitsOnRefresh(@TempDir Path directory) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        // The class path can be longer than a Windows command line allows, so it goes in an argument file.
        Path arguments = directory.resolve("java.args");
        String classPath = System.getProperty("java.class.path").replace(File.separatorChar, '/');
        Files.writeString(arguments, "-cp \"" + classPath + "\"\n", StandardCharsets.UTF_8);
        Path output = directory.resolve("output.log");

        Process process = new ProcessBuilder(java, "@" + arguments, "-Dspring.context.exit=onRefresh",
                ExitApplication.class.getName(), "--server.port=0", "--spring.profiles.active=test")
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
        assertThat(log).contains("Tomcat initialized").doesNotContain("APPLICATION FAILED TO START");
    }

    record BookId(String id) {
    }

    record Book(String id, String title) {
    }

    static class GetBookOperation implements QueryOperation<BookId, Book> {

        @Override
        public Book execute(BookId input) {
            return new Book(input.id(), "Dune");
        }
    }

    /** The service the forked JVM starts: auto-configuration and one route. */
    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ExitApplication {

        public static void main(String[] args) {
            SpringApplication.run(ExitApplication.class, args);
        }

        @Bean
        GetBookOperation getBookOperation() {
            return new GetBookOperation();
        }

        @Bean
        RestBindings restBindings(GetBookOperation getBook) {
            return RestBindings.builder().get("/books/{key}", getBook).path("key", BookId::id).build();
        }
    }
}
