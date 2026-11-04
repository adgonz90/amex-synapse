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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaggageFieldsEnvironmentPostProcessorTest {

    private static final String REMOTE_FIELDS = "management.tracing.baggage.remote-fields";

    private static final String CORRELATION_FIELDS = "management.tracing.baggage.correlation.fields";

    private final BaggageFieldsEnvironmentPostProcessor postProcessor = new BaggageFieldsEnvironmentPostProcessor();

    private final StandardEnvironment environment = new StandardEnvironment();

    @Test
    void addsCorrelationFieldWhenUserSetsNothing() {
        process();
        assertThat(list(REMOTE_FIELDS)).containsExactly("correlationId");
        assertThat(list(CORRELATION_FIELDS)).containsExactly("correlationId");
        assertThat(environment.getPropertySources().iterator().next().getName()).isEqualTo("synapseBaggageFields");
    }

    @Test
    void mergesWithUserFields() {
        userProperties(Map.of(REMOTE_FIELDS, "a,b", CORRELATION_FIELDS, "b,correlationId,c"));
        process();
        assertThat(list(REMOTE_FIELDS)).containsExactly("a", "b", "correlationId");
        assertThat(list(CORRELATION_FIELDS)).containsExactly("b", "correlationId", "c");
    }

    @Test
    void mergesWithUserFieldsWrittenAsIndexedValues() {
        userProperties(Map.of(REMOTE_FIELDS + "[0]", "a", REMOTE_FIELDS + "[1]", "b"));
        process();
        assertThat(list(REMOTE_FIELDS)).containsExactly("a", "b", "correlationId");
    }

    @Test
    void addsBoundAttributeNames() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("synapse.api.rest.context.headers.tenant", "X-Tenant");
        user.put("synapse.api.grpc.context.metadata.channel", "x-channel");
        user.put("synapse.api.grpc.context.metadata.tenant", "x-tenant");
        userProperties(user);
        process();
        assertThat(list(REMOTE_FIELDS)).containsExactly("correlationId", "tenant", "channel");
        assertThat(list(CORRELATION_FIELDS)).containsExactly("correlationId", "tenant", "channel");
    }

    @Test
    void keepsUserCommandLineValues() {
        userProperties(Map.of(REMOTE_FIELDS, "from-file"));
        environment
                .getPropertySources()
                .addFirst(new SimpleCommandLinePropertySource("--" + REMOTE_FIELDS + "=from-command-line"));
        process();
        assertThat(list(REMOTE_FIELDS)).containsExactly("from-command-line", "correlationId");
    }

    @Test
    void runningTwiceGivesTheSameResult() {
        userProperties(Map.of(REMOTE_FIELDS, "a"));
        process();
        process();
        assertThat(list(REMOTE_FIELDS)).containsExactly("a", "correlationId");
        assertThat(environment.getPropertySources().stream().filter(s -> s.getName().equals("synapseBaggageFields")))
                .hasSize(1);
    }

    @Test
    void runsAfterConfigDataIsLoaded() {
        assertThat(postProcessor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void isRegisteredInSpringFactories() {
        assertThat(SpringFactoriesLoader
                .forDefaultResourceLocation()
                .load(EnvironmentPostProcessor.class, null, (type, name, failure) -> {
                }))
                .anyMatch(BaggageFieldsEnvironmentPostProcessor.class::isInstance);
    }

    @Test
    void rejectsRestAttributeNamedCorrelationId() {
        userProperties(Map.of("synapse.api.rest.context.headers.correlationId", "X-Corr"));
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class,
                        ex -> assertThat(ex.description())
                                .contains("synapse.api.rest.context.headers", "reserved"));
    }

    @Test
    void rejectsGrpcAttributeNamedCorrelationId() {
        userProperties(Map.of("synapse.api.grpc.context.metadata.correlationId", "x-corr"));
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class,
                        ex -> assertThat(ex.description())
                                .contains("synapse.api.grpc.context.metadata", "reserved"));
    }

    @Test
    void rejectsReservedNameInAnyCase() {
        userProperties(Map.of("synapse.api.rest.context.headers.correlationid", "X-Corr"));
        assertThatThrownBy(this::process).isInstanceOf(SynapseConfigurationException.class);
    }

    @Test
    void rejectsDefaultCorrelationHeaderThatIsAlsoARemoteField() {
        userProperties(Map.of(REMOTE_FIELDS, "region,x-correlation-id"));
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .contains("synapse.correlation.header", REMOTE_FIELDS, "X-Correlation-Id",
                                    "x-correlation-id");
                    assertThat(ex.action()).contains("Rename");
                });
    }

    @Test
    void rejectsConfiguredCorrelationHeaderThatIsAlsoARemoteField() {
        userProperties(Map.of("synapse.correlation.header", "Request-Id", REMOTE_FIELDS, "REQUEST-ID"));
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class,
                        ex -> assertThat(ex.description()).contains("synapse.correlation.header", REMOTE_FIELDS));
    }

    @Test
    void rejectsCorrelationHeaderThatIsAlsoARestAttributeName() {
        userProperties(Map
                .of("synapse.correlation.header", "tenant", "synapse.api.rest.context.headers.Tenant",
                        "X-Tenant"));
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .contains("synapse.correlation.header", "synapse.api.rest.context.headers");
                    assertThat(ex.action()).contains("Rename");
                });
    }

    @Test
    void rejectsCorrelationHeaderThatIsAlsoAGrpcAttributeName() {
        userProperties(Map
                .of("synapse.correlation.header", "channel", "synapse.api.grpc.context.metadata.channel",
                        "x-channel"));
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class,
                        ex -> assertThat(ex.description())
                                .contains("synapse.correlation.header", "synapse.api.grpc.context.metadata"));
    }

    @Test
    void rejectsCorrelationHeaderNamedAfterTheCorrelationField() {
        userProperties(Map.of("synapse.correlation.header", "CorrelationId"));
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description()).contains("synapse.correlation.header", "correlationId");
                    assertThat(ex.action()).contains("synapse.correlation.header");
                });
    }

    @Test
    void rejectsCorrelationHeaderThatIsAPropagationHeader() {
        for (String header : List.of("TraceParent", "baggage", "x-b3-traceid")) {
            environment.getPropertySources().remove("user");
            userProperties(Map.of("synapse.correlation.header", header));
            assertThatThrownBy(this::process)
                    .as(header)
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                        assertThat(ex.description()).contains("synapse.correlation.header", header);
                        assertThat(ex.action()).contains("synapse.correlation.header");
                    });
        }
    }

    @Test
    void acceptsCorrelationHeaderThatIsNotABaggageField() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put(REMOTE_FIELDS, "region");
        user.put("synapse.api.rest.context.headers.tenant", "X-Correlation-Id-Not");
        userProperties(user);
        process();
        assertThat(list(REMOTE_FIELDS)).containsExactly("region", "correlationId", "tenant");
    }

    private void userProperties(Map<String, Object> properties) {
        environment.getPropertySources().addLast(new MapPropertySource("user", properties));
    }

    private void process() {
        postProcessor.postProcessEnvironment(environment, new SpringApplication());
    }

    private List<String> list(String name) {
        return Binder.get(environment).bind(name, Bindable.listOf(String.class)).orElse(List.of());
    }
}
