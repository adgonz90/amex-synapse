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

import static org.assertj.core.api.Assertions.assertThat;

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
