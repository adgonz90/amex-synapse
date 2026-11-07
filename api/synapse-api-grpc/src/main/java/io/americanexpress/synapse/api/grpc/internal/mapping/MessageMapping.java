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
import io.americanexpress.synapse.api.grpc.internal.mapping.FieldConverters.RecordConverter;
import io.americanexpress.synapse.api.grpc.internal.mapping.FieldConverters.Site;
import io.americanexpress.synapse.core.internal.binding.ComponentRefs;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;

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
 * {@code oneof} member), or an enum field whose zero value is unspecified.</li>
 * <li>A {@code oneof} member is mapped only by an explicit {@code .map}.</li>
 * <li>Input: request fields without a component are ignored; every component needs a field or a {@code .context}.
 * A {@code Void} input reads nothing and takes only {@code google.protobuf.Empty} or another message without
 * fields.</li>
 * <li>Output: every component needs a field, and no two components write the same field (or one inside the other)
 * or into two members of one {@code oneof}; response fields without a component stay unset. {@code Void} writes the
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

    private static final String UNMAPPABLE_ACTION = "Change the component type, or map another field with .map(...).";

    private static final Set<String> WRAPPERS = Set
            .of("google.protobuf.DoubleValue", "google.protobuf.FloatValue",
                    "google.protobuf.Int64Value", "google.protobuf.UInt64Value", "google.protobuf.Int32Value",
                    "google.protobuf.UInt32Value", "google.protobuf.BoolValue", "google.protobuf.StringValue",
                    "google.protobuf.BytesValue");

    private final boolean reading;

    private final Map<String, FieldOverride> overrides = new HashMap<>();

    private final Map<String, ContextSource> contexts = new HashMap<>();

    /** The records mapped so far, by type and message, so that a record that contains itself maps once. */
    private final Map<String, RecordConverter> records = new HashMap<>();

    private MessageMapping(boolean reading, List<FieldOverride> overrides, List<ContextSource> contexts) {
        this.reading = reading;
        for (FieldOverride override : overrides) {
            this.overrides.put(key(override.component()), override);
        }
        for (ContextSource context : contexts) {
            this.contexts.put(key(context.component()), context);
        }
    }

    /**
     * Creates the reader of a method's request.
     *
     * @param message the request message
     * @param recordType the operation's input type: a record, or {@code Void}
     * @param overrides the method's {@code .map} declarations (those of output records are ignored)
     * @param contexts the method's {@code .context} declarations
     * @return the reader
     * @throws MappingException when the request can't be mapped to the input
     */
    public static RecordReader reader(Descriptor message, Class<?> recordType, List<FieldOverride> overrides,
            List<ContextSource> contexts) {
        if (recordType == Void.class) {
            if (!message.getFields().isEmpty()) {
                throw new MappingException(
                        "the input Void can't be mapped to " + message.getFullName() + ", which has fields",
                        "Use google.protobuf.Empty as the request, or declare a record input for its fields.");
            }
            return new RecordReader(null, Map.of());
        }
        if (!recordType.isRecord()) {
            throw new MappingException(
                    "the input " + recordType.getSimpleName() + " can't be mapped to " + message.getFullName(),
                    "Declare the operation's input as a record, or as Void if it takes none.");
        }
        RecordConverter root = new MessageMapping(true, overrides, contexts)
                .record(ResolvableType.forClass(recordType), message);
        return new RecordReader(root, fieldPaths(root));
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
        ResolvableType type = outputType;
        if (type.resolve() == Optional.class) {
            type = type.getGeneric(0);
        }
        Class<?> raw = type.resolve(Object.class);
        if (raw == Void.class || raw == void.class) {
            return RecordWriter.empty(message);
        }
        MessageMapping mapping = new MessageMapping(false, overrides, List.of());
        if (raw == List.class) {
            return mapping.listWriter(message, type);
        }
        if (!raw.isRecord()) {
            throw new MappingException(
                    "the output " + display(type) + " can't be mapped to " + message.getFullName(),
                    "Return a record, a List, an Optional or Void from the operation.");
        }
        RecordConverter root = mapping.record(type, message);
        return RecordWriter.record(message, root, fieldPaths(root));
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
        if (!reading) {
            checkOverlaps(message, components);
        }
        converter.components(components);
        return converter;
    }

    private ComponentMapping component(ResolvableType owner, Descriptor message, ResolvedComponent component) {
        String name = describe(component);
        String messageName = message.getFullName();
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
        return ComponentMapping.field(component, path, protoPath, converter);
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

    private static boolean hasPresence(FieldDescriptor field) {
        return field.hasPresence() || (field.getJavaType() == FieldDescriptor.JavaType.ENUM && !field.isRepeated()
                && EnumNames.hasUnspecifiedZero(field.getEnumType()));
    }

    /**
     * Fails when two output components write the same field, one writes a field inside the other's message, or both
     * write into different members of one {@code oneof} (the member itself or a field inside it), where the second
     * would silently clear the first.
     */
    private static void checkOverlaps(Descriptor message, List<ComponentMapping> components) {
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
                    throw new MappingException(describe(components.get(i).component()) + " and "
                            + describe(components.get(j).component()) + " both write oneof " + message.getFullName()
                            + "." + parent + oneof.getName(), "Map at most one component to the members of a oneof.");
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
