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

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BakedProfilesTest {

    @Test
    void theProfilesOfTheGeneratedPostProcessorFail() {
        SpringApplication application = new SpringApplication(BakedProfilesTest.class);
        application.setMainApplicationClass(BakedApplication.class);
        assertThatThrownBy(() -> BakedProfiles.reject(application))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo("The AOT-generated code activates the profiles build, kafka at every run "
                                    + "(io.americanexpress.synapse.core.internal.environment."
                                    + "BakedApplication__EnvironmentPostProcessor): they were active during AOT "
                                    + "processing, so they are active in every environment the application runs "
                                    + "in.");
                    assertThat(ex.action())
                            .isEqualTo("Process the application with no active profile and the build environment "
                                    + "as the default profile (--spring.profiles.active= "
                                    + "--spring.profiles.default=<build environment>), as synapse-parent's "
                                    + "process-aot execution does, and build again.");
                });
    }

    /** Boot generates the post-processor only when profiles were active during AOT processing. */
    @Test
    void withoutAGeneratedPostProcessorNothingIsBaked() {
        SpringApplication application = new SpringApplication(BakedProfilesTest.class);
        application.setMainApplicationClass(BakedProfilesTest.class);
        assertThatCode(() -> BakedProfiles.reject(application)).doesNotThrowAnyException();
    }

    @Test
    void withoutAMainClassNothingIsChecked() {
        SpringApplication application = new SpringApplication(BakedProfilesTest.class);
        application.setMainApplicationClass(null);
        assertThatCode(() -> BakedProfiles.reject(application)).doesNotThrowAnyException();
    }
}
