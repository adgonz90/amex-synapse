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
package io.americanexpress.synapse.api.grpc.autoconfigure;

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

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures;
import io.americanexpress.synapse.core.operation.CommandOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts a gRPC service in its own JVM with {@code -Dspring.context.exit=onRefresh}, which halts the JVM with exit
 * code 0 once the context has refreshed (Spring's {@code DefaultLifecycleProcessor}): every bean is created, the
 * bindings are compiled, nothing remote is reachable, and the gRPC server is never started. Acceptance 17, for the gRPC
 * plugin.
 */
class GrpcContextExitOnRefreshIT {

    private static final long TIMEOUT_SECONDS = 60;

    @Test
    void startsAndExitsOnRefresh(@TempDir Path directory) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        // The class path can be longer than a Windows command line allows, so it goes in an argument file.
        Path arguments = directory.resolve("java.args");
        String classPath = System.getProperty("java.class.path").replace(File.separatorChar, '/');
        // The child runs on failsafe's class path, which leaves Spring Security out, as a consumer's service has it.
        assertThat(classPath).contains("grpc-netty").doesNotContain("spring-security-");
        Files.writeString(arguments, "-cp \"" + classPath + "\"\n", StandardCharsets.UTF_8);
        Path output = directory.resolve("output.log");

        Process process = new ProcessBuilder(java, "@" + arguments, "-Dspring.context.exit=onRefresh",
                ExitApplication.class.getName(), "--spring.grpc.server.port=0", "--spring.profiles.active=test",
                "--management.logging.export.otlp.enabled=false", "--management.tracing.export.otlp.enabled=false",
                "--management.otlp.metrics.export.enabled=false")
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
                .doesNotContain("APPLICATION FAILED TO START")
                .doesNotContain("gRPC Server started");
    }

    /** The input of {@code CreateItem}: only what the request carries, so it binds by name. */
    record CreateItemInput(BindingFixtures.ItemRecord item) {
    }

    /** Named {@code create-item}. */
    static class CreateItemOperation implements CommandOperation<CreateItemInput, BindingFixtures.ItemRecord> {

        @Override
        public BindingFixtures.ItemRecord execute(CreateItemInput input) {
            return input.item();
        }
    }

    /** The service the forked JVM starts: auto-configuration and every catalog method bound by name. */
    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ExitApplication {

        public static void main(String[] args) {
            SpringApplication.run(ExitApplication.class, args);
        }

        @Bean
        BindingFixtures.GetItemOperation getItemOperation() {
            return new BindingFixtures.GetItemOperation();
        }

        @Bean
        CreateItemOperation createItemOperation() {
            return new CreateItemOperation();
        }

        @Bean
        BindingFixtures.DeleteItemOperation deleteItemOperation() {
            return new BindingFixtures.DeleteItemOperation();
        }

        @Bean
        BindingFixtures.PingOperation pingOperation() {
            return new BindingFixtures.PingOperation();
        }

        @Bean
        BindingFixtures.ListTagsOperation listTagsOperation() {
            return new BindingFixtures.ListTagsOperation();
        }

        @Bean
        BindingFixtures.FailOperation failOperation() {
            return new BindingFixtures.FailOperation();
        }

        @Bean
        GrpcBindings catalogBindings() {
            return GrpcBindings.service(CatalogServiceGrpc.getServiceDescriptor()).bindAllByName().build();
        }
    }
}
