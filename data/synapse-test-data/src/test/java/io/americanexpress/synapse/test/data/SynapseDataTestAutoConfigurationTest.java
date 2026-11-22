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
package io.americanexpress.synapse.test.data;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataStoreChecksAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseDataTestAutoConfigurationTest {

    private static final String SLICE = "synapseDataTestSlice";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SynapseDataTestAutoConfiguration.class));

    /** Minor 10: like every Synapse auto-configuration, it is final and can't be constructed outside its package. */
    @Test
    void isFinalWithAPackagePrivateConstructor() {
        assertThat(Modifier.isFinal(SynapseDataTestAutoConfiguration.class.getModifiers())).isTrue();
        assertThat(SynapseDataTestAutoConfiguration.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getModifiers()
                        & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)).isZero());
    }

    @Test
    void registersTheSliceMarker() {
        runner.run(context -> assertThat(context).hasNotFailed().hasBean(SLICE));
    }

    @Test
    void backsOffToABeanWithTheMarkersName() {
        runner
                .withUserConfiguration(UserMarkerConfiguration.class)
                .run(context -> assertThat(context.getBean(SLICE)).isEqualTo("the user's marker"));
    }

    /** The marker is never injected by type: it is not a default candidate. */
    @Test
    void theMarkerIsNotADefaultCandidate() {
        runner
                .run(context -> assertThat(
                        ((AbstractBeanDefinition) context.getBeanFactory().getBeanDefinition(SLICE))
                                .isDefaultCandidate())
                        .isFalse());
    }

    /** With the marker, the settings of a store the slice leaves out are expected (Defaults #18). */
    @Test
    void theSliceAllowsSettingsOfStoresItLeavesOut() {
        ApplicationContextRunner checks = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SynapseDataStoreChecksAutoConfiguration.class))
                .withPropertyValues("synapse.data.stores.legacy.url=jdbc:h2:mem:legacy");

        checks
                .run(context -> assertThat(context)
                        .getFailure()
                        .isInstanceOf(SynapseConfigurationException.class)
                        .hasMessage("synapse.data.stores.legacy is configured, but no @SynapseDataStore(\"legacy\") "
                                + "declares the store."));
        checks
                .withConfiguration(AutoConfigurations.of(SynapseDataTestAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class UserMarkerConfiguration {

        @Bean(SLICE)
        String synapseDataTestSlice() {
            return "the user's marker";
        }
    }
}
