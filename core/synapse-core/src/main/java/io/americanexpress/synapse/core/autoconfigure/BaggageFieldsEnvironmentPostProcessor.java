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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.internal.context.BaggageHeaders;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.SynapseBaggageFields;
import io.americanexpress.synapse.core.internal.context.TraceHeaders;

/**
 * Adds Synapse's baggage fields to Boot's baggage lists. Boot's OpenTelemetry bridge reads the remote and correlation
 * (MDC) baggage fields only from {@code management.tracing.baggage.remote-fields} and
 * {@code management.tracing.baggage.correlation.fields}, so Synapse merges the correlation id field and the context
 * attribute names bound under {@code synapse.api.rest.context.headers} and {@code synapse.api.grpc.context.metadata}
 * into those lists. The user's values come first and are kept; the merged lists are added as the highest-precedence
 * property source, {@value #PROPERTY_SOURCE_NAME}.
 * <p>
 * It fails startup when a context attribute is named {@code correlationId}; when one is named after a top-level member
 * Boot's ECS format writes itself ({@code @timestamp}, {@code log}, {@code process}, {@code service}, {@code message},
 * {@code error}, {@code tags} or {@code ecs}) or after a reserved log field ({@code correlationId}, {@code traceId},
 * {@code spanId}, and the key-values {@code operation}, {@code errorCode} and {@code errorCategory}), or starts with
 * such a name followed by a dot; and when one starts with another attribute's name followed by a dot ({@code tenant}
 * and {@code tenant.region}). The names are MDC entries: a name equal to an MDC field Synapse or Boot's tracing sets
 * would overwrite it (or be overwritten), and Boot's structured formats nest dotted names or write the MDC and the
 * key-values into one object, so Boot would drop the lines that carry the attribute. The entries the user
 * sets in {@code management.tracing.baggage.correlation.fields} are MDC entries too and follow the same rules, except
 * that one may be {@code correlationId} or a context attribute's name. It also fails when the
 * correlation id header ({@code synapse.correlation.header}) is, ignoring case, also a remote baggage field (the
 * correlation id field, a context attribute name or an entry of {@code management.tracing.baggage.remote-fields}) or a
 * trace or baggage propagation header ({@code baggage}, {@code traceparent}, {@code tracestate} or a B3 header). Edges
 * hide those headers from untrusted callers, while the correlation header is always accepted.
 */
final class BaggageFieldsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "synapseBaggageFields";

    private static final String REMOTE_FIELDS = "management.tracing.baggage.remote-fields";

    private static final String CORRELATION_HEADER = "synapse.correlation.header";

    /**
     * The top-level members Spring Boot 4.1's Log4j2 ECS format writes itself, in its order
     * ({@code ElasticCommonSchemaStructuredLogFormatter.jsonMembers}, with {@code service} from
     * {@code ElasticCommonSchemaProperties}). Context data is written in the same object and nested on dots, so a
     * context attribute that is or nests under one of them is a second member of that name, and Boot drops the line.
     */
    private static final List<EcsMember> ECS_MEMBERS = List
            .of(new EcsMember("@timestamp", "every line"), new EcsMember("log", "every line"),
                    new EcsMember("process", "every line"), new EcsMember("service", "every line"),
                    new EcsMember("message", "every line"), new EcsMember("error", "every line with an exception"),
                    new EcsMember("tags", "every line with a marker"), new EcsMember("ecs", "every line"));

    /** The member names for messages: {@code @timestamp, log, ..., tags and ecs}. */
    private static final String ECS_MEMBER_NAMES = String
            .join(", ", ecsMemberNames().subList(0, ECS_MEMBERS.size() - 1))
            + " and " + ecsMemberNames().getLast();

    /**
     * The log fields an MDC name must not be or nest under: the MDC names Synapse and Boot's tracing set, and the
     * key-values Synapse's failure and payload lines add ({@code OperationInterceptor}). A context attribute with one
     * of these names would be written twice on a line, which Boot's Logback structured formats can't do, or would be
     * nested under it in ECS.
     */
    private static final List<LogField> LOG_FIELDS = List
            .of(new LogField(ContextBinder.CORRELATION_BAGGAGE_FIELD, "the MDC field of the correlation id", true),
                    new LogField("traceId", "the MDC field Boot's tracing writes for the trace id", true),
                    new LogField("spanId", "the MDC field Boot's tracing writes for the span id", true),
                    new LogField("operation", "a key-value of Synapse's failure and payload lines", false),
                    new LogField("errorCode", "a key-value of Synapse's failure line", false),
                    new LogField("errorCategory", "a key-value of Synapse's failure line", false));

    /** The log field names for messages: {@code correlationId, ..., errorCode and errorCategory}. */
    private static final String LOG_FIELD_NAMES = String
            .join(", ", logFieldNames().subList(0, LOG_FIELDS.size() - 1))
            + " and " + logFieldNames().getLast();

    private static final String ATTRIBUTE = "context attribute name";

    private static final String CORRELATION_FIELD = "correlation field";

    private static final List<String> BAGGAGE_LISTS = List
            .of(REMOTE_FIELDS,
                    "management.tracing.baggage.correlation.fields");

    /** The W3C Baggage, W3C Trace Context and B3 headers, which inbound edges hide from untrusted callers. */
    private static final List<String> PROPAGATION_HEADERS = Stream
            .concat(Stream.of(BaggageHeaders.HEADER), TraceHeaders.NAMES.stream())
            .toList();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);
        Binder binder = Binder.get(environment);
        String correlationHeader = binder
                .bind(CORRELATION_HEADER, String.class)
                .orElse(SynapseCorrelationProperties.DEFAULT_HEADER);
        if (correlationHeader.equalsIgnoreCase(ContextBinder.CORRELATION_BAGGAGE_FIELD)) {
            throw new SynapseConfigurationException("The correlation id header '" + correlationHeader + "' ("
                    + CORRELATION_HEADER + ") is also the name of the correlation id baggage field '"
                    + ContextBinder.CORRELATION_BAGGAGE_FIELD + "', which is read from the header of the same name.",
                    "Set " + CORRELATION_HEADER + " to another header, such as "
                            + SynapseCorrelationProperties.DEFAULT_HEADER + ".");
        }
        for (String propagation : PROPAGATION_HEADERS) {
            if (correlationHeader.equalsIgnoreCase(propagation)) {
                throw new SynapseConfigurationException("The correlation id header '" + correlationHeader + "' ("
                        + CORRELATION_HEADER + ") is also the trace or baggage propagation header '" + propagation
                        + "', which inbound edges hide from untrusted callers.",
                        "Set " + CORRELATION_HEADER + " to another header, such as "
                                + SynapseCorrelationProperties.DEFAULT_HEADER + ".");
            }
        }
        Set<String> synapseFields = new LinkedHashSet<>();
        synapseFields.add(ContextBinder.CORRELATION_BAGGAGE_FIELD);
        // Every configured MDC name, with where it came from; the same name in both attribute maps is one attribute.
        Map<String, MdcName> mdcNames = new LinkedHashMap<>();
        for (String attributes : SynapseBaggageFields.ATTRIBUTE_MAPS) {
            Set<String> names = SynapseBaggageFields.attributeNames(binder, attributes);
            if (names.stream().anyMatch(ContextBinder.CORRELATION_BAGGAGE_FIELD::equalsIgnoreCase)) {
                throw new SynapseConfigurationException(
                        "The context attribute name '" + ContextBinder.CORRELATION_BAGGAGE_FIELD + "' under "
                                + attributes + " is reserved for the correlation id.",
                        "Rename the attribute; the correlation id header is set with synapse.correlation.header.");
            }
            for (String name : names) {
                rejectEcsMembers(name, attributes, ATTRIBUTE);
                rejectLogFields(name, attributes, ATTRIBUTE, false);
                mdcNames.putIfAbsent(name, new MdcName(attributes, ATTRIBUTE));
            }
            rejectCorrelationHeader(correlationHeader, names, attributes);
            synapseFields.addAll(names);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (String list : BAGGAGE_LISTS) {
            List<String> userFields = binder.bind(list, Bindable.listOf(String.class)).orElse(List.of());
            if (list.equals(REMOTE_FIELDS)) {
                rejectCorrelationHeader(correlationHeader, userFields, REMOTE_FIELDS);
            } else {
                // The user's correlation fields are MDC entries on every line too: the same rules, except that one
                // may be the correlation id field or a context attribute's name, which are already in the list.
                for (String name : userFields) {
                    rejectEcsMembers(name, list, CORRELATION_FIELD);
                    rejectLogFields(name, list, CORRELATION_FIELD, true);
                    mdcNames.putIfAbsent(name, new MdcName(list, CORRELATION_FIELD));
                }
            }
            Set<String> fields = new LinkedHashSet<>(userFields);
            fields.addAll(synapseFields);
            merged.put(list, String.join(",", fields));
        }
        rejectNested(mdcNames);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, merged));
    }

    /**
     * Rejects an MDC name named after a top-level member of Boot's ECS format, or starting with that name followed by
     * a dot. The match is case-sensitive, like Boot's check for a member written twice.
     */
    private static void rejectEcsMembers(String name, String source, String what) {
        for (EcsMember member : ECS_MEMBERS) {
            if (name.equals(member.name()) || name.startsWith(member.name() + ".")) {
                throw new SynapseConfigurationException("The " + what + " '" + name + "' under " + source
                        + " would be a log field under '" + member.name()
                        + "', which Spring Boot's ECS log format writes itself on " + member.writtenOn()
                        + ". It is an MDC entry, and the format nests a dotted name under its first part, so Boot "
                        + "would drop those lines while it has a value.",
                        "Rename it to a name that is not '" + member.name() + "' and doesn't start with '"
                                + member.name() + ".'. Boot's ECS format writes these top-level fields itself: "
                                + ECS_MEMBER_NAMES + ".");
            }
        }
    }

    /**
     * Rejects an MDC name named after a reserved log field (an MDC name Synapse or Boot's tracing sets, or a key-value
     * of Synapse's log lines), or starting with that name followed by a dot. The match is case-sensitive. With
     * {@code allowCorrelationId}, the name may be the correlation id field itself: a correlation field may list it, and
     * Synapse adds it anyway.
     */
    private static void rejectLogFields(String name, String source, String what, boolean allowCorrelationId) {
        for (LogField field : LOG_FIELDS) {
            boolean same = name.equals(field.name())
                    && !(allowCorrelationId && field.name().equals(ContextBinder.CORRELATION_BAGGAGE_FIELD));
            if (same || name.startsWith(field.name() + ".")) {
                String reason;
                if (!same) {
                    reason = "Boot's ECS log format nests a dotted name under its first part, so it can't write a "
                            + "line that carries both.";
                } else if (field.mdc()) {
                    reason = "The MDC holds one value per name, so one value would overwrite the other: the logs "
                            + "would show the wrong '" + field.name() + "', or lose the " + what + ".";
                } else {
                    reason = "Boot's Logback formats write the MDC and the key-values into one object and can't write "
                            + "a name twice, so Boot would drop the lines that carry both.";
                }
                throw new SynapseConfigurationException("The " + what + " '" + name + "' under " + source
                        + " would collide in the logs with '" + field.name() + "', " + field.description()
                        + ". It is an MDC entry. " + reason,
                        "Rename it to a name that is not '" + field.name() + "' and doesn't start with '"
                                + field.name() + ".'. These log fields are reserved: " + LOG_FIELD_NAMES + ".");
            }
        }
    }

    /**
     * Rejects two MDC names of which one starts with the other followed by a dot ({@code tenant} and
     * {@code tenant.region}): Boot's ECS format nests the second under the first, which is already a value.
     */
    private static void rejectNested(Map<String, MdcName> names) {
        for (Map.Entry<String, MdcName> outer : names.entrySet()) {
            for (Map.Entry<String, MdcName> inner : names.entrySet()) {
                String name = inner.getKey();
                String parent = outer.getKey();
                if (name.startsWith(parent + ".")) {
                    MdcName nested = inner.getValue();
                    MdcName first = outer.getValue();
                    throw new SynapseConfigurationException("The " + nested.what() + " '" + name + "' under "
                            + nested.source() + " nests under the " + first.what() + " '" + parent + "' under "
                            + first.source() + ". Both are MDC entries, and Boot's ECS log format nests a dotted name "
                            + "under its first part, so it can't write a line that carries both (Duplicate nested "
                            + "pairs added under '" + parent + "').",
                            "Rename one of them so that neither starts with the other followed by a dot, for "
                                    + "example '" + camelCase(name) + "' instead of '" + name + "'.");
                }
            }
        }
    }

    private static String camelCase(String dotted) {
        StringBuilder result = new StringBuilder(dotted.length());
        boolean upper = false;
        for (char ch : dotted.toCharArray()) {
            if (ch == '.') {
                upper = true;
            } else {
                result.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            }
        }
        return result.toString();
    }

    /**
     * Returns the reserved log field names: the MDC names Synapse and Boot's tracing set, and the key-values of
     * Synapse's log lines. {@code SynapseOperationIT} checks the failure line's key-values against it.
     *
     * @return the names
     */
    static List<String> logFieldNames() {
        return LOG_FIELDS.stream().map(LogField::name).toList();
    }

    /**
     * A reserved log field.
     *
     * @param name the field name
     * @param description what sets it, for messages
     * @param mdc whether it is an MDC entry (else a key-value of Synapse's log lines)
     */
    private record LogField(String name, String description, boolean mdc) {
    }

    /**
     * Where a configured MDC name came from.
     *
     * @param source the property it is bound from
     * @param what what it is, for messages
     */
    private record MdcName(String source, String what) {
    }

    /**
     * Returns the names of the top-level members Boot's ECS format writes itself, in its order. The ECS test in
     * {@code synapse-logging-log4j2} reads this list and checks it against the lines Boot really writes, so a Boot
     * upgrade that adds a member fails the build.
     *
     * @return the member names
     */
    static List<String> ecsMemberNames() {
        return ECS_MEMBERS.stream().map(EcsMember::name).toList();
    }

    /**
     * A top-level member of Boot's ECS format.
     *
     * @param name the member name
     * @param writtenOn the lines Boot writes it on
     */
    private record EcsMember(String name, String writtenOn) {
    }

    /**
     * Rejects a correlation header that is also a remote baggage field. Every remote field is read from the header of
     * the same name, and the edge hides those headers from untrusted callers, while the correlation header is
     * accepted whatever the trust setting.
     */
    private static void rejectCorrelationHeader(String correlationHeader, Collection<String> fields, String key) {
        for (String field : fields) {
            if (field.equalsIgnoreCase(correlationHeader)) {
                throw new SynapseConfigurationException("The correlation id header '" + correlationHeader + "' ("
                        + CORRELATION_HEADER + ") is also the baggage field '" + field + "' under " + key
                        + ". Baggage fields are read from the header of the same name, so the correlation header must "
                        + "not be one.",
                        "Rename one of them: set " + CORRELATION_HEADER
                                + " to another header, or rename the field under " + key + ".");
            }
        }
    }

    /**
     * Runs last, after config data and other post-processors have contributed the user's values.
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
