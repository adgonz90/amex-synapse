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
package io.americanexpress.synapse.core.autoconfigure;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.springframework.aot.AotDetector;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.internal.environment.AotEnvironmentCheck;
import io.americanexpress.synapse.core.internal.environment.BakedProfiles;
import io.americanexpress.synapse.core.internal.environment.EnvironmentName;
import io.americanexpress.synapse.core.internal.environment.ModuleConfigFiles;
import io.americanexpress.synapse.core.internal.environment.ServiceConfigFiles;

/**
 * Applies Synapse's environment rules right after Boot has loaded the service's config data. In order, it:
 * <ol>
 * <li>in AOT mode, checks that the AOT-generated code activates no profile;</li>
 * <li>resolves the single environment name ({@code spring.profiles.active} plus {@code SpringApplication}
 * additional profiles; during AOT processing, the one {@code spring.profiles.default}, with no profile active);</li>
 * <li>checks that the service has a file for it, and that no config document uses
 * {@code spring.config.activate.on-profile};</li>
 * <li>loads the module files from fixed resource names and inserts them below Boot's config data, after the
 * collision and ownership checks;</li>
 * <li>in AOT mode, checks that environment files add no client or store names.</li>
 * </ol>
 * It runs before logging starts, so it logs nothing; a broken rule fails startup with a
 * {@link SynapseConfigurationException}, which {@link SynapseConfigurationFailureAnalyzer} prints.
 */
final class SynapseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private final BooleanSupplier aotMode;

    /** Creates the post-processor, which detects AOT mode with {@link AotDetector#useGeneratedArtifacts()}. */
    SynapseEnvironmentPostProcessor() {
        this(AotDetector::useGeneratedArtifacts);
    }

    /**
     * Creates the post-processor.
     *
     * @param aotMode whether the application runs with AOT-generated artifacts
     */
    SynapseEnvironmentPostProcessor(BooleanSupplier aotMode) {
        this.aotMode = aotMode;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (aotMode.getAsBoolean()) {
            BakedProfiles.reject(application);
        }
        String name = EnvironmentName.resolve(environment, application.getAdditionalProfiles()).name();
        ClassLoader classLoader = application.getClassLoader();
        ServiceConfigFiles.requireEnvironmentFile(environment, name, classLoader);
        ServiceConfigFiles.rejectOnProfileDocuments(environment, name, classLoader);
        List<String> configNames = ServiceConfigFiles.configNames(environment);
        ModuleConfigFiles
                .addTo(environment.getPropertySources(), ModuleConfigFiles.load(classLoader, name, configNames));
        if (aotMode.getAsBoolean()) {
            AotEnvironmentCheck.verify(environment, name, configNames);
        }
    }

    /**
     * Runs right after Boot's config data loader, so {@code spring.profiles.active} holds the service's value.
     *
     * @return one after {@link ConfigDataEnvironmentPostProcessor#ORDER}
     */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
