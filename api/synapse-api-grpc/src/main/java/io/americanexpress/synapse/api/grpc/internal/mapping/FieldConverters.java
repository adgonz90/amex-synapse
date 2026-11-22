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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.util.ReflectionUtils;

import io.americanexpress.synapse.api.grpc.internal.binding.ContextSource;
import io.americanexpress.synapse.api.grpc.internal.mapping.FieldConverter.Reading;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.internal.conditional.EntityTags;
import io.americanexpress.synapse.core.internal.conditional.VersionTokens;

/**
 * The field converters: scalars, enums, the well-known {@code Timestamp}, {@code Duration} and wrapper types,
 * repeated fields, maps, and records over messages.
 */
final class FieldConverters {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.grpc");

    private static final TypeDescriptor STRING = TypeDescriptor.valueOf(String.class);

    /** The violation of an {@code etag} off the entity tag grammar, as REST names a malformed {@code If-Match}. */
    private static final String NOT_A_TAG = "must be an entity tag";

    /** The violation of an {@code etag} with more than one tag, as REST names an {@code If-Match} list. */
    private static final String NOT_A_SINGLE_TAG = "must be a single entity tag";

    private static final int NANOS_PER_SECOND = 1_000_000_000;

    private static final long MAX_UINT32 = 0xFFFF_FFFFL;

    /** {@code 0001-01-01T00:00:00Z}, the earliest {@code google.protobuf.Timestamp}. */
    private static final long MIN_TIMESTAMP_SECONDS = -62_135_596_800L;

    /** {@code 9999-12-31T23:59:59Z}, the latest {@code google.protobuf.Timestamp} second. */
    private static final long MAX_TIMESTAMP_SECONDS = 253_402_300_799L;

    /**
     * A value whose protobuf and Java forms are the same: {@code String}, {@code bool}, {@code float}, {@code double}.
     */
    static final FieldConverter IDENTITY = new Simple(value -> value, value -> value);

    /** {@code bytes} ↔ {@code byte[]}. */
    static final FieldConverter BYTES = new Simple(value -> ((ByteString) value).toByteArray(),
            value -> ByteString.copyFrom((byte[]) value));

    private FieldConverters() {
    }

    /**
     * Where a converter writes, for the messages of the failures it raises when an output can't be written.
     *
     * @param subject the component ({@code Record.component}) or the output
     * @param field the field, {@code <message full name>.<path>}
     */
    record Site(String subject, String field) {

        IllegalStateException outOfRange(FieldDescriptor descriptor) {
            return outOfRange(MessageMapping.scalarName(descriptor));
        }

        IllegalStateException outOfRange(String type) {
            return new IllegalStateException(subject + " is out of range for " + field + " (" + type + ")");
        }

        IllegalStateException nullElement() {
            return new IllegalStateException(subject + " holds a null element for " + field);
        }
    }

    /**
     * Returns the converter of a 32-bit integer field ({@code int32}, {@code sint32}, {@code sfixed32}, {@code uint32}
     * or {@code fixed32}) for an {@code Integer} or a {@code Long} component. Unsigned values outside the
     * component's range can't be read, and values outside the field's range can't be written.
     */
    static @Nullable FieldConverter int32(FieldDescriptor field, Class<?> boxed, Site site) {
        boolean unsigned = field.getType() == FieldDescriptor.Type.UINT32
                || field.getType() == FieldDescriptor.Type.FIXED32;
        if (boxed == Integer.class) {
            return new Simple(value -> {
                if (unsigned && (Integer) value < 0) {
                    throw new IllegalArgumentException("Unsigned value out of the int range");
                }
                return value;
            }, value -> {
                if (unsigned && (Integer) value < 0) {
                    throw site.outOfRange(field);
                }
                return value;
            });
        }
        if (boxed == Long.class) {
            return new Simple(
                    value -> unsigned ? Integer.toUnsignedLong((Integer) value) : (long) (Integer) value,
                    value -> {
                        long number = (Long) value;
                        boolean inRange = unsigned
                                ? number >= 0 && number <= MAX_UINT32
                                : number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE;
                        if (!inRange) {
                            throw site.outOfRange(field);
                        }
                        return (int) number;
                    });
        }
        return null;
    }

    /** Returns the converter of a 64-bit integer field for a {@code Long} component. */
    static FieldConverter int64(FieldDescriptor field, Site site) {
        boolean unsigned = field.getType() == FieldDescriptor.Type.UINT64
                || field.getType() == FieldDescriptor.Type.FIXED64;
        return new Simple(value -> {
            if (unsigned && (Long) value < 0) {
                throw new IllegalArgumentException("Unsigned value out of the long range");
            }
            return value;
        }, value -> {
            if (unsigned && (Long) value < 0) {
                throw site.outOfRange(field);
            }
            return value;
        });
    }

    /**
     * Returns the converter of a {@code string} field for a type the conversion service converts both ways. Each
     * value is converted once, both ways, by the same service, so {@code java.time} values are read and written in
     * ISO only and never in a locale's style.
     */
    static FieldConverter converted(ConversionService conversions, TypeDescriptor type) {
        return new Simple(value -> conversions.convert(value, STRING, type),
                value -> conversions.convert(value, TypeDescriptor.forObject(value), STRING));
    }

    /** Returns the converter of an enum field for a Java enum whose every constant has a value. */
    static FieldConverter enumeration(Class<? extends Enum<?>> type, EnumDescriptor descriptor) {
        Map<Integer, @Nullable Enum<?>> byNumber = new HashMap<>();
        for (EnumValueDescriptor value : descriptor.getValues()) {
            try {
                byNumber.putIfAbsent(value.getNumber(), EnumNames.toJava(value, type));
            } catch (IllegalArgumentException e) {
                // A value without a constant: reading it is a violation.
            }
        }
        Map<Enum<?>, EnumValueDescriptor> byConstant = new HashMap<>();
        for (Enum<?> constant : type.getEnumConstants()) {
            byConstant.put(constant, EnumNames.toProto(constant, descriptor));
        }
        return new FieldConverter() {

            @Override
            public @Nullable Object read(Object value, Reading reading, String path) {
                int number = ((EnumValueDescriptor) value).getNumber();
                if (!byNumber.containsKey(number)) {
                    throw new IllegalArgumentException("No constant for the enum number");
                }
                return byNumber.get(number);
            }

            @Override
            public Object write(Object value) {
                return Objects.requireNonNull(byConstant.get(value));
            }
        };
    }

    /**
     * {@code google.protobuf.Timestamp} ↔ {@code Instant}, within the type's range, 0001-01-01T00:00:00Z to
     * 9999-12-31T23:59:59.999999999Z: a value outside it is not valid when read and can't be written.
     */
    static FieldConverter timestamp(Descriptor descriptor, Site site) {
        FieldDescriptor seconds = descriptor.findFieldByName("seconds");
        FieldDescriptor nanos = descriptor.findFieldByName("nanos");
        return new Simple(value -> {
            Message message = (Message) value;
            long second = (Long) message.getField(seconds);
            int nano = (Integer) message.getField(nanos);
            if (nano < 0 || nano >= NANOS_PER_SECOND || second < MIN_TIMESTAMP_SECONDS
                    || second > MAX_TIMESTAMP_SECONDS) {
                throw new IllegalArgumentException("Timestamp out of range");
            }
            return Instant.ofEpochSecond(second, nano);
        }, value -> {
            Instant instant = (Instant) value;
            if (instant.getEpochSecond() < MIN_TIMESTAMP_SECONDS || instant.getEpochSecond() > MAX_TIMESTAMP_SECONDS) {
                throw site.outOfRange(descriptor.getFullName());
            }
            return DynamicMessage
                    .newBuilder(descriptor)
                    .setField(seconds, instant.getEpochSecond())
                    .setField(nanos, instant.getNano())
                    .build();
        });
    }

    /**
     * {@code google.protobuf.Duration} ↔ {@code java.time.Duration}. A negative duration is written with both
     * fields negative, as the protobuf type requires.
     */
    static FieldConverter duration(Descriptor descriptor) {
        FieldDescriptor seconds = descriptor.findFieldByName("seconds");
        FieldDescriptor nanos = descriptor.findFieldByName("nanos");
        return new Simple(value -> {
            Message message = (Message) value;
            long second = (Long) message.getField(seconds);
            int nano = (Integer) message.getField(nanos);
            if (nano <= -NANOS_PER_SECOND || nano >= NANOS_PER_SECOND || (second > 0 && nano < 0)
                    || (second < 0 && nano > 0)) {
                throw new IllegalArgumentException("Duration nanos out of range");
            }
            return java.time.Duration.ofSeconds(second, nano);
        }, value -> {
            java.time.Duration duration = (java.time.Duration) value;
            long second = duration.getSeconds();
            int nano = duration.getNano();
            if (second < 0 && nano > 0) {
                second++;
                nano -= NANOS_PER_SECOND;
            }
            return DynamicMessage.newBuilder(descriptor).setField(seconds, second).setField(nanos, nano).build();
        });
    }

    /** A wrapper type ({@code google.protobuf.Int32Value}, …): its {@code value} field converted by {@code inner}. */
    static FieldConverter wrapper(Descriptor descriptor, FieldDescriptor valueField, FieldConverter inner) {
        return new FieldConverter() {

            @Override
            public @Nullable Object read(Object value, Reading reading, String path) {
                return inner.read(((Message) value).getField(valueField), reading, path);
            }

            @Override
            public Object write(Object value) {
                DynamicMessage.Builder wrapper = DynamicMessage.newBuilder(descriptor);
                Object written = inner.write(value);
                if (written != null) {
                    wrapper.setField(valueField, written);
                }
                return wrapper.build();
            }
        };
    }

    /**
     * {@code repeated T} ↔ {@code List<T>}; a violation in an element carries its index ({@code tags[1]}). The path of
     * a scalar, enum or well-known type element is built only when the element fails; a message element's path is
     * built up front, because its fields' paths start with it.
     */
    static FieldConverter list(FieldConverter elements, Site site) {
        boolean messages = elements instanceof RecordConverter;
        return new FieldConverter() {

            @Override
            public Object read(Object value, Reading reading, String path) {
                List<?> values = (List<?>) value;
                List<Object> read = new ArrayList<>(values.size());
                boolean failed = false;
                for (int i = 0; i < values.size(); i++) {
                    Object element = messages
                            ? reading.convert(elements, values.get(i), Reading.elementPath(path, i))
                            : reading.convertElement(elements, values.get(i), path, i);
                    if (FieldConverter.failed(element)) {
                        failed = true;
                    } else if (element == null) {
                        reading.missing(Reading.elementPath(path, i));
                        failed = true;
                    } else {
                        read.add(element);
                    }
                }
                return failed ? FAILED : Collections.unmodifiableList(read);
            }

            @Override
            public FieldConverter elements() {
                return elements;
            }

            @Override
            public Object write(Object value) {
                List<Object> written = new ArrayList<>();
                for (Object element : (List<?>) value) {
                    Object converted = element == null ? null : elements.write(element);
                    if (converted == null) {
                        throw site.nullElement();
                    }
                    written.add(converted);
                }
                return written;
            }
        };
    }

    /**
     * {@code map<K, V>} ↔ {@code Map<K, V>}, in the order of the entries. A violation in an entry carries the map
     * field's path only, because the key is the caller's data.
     */
    static FieldConverter map(Descriptor entry, FieldDescriptor keyField, FieldDescriptor valueField,
            FieldConverter keys, FieldConverter values, Site site) {
        return new FieldConverter() {

            @Override
            public Object read(Object value, Reading reading, String path) {
                Map<Object, Object> read = new LinkedHashMap<>();
                boolean failed = false;
                for (Object element : (List<?>) value) {
                    Message pair = (Message) element;
                    Object key = reading.convert(keys, pair.getField(keyField), path);
                    Object mapped = reading.convert(values, pair.getField(valueField), path);
                    if (FieldConverter.failed(key) || FieldConverter.failed(mapped)) {
                        failed = true;
                    } else if (key == null || mapped == null) {
                        reading.missing(path);
                        failed = true;
                    } else {
                        read.put(key, mapped);
                    }
                }
                return failed ? FAILED : Collections.unmodifiableMap(read);
            }

            @Override
            public FieldConverter elements() {
                return values;
            }

            @Override
            public boolean keyed() {
                return true;
            }

            @Override
            public Object write(Object value) {
                List<Object> written = new ArrayList<>();
                for (Map.Entry<?, ?> pair : ((Map<?, ?>) value).entrySet()) {
                    Object key = pair.getKey() == null ? null : keys.write(pair.getKey());
                    Object mapped = pair.getValue() == null ? null : values.write(pair.getValue());
                    if (key == null || mapped == null) {
                        throw site.nullElement();
                    }
                    written
                            .add(DynamicMessage
                                    .newBuilder(entry)
                                    .setField(keyField, key)
                                    .setField(valueField, mapped)
                                    .build());
                }
                return written;
            }
        };
    }

    /**
     * Returns the converter of a request's token field ({@code etag} or {@code if_none_match}) for a
     * {@code VersionCondition<V>} component. The value is an entity tag list with REST's grammar ({@link EntityTags}):
     * empty is no condition ({@code null}), {@code *} is {@code AnyVersion}, and one tag is {@code Version(v)}, its
     * opaque value read as a {@code V} by core's {@link VersionTokens} (the codec REST uses too, never a conversion
     * service), strong or weak. A value off the grammar, more than one tag, or a token that names no version of
     * {@code V} is a violation. Input only.
     *
     * @param version the version type
     * @param weakFails whether a weak tag fails the precondition once the input is valid: REST's rule for a write's
     *        {@code If-Match}, which compares strongly; a read's tag and an {@code If-None-Match} (compared weakly)
     *        bind the weak tag's value
     */
    static FieldConverter versionCondition(Class<?> version, boolean weakFails) {
        return new FieldConverter() {

            @Override
            public @Nullable Object read(Object value, Reading reading, String path) {
                String text = (String) value;
                if (text.isEmpty()) {
                    return null;
                }
                EntityTags.Parsed parsed;
                try {
                    parsed = EntityTags.parse(text);
                } catch (IllegalArgumentException e) {
                    reading.violation(path, NOT_A_TAG);
                    return FAILED;
                }
                if (!(parsed instanceof EntityTags.Parsed.Tags list)) {
                    return VersionCondition.any();
                }
                if (list.tags().size() > 1) {
                    reading.violation(path, NOT_A_SINGLE_TAG);
                    return FAILED;
                }
                EntityTags.Tag tag = list.tags().getFirst();
                // An IllegalArgumentException (a tag that names no version) is recorded by Reading.convert.
                Object parsedVersion = VersionTokens.parse(tag.opaque(), version);
                if (tag.weak() && weakFails) {
                    reading.weakTag();
                }
                return VersionCondition.of(parsedVersion);
            }

            @Override
            public Object write(Object value) {
                throw new IllegalStateException("A VersionCondition is input only");
            }
        };
    }

    /** A converter made of two functions, for values that are never absent once present. */
    private record Simple(Function<Object, @Nullable Object> reader,
            Function<Object, @Nullable Object> writer) implements FieldConverter {

        @Override
        public @Nullable Object read(Object value, Reading reading, String path) {
            return reader.apply(value);
        }

        @Override
        public @Nullable Object write(Object value) {
            return writer.apply(value);
        }
    }

    /**
     * How one record component is filled and written: from a field (its path from the record's message, dotted when
     * the field is in a nested message) or from the request context.
     *
     * @param component the component
     * @param accessor the component's accessor, accessible
     * @param fields the field path, empty for a context component
     * @param protoPath the field path in proto names, or {@code null} for a context component
     * @param converter the field's converter, or {@code null} for a context component
     * @param context the context source, or {@code null}
     * @param requiredAdvice for an input {@code VersionCondition} read from a token field, how to resubmit when a
     *        non-null one has no value (a missing precondition, not a violation); {@code null} for any other component
     * @param page for an input {@code PageQuery}, the request's page fields it reads; {@code null} for any other
     *        component
     */
    record ComponentMapping(ResolvedComponent component, Method accessor, List<FieldDescriptor> fields,
            @Nullable String protoPath, @Nullable FieldConverter converter, @Nullable ContextSource context,
            @Nullable String requiredAdvice, @Nullable PageFields page) {

        static ComponentMapping field(ResolvedComponent component, List<FieldDescriptor> fields, String protoPath,
                FieldConverter converter) {
            return new ComponentMapping(component, accessor(component), List.copyOf(fields), protoPath, converter,
                    null, null, null);
        }

        static ComponentMapping condition(ResolvedComponent component, FieldDescriptor token, FieldConverter converter,
                String requiredAdvice) {
            return new ComponentMapping(component, accessor(component), List.of(token), token.getName(), converter,
                    null, requiredAdvice, null);
        }

        static ComponentMapping context(ResolvedComponent component, ContextSource context) {
            return new ComponentMapping(component, accessor(component), List.of(), null, null, context, null, null);
        }

        static ComponentMapping page(ResolvedComponent component, PageFields page) {
            return new ComponentMapping(component, accessor(component), List.of(), null, null, null, null, page);
        }

        /** Whether the component is an input {@code VersionCondition} read from a token field. */
        boolean condition() {
            return requiredAdvice != null;
        }

        /** The path of the component's violations: the proto path, or the component name without a field. */
        String path(String base) {
            String own = protoPath == null ? component.name() : protoPath;
            return base.isEmpty() ? own : base + "." + own;
        }

        @Nullable
        Object read(Message message, Reading reading, String base) {
            if (context != null) {
                return context.value().apply(RequestContext.current());
            }
            PageFields pageFields = page;
            if (pageFields != null) {
                // The page fields are the request's own: a PageQuery is a component of the input record itself.
                return pageFields.read(message, reading);
            }
            FieldConverter fieldConverter = converter;
            if (fieldConverter == null) {
                return null;
            }
            Message holder = message;
            for (int i = 0; i < fields.size() - 1; i++) {
                holder = (Message) holder.getField(fields.get(i));
            }
            FieldDescriptor leaf = fields.getLast();
            if (leaf.hasPresence() && !holder.hasField(leaf)) {
                return null;
            }
            return reading.convert(fieldConverter, holder.getField(leaf), path(base));
        }

        /** Writes the component's value, and returns whether it set a field (a {@code null} value sets none). */
        boolean write(Object record, Target target) {
            FieldConverter fieldConverter = converter;
            if (fieldConverter == null) {
                return false;
            }
            Object value = ReflectionUtils.invokeMethod(accessor, record);
            if (value == null) {
                return false;
            }
            Object written = fieldConverter.write(value);
            if (written == null) {
                return false;
            }
            Target holder = target;
            for (int i = 0; i < fields.size() - 1; i++) {
                holder = holder.child(fields.get(i));
            }
            holder.builder.setField(fields.getLast(), written);
            return true;
        }

        private static Method accessor(ResolvedComponent component) {
            Method accessor = component.component().getAccessor();
            ReflectionUtils.makeAccessible(accessor);
            return accessor;
        }
    }

    /**
     * The {@code @Nullable} output components of a record that write into the members of one {@code oneof}: they may
     * set fields of one member only, because setting a second member would silently clear the first.
     *
     * @param oneof the oneof, {@code <message full name>.<path>.<oneof>}
     * @param components the indexes of the components in the record's mapping, in component order
     * @param names the components, {@code Record.component}, in the same order
     * @param members the member each component writes (the member itself, or a field inside it), in the same order
     */
    record OneofGroup(String oneof, List<Integer> components, List<String> names, List<String> members) {

        /**
         * Fails when the group's components set fields of more than one member.
         *
         * @param written whether each component of the record set its field, by index
         * @throws IllegalStateException naming the oneof and the components that have a value, an operation bug that
         *         the call answers with {@code INTERNAL}; the message never repeats a value
         */
        void check(boolean[] written) {
            List<String> set = new ArrayList<>();
            Set<String> setMembers = new HashSet<>();
            for (int i = 0; i < components.size(); i++) {
                if (written[components.get(i)]) {
                    set.add(names.get(i));
                    setMembers.add(members.get(i));
                }
            }
            if (setMembers.size() > 1) {
                String listed = String.join(", ", set.subList(0, set.size() - 1)) + " and " + set.getLast();
                throw new IllegalStateException(listed + (set.size() == 2 ? " both" : " all")
                        + " have a value, but they write members of oneof " + oneof + ", which holds one");
            }
        }
    }

    /** A message being written, with the nested messages that flattened components write into. */
    static final class Target {

        private final DynamicMessage.Builder builder;

        private final Map<FieldDescriptor, Target> children = new LinkedHashMap<>();

        Target(Descriptor message) {
            this.builder = DynamicMessage.newBuilder(message);
        }

        Target child(FieldDescriptor field) {
            return children.computeIfAbsent(field, nested -> new Target(nested.getMessageType()));
        }

        DynamicMessage build() {
            children.forEach((field, child) -> builder.setField(field, child.build()));
            return builder.build();
        }
    }

    /**
     * A record over a message. Its components are set after it is created, so that a record that contains itself
     * (through a message that contains itself) maps to one converter.
     */
    static final class RecordConverter implements FieldConverter {

        private final Class<?> type;

        private final Descriptor message;

        private final Constructor<?> constructor;

        private List<ComponentMapping> components = List.of();

        private List<OneofGroup> oneofs = List.of();

        RecordConverter(Class<?> type, Descriptor message) {
            this.type = type;
            this.message = message;
            this.constructor = canonicalConstructor(type);
        }

        void components(List<ComponentMapping> mapped) {
            this.components = List.copyOf(mapped);
        }

        List<ComponentMapping> components() {
            return components;
        }

        /** Sets the groups of output components that share a {@code oneof}, checked on every write. */
        void oneofs(List<OneofGroup> groups) {
            this.oneofs = List.copyOf(groups);
        }

        @Override
        public @Nullable Object read(Object value, Reading reading, String path) {
            return readRecord((Message) value, reading, path);
        }

        /**
         * Reads the record. Every component problem is recorded before the record is built; a constructor that
         * rejects the values is {@code {path, "is not a valid value"}} for a nested record and
         * {@code VALIDATION_FAILED} without violations for the request's own record, as on the REST edge. A
         * {@link SynapseException} the constructor throws reaches the caller as it is.
         *
         * @param value the message
         * @param reading the violations so far
         * @param path the record's proto path, empty for the request
         * @return the record or {@link #FAILED}
         */
        @Nullable
        Object readRecord(Message value, Reading reading, String path) {
            @Nullable
            Object[] args = new Object[components.size()];
            boolean failed = false;
            for (int i = 0; i < args.length; i++) {
                ComponentMapping component = components.get(i);
                Object read = component.read(value, reading, path);
                if (FieldConverter.failed(read)) {
                    failed = true;
                } else if (read == null && !component.component().nullable()) {
                    String advice = component.requiredAdvice();
                    if (advice != null) {
                        reading.preconditionMissing(component.path(path), advice);
                    } else {
                        reading.missing(component.path(path));
                    }
                    failed = true;
                } else {
                    args[i] = read;
                }
            }
            if (failed) {
                return FAILED;
            }
            try {
                return constructor.newInstance(args);
            } catch (InvocationTargetException e) {
                Throwable thrown = e.getTargetException();
                if (thrown instanceof Error error) {
                    throw error;
                }
                if (thrown instanceof SynapseException synapse) {
                    throw synapse;
                }
                if (path.isEmpty()) {
                    LOG.debug("{} rejected the request's values", type.getName(), thrown);
                    throw new SynapseException(SynapseErrors.VALIDATION_FAILED, thrown);
                }
                LOG.debug("{} rejected the values of {}", type.getName(), path, thrown);
                reading.invalid(path);
                return FAILED;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot create " + type.getName(), e);
            }
        }

        @Override
        public Object write(Object value) {
            return writeRecord(value);
        }

        DynamicMessage writeRecord(Object record) {
            Target target = new Target(message);
            boolean[] written = new boolean[components.size()];
            for (int i = 0; i < written.length; i++) {
                written[i] = components.get(i).write(record, target);
            }
            for (OneofGroup oneof : oneofs) {
                oneof.check(written);
            }
            return target.build();
        }

        private static Constructor<?> canonicalConstructor(Class<?> recordType) {
            Class<?>[] types = Arrays
                    .stream(Objects.requireNonNull(recordType.getRecordComponents()))
                    .map(RecordComponent::getType)
                    .toArray(Class<?>[]::new);
            try {
                Constructor<?> constructor = recordType.getDeclaredConstructor(types);
                ReflectionUtils.makeAccessible(constructor);
                return constructor;
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("No canonical constructor on " + recordType.getName(), e);
            }
        }
    }

}
