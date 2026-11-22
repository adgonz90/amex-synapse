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

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitch.Mismatch;

/**
 * Fails startup in AOT mode when a build-time switch differs from the build. Under AOT the bean definitions are fixed
 * by the build, so an environment file that changes a property that decides which beans exist would otherwise do
 * nothing in a native image or AOT run. Every {@link BuildTimeSwitch} bean is asked, and all the differences are
 * reported in one failure, in property order, so the result doesn't depend on which switch is asked first. Outside AOT
 * mode the properties decide at startup and no switch is asked.
 */
public final class BuildTimeSwitchCheck implements SmartInitializingSingleton {

    private final ObjectProvider<BuildTimeSwitch> switches;

    private final BooleanSupplier aotMode;

    /**
     * Creates the check.
     *
     * @param switches every build-time switch bean
     * @param aotMode whether the application runs with AOT-generated artifacts
     */
    public BuildTimeSwitchCheck(ObjectProvider<BuildTimeSwitch> switches, BooleanSupplier aotMode) {
        this.switches = switches;
        this.aotMode = aotMode;
    }

    /**
     * Asks every switch, in AOT mode.
     *
     * @throws SynapseConfigurationException when any switch differs from the build, naming each of them
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (!aotMode.getAsBoolean()) {
            return;
        }
        List<Mismatch> mismatches = switches
                .stream()
                .map(BuildTimeSwitch::mismatch)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Mismatch::property))
                .toList();
        if (mismatches.isEmpty()) {
            return;
        }
        throw new SynapseConfigurationException(
                "These properties are fixed at build time in a native image or AOT run, and differ from the build: "
                        + mismatches
                                .stream()
                                .map(each -> each.property() + " is " + each.now() + " but the build used "
                                        + each.build())
                                .collect(Collectors.joining("; "))
                        + ".",
                "Set each to the build's value for this run. To change one, set it in the build environment's file"
                        + " (application-<build env>.yaml) and in each deployed environment's file, then build"
                        + " again.");
    }
}
