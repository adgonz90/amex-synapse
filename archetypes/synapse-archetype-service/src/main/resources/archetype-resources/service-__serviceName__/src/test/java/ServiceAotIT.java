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
package ${package};

import java.time.Duration;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.test.data.SynapseContainers;

import ${package}.scenario.AppProcess;
import ${package}.scenario.ItemScenarios;
import ${package}.scenario.Target;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every scenario against the packaged jar in AOT mode ({@code java -Dspring.aot.enabled=true -jar}), as a deployed
 * service runs: the bean definitions are the ones {@code spring-boot:process-aot} generated in the {@code build}
 * environment during {@code package}, and the run is in {@code dev}, a deployed environment that changes no
 * build-time switch. The ${serviceName} store connects to a PostgreSQL container of its own through the environment
 * variables a platform sets ({@code SYNAPSE_DATA_STORES_${serviceName.toUpperCase()}_URL}, {@code _USERNAME} and
 * {@code _PASSWORD}), so the store migrates an empty database in AOT mode too. Spring AOT processing is opt-in, so
 * this class runs only in a build with {@code -Paot} (the {@code aot} profiles of synapse-parent and of this module's
 * pom).
 */
class ServiceAotIT extends ItemScenarios {

    /** A database of its own: {@link ServiceJvmIT}'s is the default container. */
    private static final PostgreSQLContainer STORE = SynapseContainers.postgres("${serviceName}-aot");

    private static @Nullable AppProcess service;

    @BeforeAll
    static void start() {
        service = store(AppProcess.aot(AppProcess.packagedJar()))
                .env("ENV", "dev")
                .output(AppProcess.logFile("aot-dev"))
                .start();
    }

    @AfterAll
    static void stop() {
        if (service != null) {
            service.close();
        }
    }

    @Override
    protected Target target() {
        return running().target();
    }

    /**
     * Boot says so when the generated code runs instead of the configuration classes. The environment is dev, the
     * only active profile: the build environment reached AOT processing as the default profile, so the generated
     * code activates no profile of its own. The build environment's files are not among the configuration sources.
     */
    @Test
    void runsTheAotProcessedApplicationInDev() {
        assertThat(running().output())
                .contains("Starting AOT-processed ServiceApplication")
                // The log is ECS JSON, so Boot's quotes around the profile names are escaped.
                .contains("The following 1 profile is active: \\\"dev\\\"")
                .doesNotContain("\\\"build\\\"")
                .contains("Synapse environment: dev (spring.profiles.active=${ENV}, application.yaml)")
                .containsPattern("config-dev\\.yaml +data-${serviceName}-")
                .doesNotContain("config-build.yaml", "application-build.yaml");
    }

    /**
     * {@code local} turns on the API docs and gRPC reflection, which the build left off: generated code can't add
     * their beans, so startup fails, and one failure names both keys, in property-name order, rather than the first
     * one found. The store gets the container, so the switches are the only thing that differs from a run that starts.
     */
    @Test
    void aotRunInLocalNamesEveryBuildTimeSwitch() {
        AppProcess.Exit exit = store(AppProcess.aot(AppProcess.packagedJar()))
                .env("ENV", "local")
                .output(AppProcess.logFile("aot-local"))
                .run(Duration.ofSeconds(60));

        assertThat(exit.code()).as(exit.output()).isNotZero();
        assertThat(exit.output())
                .contains("These properties are fixed at build time in a native image or AOT run, and differ from "
                        + "the build: spring.grpc.server.reflection.enabled is true but the build used false; "
                        + "springdoc.api-docs.enabled is true but the build used false.")
                .contains("Set each to the build's value for this run.");
        assertThat(occurrences(exit.output(), "APPLICATION FAILED TO START")).as(exit.output()).isOne();
    }

    /**
     * The AOT-processed jar under the OpenTelemetry Java agent. AOT processing ran without the agent, so it fixed
     * {@code OpenTelemetryAgentConfiguration}'s {@code @ConditionalOnClass} as false, and the generated code has no
     * {@code openTelemetryAgent} bean: Boot would start a second SDK next to the agent's. Startup fails instead, with
     * {@code OpenTelemetryAgentAotConfiguration}'s description and action. On the JVM the same jar runs under the agent
     * ({@link ServiceAgentIT}).
     */
    @Test
    void aotRunUnderTheAgentFailsFast() {
        AppProcess.Exit exit = store(AppProcess.aot(AppProcess.packagedJar()))
                .jvmOption("-javaagent:" + AppProcess.agentJar())
                .systemProperty("otel.traces.exporter", "none")
                .systemProperty("otel.metrics.exporter", "none")
                .systemProperty("otel.logs.exporter", "none")
                .env("ENV", "dev")
                .output(AppProcess.logFile("aot-agent"))
                .run(Duration.ofSeconds(60));

        assertThat(exit.code()).as(exit.output()).isNotZero();
        assertThat(exit.output())
                .contains("opentelemetry-javaagent - version: ")
                .contains("The OpenTelemetry Java agent is running, but this AOT-processed application was built "
                        + "without it")
                .contains("or don't build with -Paot");
        assertThat(occurrences(exit.output(), "APPLICATION FAILED TO START")).as(exit.output()).isOne();
    }

    /** The store's connection, as a platform passes it: environment variables, which win over config-dev.yaml. */
    private static AppProcess.Builder store(AppProcess.Builder builder) {
        return builder
                .env("SYNAPSE_DATA_STORES_${serviceName.toUpperCase()}_URL", STORE.getJdbcUrl())
                .env("SYNAPSE_DATA_STORES_${serviceName.toUpperCase()}_USERNAME", STORE.getUsername())
                .env("SYNAPSE_DATA_STORES_${serviceName.toUpperCase()}_PASSWORD", STORE.getPassword());
    }

    private static AppProcess running() {
        return Objects.requireNonNull(service, "the service didn't start");
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
