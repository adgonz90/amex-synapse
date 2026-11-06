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

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.support.SpringFactoriesLoader;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseConfigurationFailureAnalyzerTest {

    private final SynapseConfigurationFailureAnalyzer analyzer = new SynapseConfigurationFailureAnalyzer();

    @Test
    void analyzesSynapseConfigurationException() {
        SynapseConfigurationException failure = new SynapseConfigurationException("what is wrong", "what to do");
        assertThat(analyzer.analyze(failure))
                .isNotNull()
                .extracting(FailureAnalysis::getDescription, FailureAnalysis::getAction, FailureAnalysis::getCause)
                .containsExactly("what is wrong", "what to do", failure);
    }

    @Test
    void passesTheExceptionWithItsCauseToTheReporter() {
        // As Boot's own analyzers do, the analysis carries the exception it matched; Boot's reporter logs it, with its
        // cause chain, only at DEBUG, and prints only the description and action at ERROR.
        IOException parserFailure = new IOException("parser text");
        SynapseConfigurationException failure = new SynapseConfigurationException("what is wrong", "what to do",
                parserFailure);
        assertThat(analyzer.analyze(new BeanCreationException("bean", failure)))
                .isNotNull()
                .satisfies(analysis -> {
                    assertThat(analysis.getDescription()).isEqualTo("what is wrong");
                    assertThat(analysis.getAction()).isEqualTo("what to do");
                    assertThat(analysis.getCause()).isSameAs(failure).cause().isSameAs(parserFailure);
                });
    }

    @Test
    void findsTheExceptionInsideOtherFailures() {
        SynapseConfigurationException failure = new SynapseConfigurationException("nested", "fix it");
        assertThat(analyzer.analyze(new BeanCreationException("bean", failure)))
                .isNotNull()
                .extracting(FailureAnalysis::getDescription, FailureAnalysis::getAction, FailureAnalysis::getCause)
                .containsExactly("nested", "fix it", failure);
    }

    @Test
    void ignoresOtherFailures() {
        assertThat(analyzer.analyze(new IllegalStateException("other"))).isNull();
    }

    @Test
    void isRegisteredInSpringFactories() {
        assertThat(SpringFactoriesLoader
                .forDefaultResourceLocation()
                .load(FailureAnalyzer.class, null, (type, name, failure) -> {
                }))
                .anyMatch(SynapseConfigurationFailureAnalyzer.class::isInstance);
    }
}
