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
package io.americanexpress.synapse.api.grpc.internal.mapping;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.format.Formatter;
import org.springframework.format.datetime.standard.DateTimeFormatterRegistrar;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.api.grpc.internal.binding.ContextSource;
import io.americanexpress.synapse.api.grpc.internal.binding.FieldOverride;
import io.americanexpress.synapse.api.grpc.internal.mapping.FieldConverters.ComponentMapping;
import io.americanexpress.synapse.api.grpc.internal.mapping.FieldConverters.OneofGroup;
import io.americanexpress.synapse.api.grpc.internal.mapping.FieldConverters.RecordConverter;
import io.americanexpress.synapse.api.grpc.internal.mapping.FieldConverters.Site;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.internal.binding.ComponentRefs;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.internal.conditional.VersionTokens;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * Creates the mapping between a method's messages and its operation's records, and checks it once, when it is
 * created.
 * <ul>
 * <li>A component maps to the field whose JSON name ({@code FieldDescriptor.getJsonName()}) equals the component
 * name, unless {@code .map(path, component)} names another. A {@code .map} path is relative to the message of the
 * component's own record, and dotted to reach a field of a nested message.</li>
 * <li>Built in: {@code string} ↔ {@code String} or any type Boot's {@code ApplicationConversionService} converts
 * both ways (records excepted: they map to messages), with {@code java.time} values read and written in ISO only;
 * 32-bit integers ↔ {@code int}/{@code Integer} or {@code long}/{@code Long}; 64-bit integers ↔ {@code long}/
 * {@code Long}; {@code bool}, {@code double}, {@code float}; {@code bytes} ↔ {@code byte[]}; {@code Timestamp} ↔
 * {@code Instant}; {@code Duration} ↔ {@code java.time.Duration}; the wrapper types ↔ their value's type; enums by
 * name ({@link EnumNames}); {@code repeated} ↔ {@code List}; {@code map} ↔ {@code Map}; other messages ↔
 * records.</li>
 * <li>A {@code @Nullable} component needs a field with presence (a message, a wrapper, proto3 {@code optional}, a
 * {@code oneof} member), or an enum field whose zero value is unspecified. A {@code @Nullable} item type
 * ({@code List<@Nullable T>}, {@code Map<K, @Nullable V>}) maps to no field: a repeated element or map value is always
 * there.</li>
 * <li>So does a non-null request component bound to a scalar field (not repeated, not a message: a string, bytes, a
 * number, a bool or an enum), primitives included and at every depth of the request, because an unsent field without
 * presence reads as its default and would bind silently, where REST reports the value missing. The token fields of a
 * {@code VersionCondition}, the page fields of a {@code PageQuery} and {@code .context} components bind otherwise, and
 * a response writes every value, so neither is checked. These problems are collected over the whole request and thrown
 * as one {@link MappingException} ({@link MappingException#presenceOnly()}) once the request has mapped without any
 * other problem.</li>
 * <li>A {@code VersionCondition<V>} component of the input record maps to the request's {@code string etag} field,
 * whatever its name: REST's entity tag token, parsed with {@code EntityTags}, its opaque value read as a {@code V} by
 * core's {@code VersionTokens}, the codec REST uses.
 * An empty {@code etag} is no condition. {@code .etag(component)} writes the component's strong entity tag to the
 * response's {@code string etag} field, which no other component may write.</li>
 * <li>A {@code PageQuery} input, or a {@code PageQuery} component of the input record, reads the request's AIP-158
 * page fields ({@code int32 page_size}, {@code string page_token}, an optional {@code int32 skip}, and
 * {@code string order_by}, needed with {@code .sortable}; see {@link PageFields}), which no other component of the
 * input record may read. A {@code PageResult<T>} output writes the response's one repeated message field (the items),
 * {@code string next_page_token} (the token the caller passes to {@link RecordWriter#writePage}) and, when the
 * response has it, {@code optional int32 total_size}.</li>
 * <li>A {@code oneof} member is mapped only by an explicit {@code .map}.</li>
 * <li>Input: request fields without a component are ignored; every component needs a field or a {@code .context}.
 * A {@code Void} input reads nothing and takes only {@code google.protobuf.Empty} or another message without
 * fields.</li>
 * <li>Output: every component needs a field, and no two components write the same field (or one inside the other).
 * Components that write into different members of one {@code oneof} must all be {@code @Nullable}, and at most one
 * may have a value when a record is written; response fields without a component stay unset. {@code Void} writes the
 * empty message, {@code Optional<T>} writes its value, and {@code List<T>} writes the response's only field, which
 * must be repeated.</li>
 * </ul>
 * Each problem is a {@link MappingException}.
 */
public final class MessageMapping {

    /** Marks a list element in the proto path of a binding's field paths: the element's index goes there. */
    public static final String LIST_ELEMENT = "[]";

    /** Marks a map value in the proto path of a binding's field paths: the key, the caller's data, is left out. */
    public static final String MAP_VALUE = "{}";

    /** Boot's conversions with {@code java.time} values printed and parsed in ISO, whatever the default locale. */
    private static final ConversionService ISO = isoConversions();

    private static final TypeDescriptor STRING = TypeDescriptor.valueOf(String.class);

    /** The request and response field that carries a version as an entity tag (AIP-154): If-Match in a request. */
    private static final String ETAG_FIELD = "etag";

    /** The request field that carries REST's If-None-Match. */
    private static final String IF_NONE_MATCH_FIELD = "if_none_match";

    /** The request fields that carry an entity tag, each REST header's counterpart. */
    private static final List<String> TOKEN_FIELDS = List.of(ETAG_FIELD, IF_NONE_MATCH_FIELD);

    /** The response field of a page's next token (AIP-158), empty on the last page. */
    private static final String NEXT_PAGE_TOKEN = "next_page_token";

    /** The response field of a page's total (AIP-158), unset when the total is unknown. */
    private static final String TOTAL_SIZE = "total_size";

    /** The response field of whether another page follows: REST's hasNext, beyond AIP-158. */
    private static final String HAS_NEXT = "has_next";

    /** How to resubmit without a required {@code etag}, as REST's 428 says it for {@code If-Match}. */
    static final String IF_MATCH_REQUIRED = "is required; send the etag from a read of this resource, or * for any "
            + "current version";

    /** How to resubmit without a required {@code if_none_match}, as REST's 428 says it for {@code If-None-Match}. */
    static final String IF_NONE_MATCH_REQUIRED = "is required; send * to write only if the resource doesn't exist, "
            + "or the etag the resource must not have";

    private static final String UNMAPPABLE_ACTION = "Change the component type, or map another field with .map(...).";

    private static final Set<String> WRAPPERS = Set
            .of("google.protobuf.DoubleValue", "google.protobuf.FloatValue",
                    "google.protobuf.Int64Value", "google.protobuf.UInt64Value", "google.protobuf.Int32Value",
                    "google.protobuf.UInt32Value", "google.protobuf.BoolValue", "google.protobuf.StringValue",
                    "google.protobuf.BytesValue");

    private final boolean reading;

    /** The input record when reading: only its own components may be a {@code VersionCondition}. */
    private final @Nullable Class<?> input;

    /** Whether the request is a write's, whose If-Match {@code etag} must not be weak. */
    private final boolean write;

    /** The paging rules of the input's {@code PageQuery} component. */
    private final PagingRules paging;

    /** Whether the input record has a {@code PageQuery} component, which alone binds from the page fields. */
    private final boolean pagedInput;

    private final Map<String, FieldOverride> overrides = new HashMap<>();

    private final Map<String, ContextSource> contexts = new HashMap<>();

    /** The records mapped so far, by type and message, so that a record that contains itself maps once. */
    private final Map<String, RecordConverter> records = new HashMap<>();

    /** The request's required scalars bound to fields without presence, one line each, in mapping order. */
    private final List<String> presence = new ArrayList<>();

    private MessageMapping(@Nullable Class<?> input, boolean write, PagingRules paging, List<FieldOverride> overrides,
            List<ContextSource> contexts) {
        this.reading = input != null;
        this.input = input;
        this.write = write;
        this.paging = paging;
        this.pagedInput = input != null && input.isRecord() && ComponentRefs
                .components(input)
                .stream()
                .anyMatch(component -> component.type() == PageQuery.class);
        for (FieldOverride override : overrides) {
            this.overrides.put(key(override.component()), override);
        }
        for (ContextSource context : contexts) {
            this.contexts.put(key(context.component()), context);
        }
    }

    /**
     * Creates the reader of a write's request (a method bound to a {@code CommandOperation}).
     *
     * @param message the request message
     * @param recordType the operation's input type: a record, or {@code Void}
     * @param overrides the method's {@code .map} declarations (those of output records are ignored)
     * @param contexts the method's {@code .context} declarations
     * @return the reader
     * @throws MappingException when the request can't be mapped to the input; one listing every required scalar
     *         without presence, when that is the only problem
     */
    public static RecordReader reader(Descriptor message, Class<?> recordType, List<FieldOverride> overrides,
            List<ContextSource> contexts) {
        return reader(message, recordType, overrides, contexts, true);
    }

    /**
     * Creates the reader of a method's request.
     *
     * @param message the request message
     * @param recordType the operation's input type: a record, or {@code Void}
     * @param overrides the method's {@code .map} declarations (those of output records are ignored)
     * @param contexts the method's {@code .context} declarations
     * @param write whether the method is bound to a {@code CommandOperation}: as REST checks a write's preconditions
     *        only, a weak or unbound entity tag fails a write and is ignored or bound on a read
     * @return the reader
     * @throws MappingException when the request can't be mapped to the input; one listing every required scalar
     *         without presence, when that is the only problem
     */
    public static RecordReader reader(Descriptor message, Class<?> recordType, List<FieldOverride> overrides,
            List<ContextSource> contexts, boolean write) {
        return reader(message, recordType, overrides, contexts, write, PagingRules.defaults());
    }

    /**
     * Creates the reader of a method's request, whose {@code PageQuery} (the input, or a component of the input
     * record) reads the request's AIP-158 page fields with the method's paging rules.
     *
     * @param message the request message
     * @param recordType the operation's input type: a record, or {@code Void}
     * @param overrides the method's {@code .map} declarations (those of output records are ignored)
     * @param contexts the method's {@code .context} declarations
     * @param write whether the method is bound to a {@code CommandOperation}
     * @param paging the page sizes, the offset cap and the method's sort keys
     * @return the reader
     * @throws MappingException when the request can't be mapped to the input, including a {@code PageQuery} whose page
     *         fields are missing or have another shape; one listing every required scalar without presence, when that
     *         is the only problem
     */
    public static RecordReader reader(Descriptor message, Class<?> recordType, List<FieldOverride> overrides,
            List<ContextSource> contexts, boolean write, PagingRules paging) {
        if (recordType == Void.class) {
            if (!message.getFields().isEmpty()) {
                throw new MappingException(
                        "the input Void can't be mapped to " + message.getFullName() + ", which has fields",
                        "Use google.protobuf.Empty as the request, or declare a record input for its fields.");
            }
            return new RecordReader(null, Map.of(), null, List.of());
        }
        if (recordType == PageQuery.class) {
            // The whole input is read from the page fields; no other field is read.
            PageFields page = PageFields
                    .of(message, paging, "the input PageQuery reads the request's page fields, but ");
            return new RecordReader(null, page, Map.copyOf(page.fieldPaths("")), null,
                    unbound(message, write, Set.of()));
        }
        if (!recordType.isRecord()) {
            throw new MappingException(
                    "the input " + recordType.getSimpleName() + " can't be mapped to " + message.getFullName(),
                    "Declare the operation's input as a record, or as Void if it takes none.");
        }
        MessageMapping mapping = new MessageMapping(recordType, write, paging, overrides, contexts);
        RecordConverter root = mapping.record(ResolvableType.forClass(recordType), message);
        if (!mapping.presence.isEmpty()) {
            // Only once the whole request mapped: any other problem fails at once, as it is found.
            throw MappingException.presence(mapping.presence);
        }
        FieldDescriptor ifMatch = null;
        Set<String> bound = new HashSet<>();
        for (ComponentMapping component : root.components()) {
            if (component.condition()) {
                FieldDescriptor token = component.fields().getFirst();
                bound.add(token.getName());
                if (token.getName().equals(ETAG_FIELD)) {
                    ifMatch = token;
                }
            }
        }
        return new RecordReader(root, fieldPaths(root), ifMatch, unbound(message, write, bound));
    }

    /**
     * Returns the token fields a write's request may carry that no condition binds. A write fails closed on one that
     * is sent (REST's unbound If-Match and If-None-Match rule); a read ignores it, as REST ignores an unbound If-Match
     * on GET.
     */
    private static List<FieldDescriptor> unbound(Descriptor message, boolean write, Set<String> bound) {
        List<FieldDescriptor> unbound = new ArrayList<>();
        if (write) {
            for (String name : TOKEN_FIELDS) {
                FieldDescriptor field = message.findFieldByName(name);
                if (field != null && !bound.contains(name) && !field.isRepeated()
                        && field.getJavaType() == FieldDescriptor.JavaType.STRING) {
                    unbound.add(field);
                }
            }
        }
        return unbound;
    }

    /**
     * Creates the writer of a method's response, from the output type as resolved against the operation's class (for
     * example {@code MethodDeclaration.outputGenericType()}): its {@code Type} alone may be the type variable
     * {@code O}.
     *
     * @param message the response message
     * @param outputType the operation's generic output type
     * @param overrides the method's {@code .map} declarations (those of input records are ignored)
     * @return the writer
     * @throws MappingException when the output can't be mapped to the response
     */
    public static RecordWriter writer(Descriptor message, ResolvableType outputType, List<FieldOverride> overrides) {
        return writer(message, outputType, overrides, null);
    }

    /**
     * Creates the writer of a method's response, as {@link #writer(Descriptor, ResolvableType, List)} does, that also
     * writes the {@code .etag} component's strong entity tag to the response's {@code string etag} field. The binding
     * checks admit {@code .etag} only on a record output (or an {@code Optional} of one).
     *
     * @param message the response message
     * @param outputType the operation's generic output type
     * @param overrides the method's {@code .map} declarations (those of input records are ignored)
     * @param etag the {@code .etag} component of the output record, or {@code null} for none
     * @return the writer
     * @throws MappingException when the output can't be mapped to the response, the response has no
     *         {@code string etag} field for {@code .etag}, or another component writes that field
     */
    public static RecordWriter writer(Descriptor message, ResolvableType outputType, List<FieldOverride> overrides,
            @Nullable ResolvedComponent etag) {
        ResolvableType type = outputType;
        if (type.resolve() == Optional.class) {
            type = type.getGeneric(0);
        }
        Class<?> raw = type.resolve(Object.class);
        if (raw == Void.class || raw == void.class) {
            return RecordWriter.empty(message);
        }
        MessageMapping mapping = new MessageMapping(null, false, PagingRules.defaults(), overrides, List.of());
        if (raw == List.class) {
            return mapping.listWriter(message, type);
        }
        if (raw == PageResult.class) {
            return mapping.pageWriter(message, type);
        }
        if (!raw.isRecord()) {
            throw new MappingException(
                    "the output " + display(type) + " can't be mapped to " + message.getFullName(),
                    "Return a record, a List, an Optional or Void from the operation.");
        }
        FieldDescriptor etagField = etag == null
                ? null
                : tokenField(message, ETAG_FIELD, ".etag(...) writes the response's string field etag, but ",
                        ", or remove .etag(...).");
        RecordConverter root = mapping.record(type, message);
        if (etag == null || etagField == null) {
            return RecordWriter.record(message, root, fieldPaths(root));
        }
        for (ComponentMapping component : root.components()) {
            if (component.fields().getFirst().equals(etagField)) {
                String name = describe(component.component());
                throw new MappingException(
                        name + " and .etag(...) both write " + message.getFullName() + "." + ETAG_FIELD,
                        "Map " + name + " to another field, or remove .etag(...).");
            }
        }
        return RecordWriter.record(message, root, fieldPaths(root), new RecordWriter.ETagWriter(etag, etagField));
    }

    /**
     * Returns the field of a message that carries an entity tag token: a singular {@code string}, outside a real
     * {@code oneof} (a proto3 {@code optional} is fine), where setting it can't clear another member.
     *
     * @param message the message
     * @param name the field name ({@code etag} or {@code if_none_match})
     * @param subject the start of the problem, up to the field's description
     * @param actionTail what the action adds after naming the fix
     * @return the field
     * @throws MappingException when the message has no such field, or the field has another shape
     */
    private static FieldDescriptor tokenField(Descriptor message, String name, String subject, String actionTail) {
        String messageName = message.getFullName();
        FieldDescriptor field = message.findFieldByName(name);
        if (field == null) {
            throw new MappingException(subject + messageName + " has no string field " + name,
                    "Add string " + name + " to " + messageName + actionTail);
        }
        String fieldName = messageName + "." + name;
        if (field.isRepeated() || field.getJavaType() != FieldDescriptor.JavaType.STRING) {
            throw new MappingException(subject + fieldName + " is " + typeName(field) + ", not string",
                    "Declare " + fieldName + " as string" + actionTail);
        }
        OneofDescriptor oneof = field.getRealContainingOneof();
        if (oneof != null) {
            throw new MappingException(subject + fieldName + " is part of oneof " + oneof.getName(),
                    "Move " + fieldName + " out of oneof " + oneof.getName() + actionTail);
        }
        return field;
    }

    /**
     * Returns a field's type as the {@code .proto} file declares it: {@code string},
     * {@code repeated synapse.fixture.v1.Author}, {@code map<string, int64>}.
     *
     * @param field the field
     * @return the type
     */
    static String typeName(FieldDescriptor field) {
        if (field.isMapField()) {
            Descriptor entry = field.getMessageType();
            return "map<" + scalarName(entry.findFieldByNumber(1)) + ", " + scalarName(entry.findFieldByNumber(2))
                    + ">";
        }
        return (field.isRepeated() ? "repeated " : "") + scalarName(field);
    }

    /**
     * Returns a field's single-value type: the scalar's name, or the message's or enum's full name.
     *
     * @param field the field
     * @return the type
     */
    static String scalarName(FieldDescriptor field) {
        return switch (field.getJavaType()) {
            case MESSAGE -> field.getMessageType().getFullName();
            case ENUM -> field.getEnumType().getFullName();
            default -> field.getType().name().toLowerCase(Locale.ROOT);
        };
    }

    private RecordWriter listWriter(Descriptor message, ResolvableType type) {
        List<FieldDescriptor> fields = message.getFields();
        if (fields.size() != 1 || !fields.getFirst().isRepeated() || fields.getFirst().isMapField()) {
            throw new MappingException(
                    message.getFullName() + " must have a single field, a repeated one, for the output "
                            + display(type),
                    "Give the response message a single repeated field, or return a record from the operation.");
        }
        FieldDescriptor field = fields.getFirst();
        Site site = new Site("the output " + display(type), message.getFullName() + "." + field.getName());
        FieldConverter elements = single(field, type.getGeneric(0), site);
        if (elements == null) {
            throw new MappingException("the output " + display(type) + " can't be mapped to " + site.field() + " ("
                    + typeName(field) + ")", UNMAPPABLE_ACTION);
        }
        return RecordWriter.list(message, field, FieldConverters.list(elements, site));
    }

    /**
     * Maps a {@code PageResult<T>} output to an AIP-158 response: its one repeated message field (any name, not a
     * {@code map}) holds the items, {@code string next_page_token} the next page's token, and an
     * {@code optional int32 total_size}, when the response has one, the total.
     */
    private RecordWriter pageWriter(Descriptor message, ResolvableType type) {
        String messageName = message.getFullName();
        String output = "the output " + display(type);
        List<FieldDescriptor> repeated = message
                .getFields()
                .stream()
                .filter(field -> field.isRepeated() && !field.isMapField()
                        && field.getJavaType() == FieldDescriptor.JavaType.MESSAGE)
                .toList();
        if (repeated.size() != 1) {
            String found = repeated.isEmpty()
                    ? "has none"
                    : "has " + repeated.size() + " ("
                            + repeated.stream().map(FieldDescriptor::getName).collect(Collectors.joining(", ")) + ")";
            throw new MappingException(
                    output + " writes its items to the response's one repeated message field, but " + messageName
                            + " " + found,
                    "Give " + messageName + " one repeated message field for the items, as AIP-158 pages do.");
        }
        FieldDescriptor items = repeated.getFirst();
        Site site = new Site(output, messageName + "." + items.getName());
        FieldConverter elements = single(items, type.getGeneric(0), site);
        if (elements == null) {
            throw new MappingException(output + " can't be mapped to " + site.field() + " (" + typeName(items) + ")",
                    UNMAPPABLE_ACTION);
        }
        String subject = output + " writes the next page's token to the response's string next_page_token, but ";
        FieldDescriptor nextPageToken = PageFields
                .field(message, NEXT_PAGE_TOKEN, FieldDescriptor.Type.STRING, subject, ": AIP-158 pages carry it.");
        // Beyond AIP-158: REST always writes hasNext, which a page past the offset cap has without a token.
        FieldDescriptor hasNext = PageFields
                .field(message, HAS_NEXT, FieldDescriptor.Type.BOOL,
                        output + " writes whether another page follows to the response's bool has_next, but ",
                        ": Synapse pages carry it, as REST's hasNext.");
        FieldDescriptor totalSize = message.findFieldByName(TOTAL_SIZE);
        if (totalSize != null) {
            String totalSubject = output + " writes its total to the response's optional int32 or int64 total_size, "
                    + "but ";
            String totalName = messageName + "." + TOTAL_SIZE;
            String totalAction = "Declare " + totalName + " as optional int32 or optional int64, so that an unknown "
                    + "total stays unset.";
            FieldDescriptor.Type totalType = totalSize.getType();
            if (totalSize.isRepeated()
                    || (totalType != FieldDescriptor.Type.INT32 && totalType != FieldDescriptor.Type.INT64)) {
                throw new MappingException(
                        totalSubject + totalName + " is " + typeName(totalSize) + ", not int32 or int64",
                        totalAction);
            }
            OneofDescriptor oneof = totalSize.getRealContainingOneof();
            if (oneof != null) {
                throw new MappingException(totalSubject + totalName + " is part of oneof " + oneof.getName(),
                        "Move " + totalName + " out of oneof " + oneof.getName() + ", or remove it: a page's total is "
                                + "optional.");
            }
            if (!totalSize.hasPresence()) {
                throw new MappingException(totalSubject + totalName + " has no presence", totalAction);
            }
        }
        return RecordWriter
                .page(message, new RecordWriter.PageWriter(items, FieldConverters.list(elements, site), nextPageToken,
                        hasNext, totalSize));
    }

    private RecordConverter record(ResolvableType type, Descriptor message) {
        String key = type + "|" + message.getFullName();
        RecordConverter known = records.get(key);
        if (known != null) {
            return known;
        }
        Class<?> recordType = type.toClass();
        RecordConverter converter = new RecordConverter(recordType, message);
        records.put(key, converter);
        List<ComponentMapping> components = new ArrayList<>();
        for (ResolvedComponent component : ComponentRefs.components(recordType)) {
            components.add(component(type, message, component));
        }
        converter.components(components);
        if (!reading) {
            converter.oneofs(checkOverlaps(message, components));
        }
        return converter;
    }

    private ComponentMapping component(ResolvableType owner, Descriptor message, ResolvedComponent component) {
        String name = describe(component);
        String messageName = message.getFullName();
        Class<?> inputType = input;
        if (inputType != null && component.type() == VersionCondition.class) {
            return condition(inputType, owner, message, component);
        }
        if (inputType != null && component.type() == PageQuery.class) {
            if (component.recordType() != inputType) {
                throw new MappingException(
                        name + " is a PageQuery outside the input record " + inputType.getSimpleName(),
                        "Declare the PageQuery as a component of " + inputType.getSimpleName()
                                + " itself, not inside another type.");
            }
            return ComponentMapping.page(component, PageFields.of(message, paging, name + " is a PageQuery, but "));
        }
        ContextSource context = reading ? contexts.get(key(component)) : null;
        if (context != null) {
            return ComponentMapping.context(component, context);
        }
        FieldOverride override = overrides.get(key(component));
        List<FieldDescriptor> path;
        if (override != null) {
            path = resolve(message, override.fieldPath());
            if (path == null) {
                throw new MappingException(
                        ".map(\"" + override.fieldPath() + "\", ...) names no field of " + messageName,
                        "Use a field path of " + messageName + ", dotted for nested messages.");
            }
        } else {
            FieldDescriptor field = byJsonName(message, component.name());
            if (field == null) {
                if (!reading) {
                    throw new MappingException(name + " has no field in " + messageName,
                            "Add the field to the message, or map it with .map(...).");
                }
                throw new MappingException(name + " has no source",
                        "Add the field to " + messageName + ", map one with .map(...), or set it with .context(...).");
            }
            OneofDescriptor oneof = field.getRealContainingOneof();
            if (oneof != null) {
                throw new MappingException(messageName + "." + field.getName() + " is part of oneof " + oneof.getName(),
                        "Map it explicitly with .map(\"" + field.getName() + "\", ...).");
            }
            path = List.of(field);
        }
        String protoPath = path.stream().map(FieldDescriptor::getName).collect(Collectors.joining("."));
        if (inputType != null && component.recordType() == inputType && path.size() == 1
                && TOKEN_FIELDS.contains(protoPath)) {
            // The token fields carry entity tags, as REST's If-Match and If-None-Match do; their text is not a value.
            throw new MappingException(
                    name + " is mapped to " + messageName + "." + protoPath + ", which carries an entity tag: only a "
                            + "VersionCondition binds from it",
                    "Declare " + name + " as a VersionCondition, or map it to another field with .map(...).");
        }
        if (pagedInput && component.recordType() == inputType && path.size() == 1
                && PageFields.NAMES.contains(protoPath)) {
            // As REST's paging parameters, which no other query source may read on a route that binds PageQuery.
            throw new MappingException(
                    name + " is mapped to " + messageName + "." + protoPath + ", a page field, and the input binds "
                            + "PageQuery from the page fields page_size, page_token, skip and order_by",
                    "Map " + name + " to another field with .map(...), or remove it from the input.");
        }
        FieldDescriptor leaf = path.getLast();
        Site site = new Site(name, messageName + "." + protoPath);
        FieldConverter converter = converter(leaf, ResolvableType.forType(component.genericType(), owner), site);
        if (converter == null) {
            throw new MappingException(name + " can't be mapped to " + site.field() + " (" + typeName(leaf) + ")",
                    UNMAPPABLE_ACTION);
        }
        if (component.nullable() && !hasPresence(leaf)) {
            throw new MappingException(name + " is @Nullable but " + site.field() + " has no presence",
                    "Use a wrapper type or proto3 optional for the field, or make the component non-null.");
        }
        if (leaf.isRepeated() && component.nullableItems()) {
            // REST passes a null item of a @Nullable item type through; a repeated element or map value is always
            // there, so the two protocols would differ.
            throw new MappingException(
                    (leaf.isMapField() ? "the values of " : "the elements of ") + name + " are @Nullable but "
                            + site.field() + " has no presence for them",
                    "Make the item type non-null (a repeated or map field never holds a missing element), or bind the "
                            + "operation over REST only.");
        }
        if (reading && !component.nullable() && isScalar(leaf) && !hasPresence(leaf)) {
            // An unsent plain field reads as its default ("", 0, false, the zero enum value), so a missing value
            // would bind silently, where REST reports it missing. Collected, so that one failure lists them all.
            presence
                    .add(name + " is required, but " + site.field() + " has no presence, so an unsent field would "
                            + "bind its default");
        }
        return ComponentMapping.field(component, path, protoPath, converter);
    }

    /**
     * Maps the input's {@code VersionCondition} to a request token field, by type and a fixed name, whatever the
     * component is called: {@code etag} (AIP-154) with REST's {@code If-Match} semantics by default, or
     * {@code if_none_match} with REST's {@code If-None-Match} semantics when {@code .map("if_none_match", ...)} names
     * it, as REST's {@code .header(...)} decides the header. Its version type must be one of core's version types
     * ({@link VersionTokens}), as on REST. A weak {@code etag} fails a write, as REST compares {@code If-Match}
     * strongly; a weak {@code if_none_match}, and any weak tag on a read, binds its value.
     */
    private ComponentMapping condition(Class<?> inputType, ResolvableType owner, Descriptor message,
            ResolvedComponent component) {
        String name = describe(component);
        if (component.recordType() != inputType) {
            throw new MappingException(
                    name + " is a VersionCondition outside the input record " + inputType.getSimpleName(),
                    "Declare the VersionCondition as a component of " + inputType.getSimpleName()
                            + " itself, not inside another type.");
        }
        FieldOverride override = overrides.get(key(component));
        String fieldName = override == null ? ETAG_FIELD : override.fieldPath();
        boolean ifMatch = fieldName.equals(ETAG_FIELD);
        if (!ifMatch && !fieldName.equals(IF_NONE_MATCH_FIELD)) {
            throw new MappingException(
                    name + " is a VersionCondition, which binds only from the request's etag or if_none_match field, "
                            + "not from .map(\"" + fieldName + "\", ...)",
                    "Map it to \"etag\" (If-Match) or \"if_none_match\" (If-None-Match).");
        }
        FieldDescriptor etag = tokenField(message, fieldName, name + " is a VersionCondition, but ",
                ifMatch
                        ? ": it carries the version as an entity tag, such as \"5\" or *."
                        : ": it carries If-None-Match's entity tag, such as * or \"5\".");
        Class<?> versionClass = ResolvableType.forType(component.genericType(), owner).getGeneric(0).resolve();
        if (versionClass == null || !VersionTokens.supports(versionClass)) {
            // One set of version types for every protocol: the ones core's version token codec reads.
            String versionName = versionClass == null ? "?" : versionClass.getSimpleName();
            throw new MappingException(
                    name + " is a VersionCondition<" + versionName + ">, which is not a version type",
                    "Declare a version type: " + VersionTokens.TYPES + ".");
        }
        return ComponentMapping
                .condition(component, etag,
                        FieldConverters.versionCondition(versionClass, write && ifMatch),
                        ifMatch ? IF_MATCH_REQUIRED : IF_NONE_MATCH_REQUIRED);
    }

    private @Nullable FieldConverter converter(FieldDescriptor field, ResolvableType type, Site site) {
        Class<?> raw = type.resolve();
        if (raw == null) {
            return null;
        }
        if (field.isMapField()) {
            if (raw != Map.class) {
                return null;
            }
            Descriptor entry = field.getMessageType();
            FieldDescriptor keyField = entry.findFieldByNumber(1);
            FieldDescriptor valueField = entry.findFieldByNumber(2);
            FieldConverter keys = single(keyField, type.getGeneric(0), site);
            FieldConverter values = single(valueField, type.getGeneric(1), site);
            return keys == null || values == null
                    ? null
                    : FieldConverters.map(entry, keyField, valueField, keys, values, site);
        }
        if (field.isRepeated()) {
            if (raw != List.class) {
                return null;
            }
            FieldConverter elements = single(field, type.getGeneric(0), site);
            return elements == null ? null : FieldConverters.list(elements, site);
        }
        return single(field, type, site);
    }

    /** The converter of one value of the field (an element, for a repeated field), or {@code null} if none fits. */
    private @Nullable FieldConverter single(FieldDescriptor field, ResolvableType type, Site site) {
        Class<?> raw = type.resolve();
        if (raw == null) {
            return null;
        }
        Class<?> boxed = ClassUtils.resolvePrimitiveIfNecessary(raw);
        return switch (field.getJavaType()) {
            case INT -> FieldConverters.int32(field, boxed, site);
            case LONG -> boxed == Long.class ? FieldConverters.int64(field, site) : null;
            case FLOAT -> boxed == Float.class ? FieldConverters.IDENTITY : null;
            case DOUBLE -> boxed == Double.class ? FieldConverters.IDENTITY : null;
            case BOOLEAN -> boxed == Boolean.class ? FieldConverters.IDENTITY : null;
            case BYTE_STRING -> raw == byte[].class ? FieldConverters.BYTES : null;
            case STRING -> string(type, raw);
            case ENUM -> enumeration(field, raw, site);
            case MESSAGE -> message(field.getMessageType(), type, raw, site);
        };
    }

    private static @Nullable FieldConverter string(ResolvableType type, Class<?> raw) {
        if (raw == String.class) {
            return FieldConverters.IDENTITY;
        }
        // A record's toString doesn't read back, and containers have their own field shapes.
        if (raw.isRecord() || raw == Object.class || raw == Optional.class || raw.isArray()
                || Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) {
            return null;
        }
        TypeDescriptor target = new TypeDescriptor(type, null, null);
        if (!ISO.canConvert(STRING, target) || !ISO.canConvert(target, STRING)) {
            return null;
        }
        return FieldConverters.converted(ISO, target);
    }

    /**
     * Boot's application conversions with an ISO {@code java.time} registrar added last, so that its printers and
     * parsers win: {@code LocalDate.of(2026, 11, 6)} prints as {@code 2026-11-06}, not in the locale's short style.
     * {@code Instant} gets a strict ISO formatter after it, because the registrar's own also reads epoch milliseconds
     * and RFC 1123 dates.
     */
    private static ConversionService isoConversions() {
        ApplicationConversionService conversions = new ApplicationConversionService();
        DateTimeFormatterRegistrar iso = new DateTimeFormatterRegistrar();
        iso.setUseIsoFormat(true);
        iso.registerFormatters(conversions);
        conversions.addFormatterForFieldType(Instant.class, new IsoInstantFormatter());
        return conversions;
    }

    /** Reads and prints an {@code Instant} in ISO-8601 only ({@code 2026-11-06T10:15:30Z}). */
    private static final class IsoInstantFormatter implements Formatter<Instant> {

        @Override
        public Instant parse(String text, Locale locale) {
            return Instant.parse(text);
        }

        @Override
        public String print(Instant instant, Locale locale) {
            return instant.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable FieldConverter enumeration(FieldDescriptor field, Class<?> raw, Site site) {
        if (!raw.isEnum()) {
            return null;
        }
        Class<? extends Enum<?>> enumType = (Class<? extends Enum<?>>) raw;
        Enum<?> missing = EnumNames.firstWithoutValue(enumType, field.getEnumType());
        if (missing != null) {
            String javaName = enumType.getSimpleName();
            throw new MappingException(
                    site.subject() + " can't be mapped to " + site.field() + " (" + typeName(field)
                            + "): no value for " + javaName + "." + missing.name(),
                    "Add a value for every constant of " + javaName + ", named like the constant or "
                            + EnumNames.prefix(field.getEnumType().getName())
                            + "_<constant>, or change the component type.");
        }
        Enum<?> unspecified = EnumNames.firstMatchingUnspecified(enumType, field.getEnumType());
        if (unspecified != null) {
            String constant = enumType.getSimpleName() + "." + unspecified.name();
            throw new MappingException(
                    site.subject() + " can't be mapped to " + site.field() + " (" + typeName(field) + "): "
                            + constant + " matches the unspecified value "
                            + EnumNames.toProto(unspecified, field.getEnumType()).getName(),
                    "Remove " + constant + ": the unspecified value maps to null.");
        }
        return FieldConverters.enumeration(enumType, field.getEnumType());
    }

    private @Nullable FieldConverter message(Descriptor descriptor, ResolvableType type, Class<?> raw, Site site) {
        String name = descriptor.getFullName();
        if (name.equals("google.protobuf.Timestamp")) {
            return raw == Instant.class ? FieldConverters.timestamp(descriptor, site) : null;
        }
        if (name.equals("google.protobuf.Duration")) {
            return raw == java.time.Duration.class ? FieldConverters.duration(descriptor) : null;
        }
        if (WRAPPERS.contains(name)) {
            FieldDescriptor value = descriptor.findFieldByNumber(1);
            FieldConverter inner = single(value, type, site);
            return inner == null ? null : FieldConverters.wrapper(descriptor, value, inner);
        }
        return raw.isRecord() ? record(type, descriptor) : null;
    }

    /** Whether a field holds one plain value: not repeated (nor a map), and not a message. */
    private static boolean isScalar(FieldDescriptor field) {
        return !field.isRepeated() && field.getJavaType() != FieldDescriptor.JavaType.MESSAGE;
    }

    private static boolean hasPresence(FieldDescriptor field) {
        return field.hasPresence() || (field.getJavaType() == FieldDescriptor.JavaType.ENUM && !field.isRepeated()
                && EnumNames.hasUnspecifiedZero(field.getEnumType()));
    }

    /**
     * Fails when two output components write the same field, or one writes a field inside the other's message. Two
     * components that write into different members of one {@code oneof} (the member itself or a field inside it) must
     * both be {@code @Nullable}: a component that always has a value would always clear the others. They are returned
     * as a group, of which at most one may have a value when a record is written, as the request side reads at most one
     * member.
     */
    private static List<OneofGroup> checkOverlaps(Descriptor message, List<ComponentMapping> components) {
        // By oneof: each component that writes into it, and the member it writes.
        Map<String, Map<Integer, String>> oneofs = new LinkedHashMap<>();
        for (int i = 0; i < components.size(); i++) {
            for (int j = i + 1; j < components.size(); j++) {
                List<FieldDescriptor> first = components.get(i).fields();
                List<FieldDescriptor> second = components.get(j).fields();
                int split = splitInOneof(first, second);
                if (split >= 0) {
                    String parent = first
                            .subList(0, split)
                            .stream()
                            .map(field -> field.getName() + ".")
                            .collect(Collectors.joining());
                    OneofDescriptor oneof = Objects.requireNonNull(first.get(split).getRealContainingOneof());
                    String oneofName = message.getFullName() + "." + parent + oneof.getName();
                    ResolvedComponent one = components.get(i).component();
                    ResolvedComponent other = components.get(j).component();
                    ResolvedComponent required = !one.nullable() ? one : (!other.nullable() ? other : null);
                    if (required != null) {
                        throw new MappingException(describe(one) + " and " + describe(other) + " both write oneof "
                                + oneofName + ", and " + describe(required) + " is not @Nullable",
                                "Declare each component that writes a member of the oneof @Nullable, so that at most "
                                        + "one has a value, or map only one component to the oneof.");
                    }
                    Map<Integer, String> group = oneofs.computeIfAbsent(oneofName, name -> new TreeMap<>());
                    group.put(i, first.get(split).getName());
                    group.put(j, second.get(split).getName());
                    continue;
                }
                int shorter = Math.min(first.size(), second.size());
                if (shorter > 0 && first.subList(0, shorter).equals(second.subList(0, shorter))) {
                    String path = first
                            .subList(0, shorter)
                            .stream()
                            .map(FieldDescriptor::getName)
                            .collect(Collectors.joining("."));
                    throw new MappingException(describe(components.get(i).component()) + " and "
                            + describe(components.get(j).component()) + " both write " + message.getFullName() + "."
                            + path, "Map each component to its own field.");
                }
            }
        }
        List<OneofGroup> groups = new ArrayList<>();
        oneofs.forEach((oneof, members) -> {
            List<Integer> indexes = List.copyOf(members.keySet());
            List<String> names = indexes.stream().map(index -> describe(components.get(index).component())).toList();
            groups.add(new OneofGroup(oneof, indexes, names, List.copyOf(members.values())));
        });
        return groups;
    }

    /**
     * Returns where two paths first part: the index of their first different fields, when those are different
     * members of one {@code oneof} in the same message (whatever follows them); otherwise {@code -1}.
     */
    private static int splitInOneof(List<FieldDescriptor> first, List<FieldDescriptor> second) {
        int shorter = Math.min(first.size(), second.size());
        for (int index = 0; index < shorter; index++) {
            FieldDescriptor one = first.get(index);
            FieldDescriptor other = second.get(index);
            if (!one.equals(other)) {
                OneofDescriptor oneof = one.getRealContainingOneof();
                return oneof != null && oneof.equals(other.getRealContainingOneof()) ? index : -1;
            }
        }
        return -1;
    }

    /** Resolves a dotted path of proto field names; every segment but the last must be a singular message. */
    private static @Nullable List<FieldDescriptor> resolve(Descriptor message, String path) {
        List<FieldDescriptor> fields = new ArrayList<>();
        @Nullable
        Descriptor current = message;
        String[] segments = path.split("\\.", -1);
        for (int i = 0; i < segments.length; i++) {
            if (current == null) {
                return null;
            }
            FieldDescriptor field = current.findFieldByName(segments[i]);
            if (field == null) {
                return null;
            }
            fields.add(field);
            boolean nested = field.getJavaType() == FieldDescriptor.JavaType.MESSAGE && !field.isRepeated();
            current = nested ? field.getMessageType() : null;
        }
        return fields;
    }

    private static @Nullable FieldDescriptor byJsonName(Descriptor message, String name) {
        for (FieldDescriptor field : message.getFields()) {
            if (field.getJsonName().equals(name)) {
                return field;
            }
        }
        return null;
    }

    /**
     * The component paths of the mapped fields (dotted through nested records) and their proto paths. The elements of
     * a repeated field and the values of a map field are listed with {@code []} for the index or key in the component
     * path; in the proto path an element is {@value #LIST_ELEMENT}, where its index goes
     * ({@code authors[].countryCode} → {@code authors[].country_code}), and a map value is {@value #MAP_VALUE}, where
     * nothing goes, because the key is the caller's data ({@code authorsByRank[].countryCode} →
     * {@code authors_by_rank{}.country_code}). A record that contains itself is listed once.
     */
    private static Map<String, String> fieldPaths(RecordConverter root) {
        Map<String, String> paths = new LinkedHashMap<>();
        collect(root, "", "", paths, new HashSet<>());
        // Immutable once, so each call shares it (GrpcCallState.fieldPaths keeps a JDK immutable map as it is).
        return Map.copyOf(paths);
    }

    private static void collect(RecordConverter record, String componentPrefix, String protoPrefix,
            Map<String, String> paths, Set<RecordConverter> enclosing) {
        if (!enclosing.add(record)) {
            return;
        }
        for (ComponentMapping component : record.components()) {
            PageFields page = component.page();
            if (page != null) {
                paths.putAll(page.fieldPaths(componentPrefix + component.component().name() + "."));
                continue;
            }
            String protoPath = component.protoPath();
            if (protoPath == null) {
                continue;
            }
            String componentPath = componentPrefix + component.component().name();
            String fullProtoPath = protoPrefix + protoPath;
            paths.put(componentPath, fullProtoPath);
            collectNested(component.converter(), componentPath, fullProtoPath, paths, enclosing);
        }
        enclosing.remove(record);
    }

    private static void collectNested(@Nullable FieldConverter converter, String componentPath, String protoPath,
            Map<String, String> paths, Set<RecordConverter> enclosing) {
        if (converter instanceof RecordConverter nested) {
            collect(nested, componentPath + ".", protoPath + ".", paths, enclosing);
            return;
        }
        if (converter == null) {
            return;
        }
        FieldConverter elements = converter.elements();
        if (elements != null) {
            String element = protoPath + (converter.keyed() ? MAP_VALUE : LIST_ELEMENT);
            paths.put(componentPath + "[]", element);
            collectNested(elements, componentPath + "[]", element, paths, enclosing);
        }
    }

    private static String display(ResolvableType type) {
        Class<?> raw = type.resolve(Object.class);
        if (!type.hasGenerics()) {
            return raw.getSimpleName();
        }
        List<String> arguments = new ArrayList<>();
        for (ResolvableType argument : type.getGenerics()) {
            arguments.add(display(argument));
        }
        return raw.getSimpleName() + "<" + String.join(", ", arguments) + ">";
    }

    private static String describe(ResolvedComponent component) {
        return component.recordType().getSimpleName() + "." + component.name();
    }

    private static String key(ResolvedComponent component) {
        return component.recordType().getName() + "#" + component.name();
    }
}
