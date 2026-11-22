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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitch.Mismatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildTimeSwitchCheckTest {

    private static final String ACTION = "Set each to the build's value for this run. To change one, set it in the"
            + " build environment's file (application-<build env>.yaml) and in each deployed environment's file,"
            + " then build again.";

    private static BuildTimeSwitchCheck check(boolean aotMode, BuildTimeSwitch... switches) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int i = 0; i < switches.length; i++) {
            beans.registerSingleton("switch" + i, switches[i]);
        }
        return new BuildTimeSwitchCheck(beans.getBeanProvider(BuildTimeSwitch.class), () -> aotMode);
    }

    private static BuildTimeSwitch differing(String property, String now, String build) {
        return () -> new Mismatch(property, now, build);
    }

    private static BuildTimeSwitch agreeing() {
        return () -> null;
    }

    @Test
    void mismatchFailsInAotMode() {
        assertThatThrownBy(() -> check(true, differing("springdoc.api-docs.enabled", "true", "false"))
                .afterSingletonsInstantiated())
                .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                    assertThat(failure.description())
                            .isEqualTo("These properties are fixed at build time in a native image or AOT run, and"
                                    + " differ from the build: springdoc.api-docs.enabled is true but the build used"
                                    + " false.");
                    assertThat(failure.action()).isEqualTo(ACTION);
                });
    }

    @Test
    void everyMismatchIsInOneFailure() {
        // Registered out of order, and an agreeing switch between them.
        assertThatThrownBy(() -> check(true, differing("spring.grpc.server.reflection.enabled", "false", "true"),
                agreeing(), differing("springdoc.api-docs.enabled", "true", "false"))
                .afterSingletonsInstantiated())
                .isInstanceOfSatisfying(SynapseConfigurationException.class,
                        failure -> assertThat(failure.description())
                                .isEqualTo("These properties are fixed at build time in a native image or AOT run,"
                                        + " and differ from the build: spring.grpc.server.reflection.enabled is false"
                                        + " but the build used true; springdoc.api-docs.enabled is true but the build"
                                        + " used false."));
        // Sorted by property, whatever the registration order.
        assertThatThrownBy(() -> check(true, differing("springdoc.api-docs.enabled", "true", "false"),
                differing("spring.grpc.server.reflection.enabled", "true", "false")).afterSingletonsInstantiated())
                .hasMessageContaining("differ from the build: spring.grpc.server.reflection.enabled is true"
                        + " but the build used false; springdoc.api-docs.enabled");
    }

    @Test
    void agreementPassesInAotMode() {
        assertThatCode(() -> check(true).afterSingletonsInstantiated()).doesNotThrowAnyException();
        assertThatCode(() -> check(true, agreeing(), agreeing()).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    @Test
    void jvmModeNeverChecks() {
        BuildTimeSwitch neverAsked = () -> {
            throw new AssertionError("a switch was asked outside AOT mode");
        };
        assertThatCode(() -> check(false, differing("springdoc.api-docs.enabled", "true", "false"), neverAsked)
                .afterSingletonsInstantiated()).doesNotThrowAnyException();
    }
}
