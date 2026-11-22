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

import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * The profiles that AOT-generated code activates at every run. Spring Boot's AOT code generation writes a
 * {@code <Main>__EnvironmentPostProcessor} that calls {@code addActiveProfile} for each profile that was active during
 * AOT processing, and only when one was ({@code EnvironmentPostProcessorApplicationListener}'s AOT contribution, Boot
 * 4.1). In AOT mode Boot looks it up by that name and runs it first. Profiles that libraries add at run time, such as
 * spring-cloud-kubernetes' {@code kubernetes} or java-cfenv's {@code cloud}, are not baked and are not checked.
 */
public final class BakedProfiles {

    /** The suffix of the class Boot generates, after the main class's name ({@code AOT_FEATURE_NAME}). */
    private static final String GENERATED_SUFFIX = "__EnvironmentPostProcessor";

    private BakedProfiles() {
    }

    /**
     * Fails when the application's AOT-generated code activates a profile. Call it only when the application runs with
     * AOT-generated artifacts.
     *
     * @param application the application, whose main class names the generated class
     * @throws SynapseConfigurationException when the generated code activates a profile
     */
    public static void reject(SpringApplication application) {
        Class<?> mainApplicationClass = application.getMainApplicationClass();
        if (mainApplicationClass == null) {
            return;
        }
        String generatedName = mainApplicationClass.getName() + GENERATED_SUFFIX;
        ClassLoader classLoader = application.getClassLoader();
        if (!ClassUtils.isPresent(generatedName, classLoader)) {
            return;
        }
        String[] baked = baked(generatedName, classLoader, application);
        if (baked.length == 0) {
            return;
        }
        throw new SynapseConfigurationException("The AOT-generated code activates the profiles "
                + String.join(", ", baked) + " at every run (" + generatedName + "): they were active during AOT "
                + "processing, so they are active in every environment the application runs in.",
                "Process the application with no active profile and the build environment as the default profile "
                        + "(--spring.profiles.active= --spring.profiles.default=<build environment>), as "
                        + "synapse-parent's process-aot execution does, and build again.");
    }

    /** Replays the generated post-processor on an environment without property sources, which only it changes. */
    private static String[] baked(String generatedName, @Nullable ClassLoader classLoader,
            SpringApplication application) {
        EnvironmentPostProcessor generated = BeanUtils
                .instantiateClass(ClassUtils.resolveClassName(generatedName, classLoader),
                        EnvironmentPostProcessor.class);
        ConfigurableEnvironment scratch = new AbstractEnvironment() {
        };
        generated.postProcessEnvironment(scratch, application);
        return scratch.getActiveProfiles();
    }
}
