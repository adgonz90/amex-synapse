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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import io.americanexpress.synapse.core.internal.environment.ConfigSources;

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
    void startupReportLabelsTheSource() {
        assertThat(ConfigSources
                .label(new MapPropertySource(BaggageFieldsEnvironmentPostProcessor.PROPERTY_SOURCE_NAME,
                        Map.of("a", "b"))))
                .isEqualTo("Synapse baggage fields");
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

    @ParameterizedTest
    @CsvSource({"synapse.api.rest.context.headers, error", "synapse.api.rest.context.headers, error.source",
            "synapse.api.rest.context.headers, '[error.source]'", "synapse.api.grpc.context.metadata, error",
            "synapse.api.grpc.context.metadata, error.source"})
    void rejectsAttributeNamedErrorOrUnderError(String attributes, String key) {
        String name = key.replace("[", "").replace("]", "");
        userProperties(Map.of(attributes + (key.startsWith("[") ? "" : ".") + key, "x-error"));
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description()).contains("'" + name + "'", attributes, "ECS", "'error'");
                    assertThat(ex.action()).contains("error.");
                });
    }

    @ParameterizedTest
    @CsvSource({"synapse.api.rest.context.headers, '[@timestamp]', @timestamp",
            "synapse.api.rest.context.headers, log, log", "synapse.api.rest.context.headers, log.level, log",
            "synapse.api.grpc.context.metadata, process, process",
            "synapse.api.grpc.context.metadata, process.thread.name, process",
            "synapse.api.rest.context.headers, service, service",
            "synapse.api.rest.context.headers, service.name, service",
            "synapse.api.grpc.context.metadata, message, message", "synapse.api.rest.context.headers, tags, tags",
            "synapse.api.grpc.context.metadata, ecs, ecs", "synapse.api.grpc.context.metadata, ecs.version, ecs"})
    void rejectsAttributeNamedAfterAnEcsMember(String attributes, String key, String reserved) {
        String name = key.replace("[", "").replace("]", "");
        userProperties(Map.of(attributes + (key.startsWith("[") ? "" : ".") + key, "x-value"));
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description()).contains("'" + name + "'", "'" + reserved + "'", attributes, "ECS");
                    assertThat(ex.action())
                            .contains("'" + reserved + "'", "'" + reserved + ".'",
                                    "@timestamp, log, process, service, message, error, tags and ecs");
                });
    }

    @Test
    void acceptsAttributeNamesThatOnlyLookLikeEcsMembers() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("synapse.api.rest.context.headers.errorSource", "X-Error-Source");
        user.put("synapse.api.rest.context.headers.errors", "X-Errors");
        user.put("synapse.api.grpc.context.metadata.Error", "x-error");
        user.put("synapse.api.grpc.context.metadata.Log", "x-log");
        user.put("synapse.api.rest.context.headers.timestamp", "X-Timestamp");
        user.put("synapse.api.rest.context.headers.messages", "X-Messages");
        user.put("synapse.api.rest.context.headers.serviceName", "X-Service-Name");
        user.put("synapse.api.grpc.context.metadata.ecsVersion", "x-ecs-version");
        userProperties(user);
        process();
        assertThat(list(CORRELATION_FIELDS))
                .containsExactlyInAnyOrder("correlationId", "errorSource", "errors", "timestamp", "messages",
                        "serviceName", "Error", "Log", "ecsVersion");
    }

    @ParameterizedTest
    @CsvSource({"synapse.api.rest.context.headers, correlationId.source, correlationId",
            "synapse.api.grpc.context.metadata, traceId, traceId",
            "synapse.api.rest.context.headers, traceId.x, traceId", "synapse.api.rest.context.headers, spanId, spanId",
            "synapse.api.grpc.context.metadata, spanId.x, spanId",
            "synapse.api.rest.context.headers, operation, operation",
            "synapse.api.grpc.context.metadata, operation.name, operation",
            "synapse.api.rest.context.headers, errorCode, errorCode",
            "synapse.api.grpc.context.metadata, errorCategory.x, errorCategory"})
    void rejectsAttributeCollidingWithALogField(String attributes, String name, String field) {
        userProperties(Map.of(attributes + "." + name, "x-value"));
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description()).contains("'" + name + "'", "'" + field + "'", attributes);
                    assertThat(ex.action())
                            .contains("'" + field + "'",
                                    "correlationId, traceId, spanId, operation, errorCode and errorCategory");
                });
    }

    /**
     * The reason names what would happen: a name equal to an MDC field is the same MDC entry, so one value overwrites
     * the other; a name equal to a key-value makes Boot's Logback formats drop the line; a dotted name nests.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"traceId | one value would overwrite the other",
            "correlationId.source | nests a dotted name under its first part",
            "operation | Boot would drop the lines that carry both",
            "errorCode.x | nests a dotted name under its first part"})
    void theReasonFitsTheCollision(String name, String reason) {
        userProperties(Map.of("synapse.api.rest.context.headers." + name, "x-value"));
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class,
                        ex -> assertThat(ex.description()).contains(reason));
    }

    @ParameterizedTest
    @CsvSource({"synapse.api.rest.context.headers.tenant, synapse.api.rest.context.headers.tenant.region",
            "synapse.api.rest.context.headers.tenant, synapse.api.grpc.context.metadata.tenant.region",
            "synapse.api.grpc.context.metadata.tenant.region, synapse.api.rest.context.headers.tenant"})
    void rejectsAttributeNestedUnderAnother(String first, String second) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put(first, "x-a");
        user.put(second, "x-b");
        userProperties(user);
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description()).contains("'tenant.region'", "'tenant'", "Duplicate nested pairs");
                    assertThat(ex.action()).contains("tenant");
                });
    }

    @Test
    void acceptsAttributesThatOnlyShareAPrefix() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("synapse.api.rest.context.headers.tenant", "X-Tenant");
        user.put("synapse.api.rest.context.headers.tenantRegion", "X-Tenant-Region");
        user.put("synapse.api.grpc.context.metadata.tenant", "x-tenant");
        user.put("synapse.api.grpc.context.metadata.traceIdSource", "x-trace-id-source");
        user.put("synapse.api.grpc.context.metadata.operations", "x-operations");
        userProperties(user);
        process();
        assertThat(list(CORRELATION_FIELDS))
                .containsExactlyInAnyOrder("correlationId", "tenant", "tenantRegion", "traceIdSource", "operations");
    }

    @ParameterizedTest
    @CsvSource({"log.level, log", "traceId, traceId", "spanId.x, spanId", "correlationId.source, correlationId",
            "errorCode, errorCode", "'a,a.b', a", "'tenant.region', tenant"})
    void rejectsCorrelationFieldsUnderTheSameRules(String fields, String reserved) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put(CORRELATION_FIELDS, fields);
        user.put("synapse.api.rest.context.headers.tenant", "X-Tenant");
        userProperties(user);
        assertThatThrownBy(this::process)
                .isInstanceOfSatisfying(SynapseConfigurationException.class,
                        ex -> assertThat(ex.description()).contains(CORRELATION_FIELDS, "'" + reserved + "'"));
    }

    @Test
    void acceptsCorrelationFieldsThatRepeatSynapseFields() {
        userProperties(Map
                .of(CORRELATION_FIELDS, "correlationId,tenant,region",
                        "synapse.api.rest.context.headers.tenant", "X-Tenant"));
        process();
        assertThat(list(CORRELATION_FIELDS)).containsExactly("correlationId", "tenant", "region");
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
