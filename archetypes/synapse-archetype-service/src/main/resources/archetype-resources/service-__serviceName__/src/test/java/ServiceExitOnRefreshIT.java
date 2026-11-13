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

import org.junit.jupiter.api.Test;

import ${package}.scenario.AppProcess;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packaged jar starts in the {@code build} environment with {@code -Dspring.context.exit=onRefresh} and exits with
 * code 0 once the context has refreshed, with no database to reach. That is the training run of an image's AOT cache
 * (the Dockerfile's, and synapse-parent's {@code -Pimage}): every bean is created, the ${serviceName} store's
 * EntityManagerFactory included, and the JVM exits before any lifecycle starts, so the store neither opens its pool nor
 * migrates. data-${serviceName}'s {@code config-build.yaml} points the store at a closed port. No Docker.
 */
class ServiceExitOnRefreshIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** The build environment through {@code ENV}, as {@code application.yaml} maps it. */
    @Test
    void startsWithNoDatabaseReachable() {
        AppProcess.Exit exit = AppProcess
                .jvm(AppProcess.packagedJar())
                .env("ENV", "build")
                .systemProperty("spring.context.exit", "onRefresh")
                .output(AppProcess.logFile("exit-on-refresh"))
                .run(TIMEOUT);

        assertExitedOnRefresh(exit);
    }

    /**
     * The training run as the image build starts it: the environment is the system property that the Dockerfile (or
     * synapse-parent's {@code TRAINING_RUN_JAVA_TOOL_OPTIONS}) passes, and {@code ENV} is not set. The system property
     * outranks {@code application.yaml}'s {@code spring.profiles.active: ${ENV}}.
     */
    @Test
    void startsAsTheImageTrainingRunDoes() {
        AppProcess.Exit exit = AppProcess
                .jvm(AppProcess.packagedJar())
                .systemProperty("spring.profiles.active", "build")
                .systemProperty("spring.context.exit", "onRefresh")
                .output(AppProcess.logFile("training-run"))
                .run(TIMEOUT);

        assertExitedOnRefresh(exit);
    }

    private static void assertExitedOnRefresh(AppProcess.Exit exit) {
        assertThat(exit.code()).as("exit code; output:%n%s", exit.output()).isZero();
        assertThat(exit.output())
                .contains("The following 1 profile is active: \\\"build\\\"")
                .doesNotContain("APPLICATION FAILED TO START", "Connection refused", "${serviceName} - Start",
                        "Migrating", "Tomcat started", "Exception");
    }
}
