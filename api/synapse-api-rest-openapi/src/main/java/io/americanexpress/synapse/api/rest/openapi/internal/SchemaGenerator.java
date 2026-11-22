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
package io.americanexpress.synapse.api.rest.openapi.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import jakarta.validation.Validator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.AnnotationIntrospector;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.AnnotatedClass;
import tools.jackson.databind.introspect.ClassIntrospector;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.internal.binding.JsonNames;
import io.americanexpress.synapse.api.rest.internal.paging.PageBody;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * Describes Java types as OpenAPI 3.1 schemas (JSON Schema 2020-12) that match what the application's
 * {@code JsonMapper} writes and reads, constrained as its {@code Validator} checks them.
 *
 * <ul>
 * <li>Simple types map to a JSON type and format. A {@code char} is a one-character string, and {@code Year} is an
 * integer; the other {@code java.time} types are strings.</li>
 * <li>Arrays and collections map to {@code array} ({@code Set} adds {@code uniqueItems}); a {@code Map} maps to
 * {@code object} with {@code additionalProperties}, whatever its key type, since Jackson writes every key as a string.
 * {@code Optional<T>} is {@code T}, nullable; {@code OptionalInt}, {@code OptionalLong} and {@code OptionalDouble} are
 * nullable numbers.</li>
 * <li>A record or an enum is a component, referenced with {@code $ref}. A record's properties are its components that
 * the mapper writes (outputs) or reads (inputs), in component order. {@code required} lists the non-nullable ones, and
 * in outputs only those the mapper always writes (its inclusion is {@code ALWAYS} or {@code NON_NULL}). When a record's
 * input differs from its output (its own JSON names, an output the mapper may leave out, its own serialization code,
 * or a record it holds), its input is a second component, {@code <name>Input}. An enum lists its constants as the
 * mapper writes them: strings ({@code DayOfWeek}) or integers ({@code Month}, a numeric {@code @JsonValue}). A
 * {@linkplain #parameter parameter} of an enum type is not the component: it lists the constant names, which the
 * {@code ConversionService} reads.</li>
 * <li>Components are named by their simple name, a generic one by its arguments ({@code EnvelopeOfBook},
 * {@code PairOfBookAndAuthor}). When two components claim one name, both take their qualified name, and so does a
 * component that claims a name Synapse's own components use ({@code SynapseProblem}, {@code SynapseViolation}). A name
 * can change while more types are described; the {@code $ref}s already returned change with it.</li>
 * <li>Anything else is {@code {}}, and its type is reported by {@link #undescribed()}: an unknown type, and a record
 * that its own code writes ({@code @JsonSerialize(using = ...)}, {@code @JsonValue}) or reads
 * ({@code @JsonDeserialize(using = ...)}, a delegating creator), on that side. A type Jackson fails on is {@code {}}
 * too, and {@link #reasons()} keeps the failure.</li>
 * </ul>
 *
 * <p>
 * Each distinct type is walked once, breadth first, so recursive and shared types cost one walk. {@link Limits} bound
 * the walk by the number of types and by their size, since a generic record can grow its types without repeating one:
 * <ul>
 * <li>a type whose component name would be longer than {@code nameLength} is {@code {}} and reported;</li>
 * <li>a component that needs more than {@code schemasPerType} schemas is {@code {}} and reported, and the components
 * first reached while walking it are dropped;</li>
 * <li>after {@code types} distinct types, or once {@code schemas} schemas exist, the first type over the limit is
 * reported, and it and every later type are {@code {}}.</li>
 * </ul>
 * Each of these is reported with its limit in {@link #reasons()}.
 *
 * <p>
 * Not thread-safe: one generator documents one application's routes.
 */
public final class SchemaGenerator {

    /** The most distinct types one generator describes by default. */
    static final int TYPE_BUDGET = 10_000;

    /** The component of a problem body, which {@code RouteDocumenter} adds. */
    static final String PROBLEM = "SynapseProblem";

    /** The component of a problem's violation, which {@code RouteDocumenter} adds. */
    static final String VIOLATION = "SynapseViolation";

    /** The names no described type takes by its simple name: such a type takes its qualified name instead. */
    static final Set<String> RESERVED = Set.of(PROBLEM, VIOLATION);

    private static final String COMPONENTS = "#/components/schemas/";

    private static final String PAGE = "Page";

    private static final TypeKey OBJECT = new TypeKey(Object.class, List.of());

    private static final Map<Class<?>, Simple> SIMPLE = simpleTypes();

    private final JsonMapper mapper;

    private final Validator validator;

    private final Limits limits;

    /** The components, in the order they were first referenced. */
    private final Map<NodeKey, Node> nodes = new LinkedHashMap<>();

    /** The distinct types counted against the type limit. */
    private final Set<Object> counted = new HashSet<>();

    /** The components referenced but not walked yet. */
    private final Deque<Node> pending = new ArrayDeque<>();

    private final Set<String> undescribed = new TreeSet<>();

    private final Map<String, Reason> reasons = new TreeMap<>();

    private final Map<Class<?>, Map<String, String>> outputNames = new HashMap<>();

    private final Map<Class<?>, Map<String, String>> inputNames = new HashMap<>();

    private final Map<Class<?>, Set<String>> omittable = new HashMap<>();

    private final Map<Class<?>, Boolean> customOutput = new HashMap<>();

    private final Map<Class<?>, Boolean> customInput = new HashMap<>();

    private final Map<Class<?>, Boolean> readsByProperty = new HashMap<>();

    /** The component being walked, which records the input components it references. */
    private @Nullable Node building;

    /** The components first referenced, and the types first counted, while walking {@link #building}. */
    private final List<Node> createdInBuild = new ArrayList<>();

    private final List<Object> countedInBuild = new ArrayList<>();

    /** The schemas created in all, and while walking {@link #building}. */
    private int schemas;

    private int buildSchemas;

    /** How many times a component has been walked. */
    private int walks;

    private boolean limitReached;

    /** How many components there were when they were last named. */
    private int named = -1;

    /**
     * Creates a generator.
     *
     * @param mapper the application's mapper, which names the properties and the enum values
     * @param validator the application's validator, whose constraint metadata becomes keywords
     */
    public SchemaGenerator(JsonMapper mapper, Validator validator) {
        this(mapper, validator, Limits.DEFAULT);
    }

    /**
     * Creates a generator with limits.
     *
     * @param mapper the application's mapper
     * @param validator the application's validator
     * @param limits the limits
     */
    SchemaGenerator(JsonMapper mapper, Validator validator, Limits limits) {
        this.mapper = mapper;
        this.validator = validator;
        this.limits = limits;
    }

    /** How many schemas the generator has created. */
    int schemaCount() {
        return schemas;
    }

    /** How many components the generator has walked. */
    int walkCount() {
        return walks;
    }

    /**
     * What one generator describes at most.
     *
     * @param types distinct types
     * @param nameLength characters of a component's simple name
     * @param schemasPerType schemas in one component
     * @param schemas schemas in all
     */
    record Limits(int types, int nameLength, int schemasPerType, int schemas) {

        /**
         * The defaults. A schema takes about 0.6 KB, so the total keeps the documentation near 110 MB at most; the
         * branching {@code Grow<T>} of the tests reaches the type limit first, with about 164,000 schemas.
         */
        static final Limits DEFAULT = new Limits(TYPE_BUDGET, 512, 5_000, 200_000);
    }

    /**
     * Describes a type as the mapper writes it.
     *
     * @param type the type
     * @return its schema: a {@code $ref} for a record or an enum
     */
    public Schema<?> output(ResolvableType type) {
        return finish(schema(key(type), View.OUTPUT));
    }

    /**
     * Describes a type as the mapper reads it.
     *
     * @param type the type
     * @return its schema: a {@code $ref} for a record or an enum
     */
    public Schema<?> input(ResolvableType type) {
        return finish(schema(key(type), View.INPUT));
    }

    /**
     * Describes some of a record's components, as the mapper writes them, as an inline object.
     *
     * @param record the record type
     * @param components tells, by component name, which components to describe
     * @return the object schema
     */
    public Schema<?> outputObject(Class<?> record, Predicate<String> components) {
        return finish(inlineObject(record, View.OUTPUT, components));
    }

    /**
     * Describes some of a record's components, as the mapper reads them, as an inline object.
     *
     * @param record the record type
     * @param components tells, by component name, which components to describe
     * @return the object schema
     */
    public Schema<?> inputObject(Class<?> record, Predicate<String> components) {
        return finish(inlineObject(record, View.INPUT, components));
    }

    /**
     * Describes the body of a paged response: the {@code <Item>Page} component, whose members are named as the mapper
     * writes {@link PageBody}. Members that are left out when absent are optional, not nullable.
     *
     * @param itemType the page's item type
     * @return a {@code $ref} to the page component
     */
    public Schema<?> page(ResolvableType itemType) {
        return finish(reference(key(itemType), Kind.PAGE, View.OUTPUT));
    }

    /**
     * Describes a value the {@code ConversionService} converts from or to text, bound from or written to a record
     * component: a path variable, a query parameter, a cookie, or a header. An enum is an inline {@code string} enum of
     * its constant names ({@code Enum.name()}, as {@code Enum.valueOf} reads them), not its component with the
     * mapper's names; so is the enum of an {@code Optional}, an array or a collection. Other types are described as
     * the mapper reads them. The component's constraints apply. A parameter states whether it is required itself, so
     * the schema never adds {@code null}.
     *
     * @param component the component
     * @return its schema
     */
    public Schema<?> parameter(ResolvedComponent component) {
        TypeKey type = componentType(component.component(), classKey(component.recordType()));
        Schema<?> schema = converted(type.raw() == Optional.class ? argument(type, Optional.class, 0) : type);
        constrain(component.recordType(), component.name(), schema);
        return finish(schema);
    }

    /** A value the {@code ConversionService} converts: an enum by its constant names, each element of a collection. */
    private Schema<?> converted(TypeKey type) {
        Class<?> raw = type.raw();
        if (raw.isEnum()) {
            return constantNames(raw);
        }
        if (raw.isArray() && raw.getComponentType().isEnum()) {
            return array(constantNames(raw.getComponentType()));
        }
        if (Collection.class.isAssignableFrom(raw)) {
            Class<?> element = argument(type, Collection.class, 0).raw();
            if (element.isEnum()) {
                Schema<?> array = array(constantNames(element));
                return Set.class.isAssignableFrom(raw) ? array.uniqueItems(true) : array;
            }
        }
        return schema(type, View.INPUT);
    }

    private Schema<?> constantNames(Class<?> enumType) {
        List<Object> names = new ArrayList<>();
        for (Object constant : Objects.requireNonNull(enumType.getEnumConstants())) {
            names.add(((Enum<?>) constant).name());
        }
        JsonSchema schema = (JsonSchema) typed("string", null);
        schema._enum(names);
        return schema;
    }

    /**
     * Returns the components described so far.
     *
     * @return the component schemas by name, sorted by name
     */
    public Map<String, Schema<?>> components() {
        drain();
        assignNames();
        Map<String, Schema<?>> components = new TreeMap<>();
        for (Node node : nodes.values()) {
            if (node.representative == node) {
                components.put(node.name, node.body);
            }
        }
        return Collections.unmodifiableMap(components);
    }

    /**
     * Returns the types documented as {@code {}}: types the generator has no schema for, records written or read by
     * their own code, types over the name or per-component limit, and the first type over the type or total limit.
     *
     * @return the type names, sorted
     */
    public Set<String> undescribed() {
        return Collections.unmodifiableSet(undescribed);
    }

    /**
     * Returns why some of the {@linkplain #undescribed() undescribed} types are {@code {}}: a limit, or a Jackson
     * lookup that failed. A type the generator has no schema for, or that its own code writes or reads, has no reason.
     *
     * @return the reasons by type name, sorted
     */
    public Map<String, Reason> reasons() {
        return Collections.unmodifiableMap(reasons);
    }

    /**
     * Why a type is documented as {@code {}}.
     *
     * @param text the reason, as a clause that follows the type's name
     * @param cause the Jackson failure, for its stack trace; {@code null} for a limit
     */
    public record Reason(String text, @Nullable Throwable cause) {
    }

    private Schema<?> finish(Schema<?> schema) {
        drain();
        assignNames();
        return schema;
    }

    private Schema<?> schema(TypeKey type, View view) {
        Class<?> raw = ClassUtils.resolvePrimitiveIfNecessary(type.raw());
        if (raw.isArray()) {
            TypeKey component = type.arguments().getFirst();
            return component.raw() == byte.class ? typed("string", "byte") : array(schema(component, view));
        }
        Simple simple = SIMPLE.get(raw);
        if (simple != null) {
            return typed(simple.type(), simple.format());
        }
        if (raw == Character.class) {
            Schema<?> character = typed("string", null);
            character.setMinLength(1);
            character.setMaxLength(1);
            return character;
        }
        TypeKey value = optionalValue(type);
        if (value != null) {
            return nullable(schema(value, view));
        }
        if (raw.isEnum()) {
            return reference(type, Kind.ENUM, View.OUTPUT);
        }
        if (raw.getPackageName().equals("java.time")) {
            return typed("string", null);
        }
        if (Collection.class.isAssignableFrom(raw)) {
            Schema<?> array = array(schema(argument(type, Collection.class, 0), view));
            return Set.class.isAssignableFrom(raw) ? array.uniqueItems(true) : array;
        }
        if (Map.class.isAssignableFrom(raw)) {
            // Jackson writes every key as a string, whatever its type.
            return typed("object", null).additionalProperties(schema(argument(type, Map.class, 1), view));
        }
        if (raw.isRecord()) {
            return reference(type, Kind.RECORD, view);
        }
        return empty(type);
    }

    /** The value of an {@code Optional} or of a primitive optional, which Jackson writes as the value or null. */
    private static @Nullable TypeKey optionalValue(TypeKey type) {
        Class<?> raw = type.raw();
        if (raw == Optional.class) {
            return argument(type, Optional.class, 0);
        }
        if (raw == OptionalInt.class) {
            return classKey(Integer.class);
        }
        if (raw == OptionalLong.class) {
            return classKey(Long.class);
        }
        return raw == OptionalDouble.class ? classKey(Double.class) : null;
    }

    /** A {@code $ref} to the type's component, which is created (and walked later) on its first reference. */
    private Schema<?> reference(TypeKey type, Kind kind, View view) {
        NodeKey key = new NodeKey(type, kind, view);
        Node node = nodes.get(key);
        if (node == null) {
            if (nameLength(type, limits.nameLength()) + (kind == Kind.PAGE ? PAGE.length() : 0) > limits
                    .nameLength()) {
                return empty(kind == Kind.PAGE ? pageOf(type) : render(type),
                        new Reason("its schema name would be longer than " + limits.nameLength() + " characters",
                                null));
            }
            Object subject = kind == Kind.PAGE ? key : key.type();
            if (!counted.contains(subject)) {
                if (counted.size() >= limits.types() || schemas >= limits.schemas()) {
                    return overLimit(key);
                }
                counted.add(subject);
                if (building != null) {
                    countedInBuild.add(subject);
                }
            }
            node = new Node(key);
            nodes.put(key, node);
            pending.add(node);
            if (building != null) {
                createdInBuild.add(node);
            }
        }
        Node parent = building;
        if (parent != null && parent.key.view() == View.INPUT && view == View.INPUT) {
            parent.children.add(node);
        }
        Schema<?> ref = newSchema();
        ref.set$ref(COMPONENTS + node.representative.name);
        node.refs.add(ref);
        return ref;
    }

    /** The first type over the type or schema limit is reported; every later one is {@code {}} too. */
    private Schema<?> overLimit(NodeKey key) {
        if (!limitReached) {
            limitReached = true;
            empty(key.kind() == Kind.PAGE ? pageOf(key.type()) : render(key.type()),
                    new Reason("the schema budget was reached (" + limits.types() + " types, " + limits.schemas()
                            + " schemas), so every type after it is documented as {} too", null));
        }
        return new JsonSchema();
    }

    private Schema<?> empty(TypeKey type) {
        return empty(render(type));
    }

    private Schema<?> empty(String type) {
        undescribed.add(type);
        return new JsonSchema();
    }

    /** A type documented as {@code {}} for a reason: the first reason given for it is kept. */
    private Schema<?> empty(String type, Reason reason) {
        reasons.putIfAbsent(type, reason);
        return empty(type);
    }

    /** The reason of a failed Jackson lookup: the failure's class and message. */
    private static Reason failed(JacksonFailure failure) {
        Throwable cause = Objects.requireNonNull(failure.getCause());
        String message = cause.getMessage();
        return new Reason("Jackson failed on it: " + cause.getClass().getName()
                + (message == null || message.isBlank() ? "" : ": " + message), cause);
    }

    private static String pageOf(TypeKey item) {
        return PageResult.class.getName() + "<" + render(item) + ">";
    }

    private void drain() {
        for (Node node = pending.poll(); node != null; node = pending.poll()) {
            if (schemas >= limits.schemas()) {
                // The total is spent: what is left is documented as {}.
                overLimit(node.key);
                continue;
            }
            build(node);
        }
    }

    /**
     * Walks one component. A component that needs more than its share of schemas, or that Jackson fails on, is
     * {@code {}} instead, and the components first referenced while walking it are dropped with it.
     */
    private void build(Node node) {
        walks++;
        building = node;
        buildSchemas = 0;
        createdInBuild.clear();
        countedInBuild.clear();
        try {
            TypeKey type = node.key.type();
            node.body = switch (node.key.kind()) {
                case ENUM -> enumeration(type);
                case RECORD -> object(type, node.key.view(), component -> true);
                case PAGE -> pageBody(type);
            };
            if (node.key.view() == View.INPUT) {
                Class<?> record = type.raw();
                node.viewsDiffer = !names(record, View.INPUT).equals(names(record, View.OUTPUT))
                        || !omittable(record).isEmpty() || customOutput(record) || customInput(record);
            }
        } catch (TooLarge | JacksonFailure stopped) {
            for (Node created : createdInBuild) {
                nodes.remove(created.key);
                pending.remove(created);
            }
            counted.removeAll(countedInBuild);
            node.children.clear();
            node.viewsDiffer = node.key.view() == View.INPUT;
            Reason reason = stopped instanceof JacksonFailure failure
                    ? failed(failure)
                    : new Reason("its schema needs more than " + limits.schemasPerType() + " schemas", null);
            node.body = empty(node.key.kind() == Kind.PAGE ? pageOf(node.key.type()) : render(node.key.type()),
                    reason);
        } finally {
            building = null;
        }
    }

    private Schema<?> enumeration(TypeKey type) {
        List<Object> values = new ArrayList<>();
        boolean strings = true;
        boolean integers = true;
        for (Object constant : Objects.requireNonNull(type.raw().getEnumConstants())) {
            JsonNode value = jackson(() -> mapper.valueToTree(constant));
            strings &= value.isString();
            integers &= value.isIntegralNumber();
            values.add(value.isString() ? value.asString() : value.numberValue());
        }
        if (!strings && !integers) {
            return empty(type);
        }
        JsonSchema schema = (JsonSchema) typed(strings ? "string" : "integer", null);
        schema._enum(values);
        return schema;
    }

    /** An inline object; a record Jackson fails on is {@code {}}, as a component would be. */
    private Schema<?> inlineObject(Class<?> record, View view, Predicate<String> components) {
        TypeKey type = classKey(record);
        try {
            // Every Jackson lookup in object() happens before it references another type, so nothing is left behind.
            return object(type, view, components);
        } catch (JacksonFailure failure) {
            return empty(render(type), failed(failure));
        }
    }

    private Schema<?> object(TypeKey owner, View view, Predicate<String> include) {
        Class<?> record = owner.raw();
        if (view == View.OUTPUT ? customOutput(record) : customInput(record)) {
            return empty(owner);
        }
        if (view == View.INPUT && !readsByProperty(record)) {
            // Jackson doesn't read it property by property: a delegating creator, @JsonValue, or a deserializer of
            // its own.
            return empty(owner);
        }
        Map<String, String> names = names(record, view);
        Set<String> omittable = view == View.OUTPUT ? omittable(record) : Set.of();
        Schema<?> object = typed("object", null);
        List<String> required = new ArrayList<>();
        for (RecordComponent component : Objects.requireNonNull(record.getRecordComponents())) {
            String name = names.get(component.getName());
            if (name == null || !include.test(component.getName())) {
                continue;
            }
            TypeKey type = componentType(component, owner);
            TypeKey value = optionalValue(type);
            Schema<?> schema = schema(value != null ? value : type, view);
            boolean notNull = constrain(record, component.getName(), schema);
            boolean nullable = !notNull && (value != null || new ResolvedComponent(record, component).nullable());
            object.addProperty(name, nullable ? nullable(schema) : schema);
            if (!nullable && !omittable.contains(component.getName())) {
                required.add(name);
            }
        }
        if (!required.isEmpty()) {
            object.required(required);
        }
        return object;
    }

    private Schema<?> pageBody(TypeKey item) {
        Map<String, String> names = names(PageBody.class, View.OUTPUT);
        Set<String> omittable = omittable(PageBody.class);
        Schema<?> object = typed("object", null);
        List<String> required = new ArrayList<>();
        for (RecordComponent component : Objects.requireNonNull(PageBody.class.getRecordComponents())) {
            String name = names.get(component.getName());
            if (name == null) {
                continue;
            }
            Schema<?> schema = component.getName().equals("items")
                    ? array(schema(item, View.OUTPUT))
                    : schema(componentType(component, classKey(PageBody.class)), View.OUTPUT);
            object.addProperty(name, schema);
            if (!new ResolvedComponent(PageBody.class, component).nullable()
                    && !omittable.contains(component.getName())) {
                required.add(name);
            }
        }
        object.required(required);
        return object;
    }

    /** Whether the mapper reads the record from an object, property by property. */
    private boolean readsByProperty(Class<?> record) {
        return readsByProperty.computeIfAbsent(record, type -> jackson(() -> JsonNames.readsByProperty(mapper, type)));
    }

    /** The components the mapper may leave out when it writes the record. */
    private Set<String> omittable(Class<?> record) {
        return omittable.computeIfAbsent(record, type -> jackson(() -> JsonNames.omittable(mapper, type)));
    }

    /**
     * Whether the record is not written property by property: {@code @JsonSerialize(using = ...)} or
     * {@code (converter = ...)}, {@code @JsonValue}, or {@code @JsonFormat(shape = ARRAY)}.
     */
    private boolean customOutput(Class<?> record) {
        return customOutput.computeIfAbsent(record, type -> jackson(() -> {
            SerializationConfig config = mapper.serializationConfig();
            ClassIntrospector introspector = config.classIntrospectorInstance();
            JavaType javaType = mapper.constructType(type);
            BeanDescription description = introspector
                    .introspectForSerialization(javaType, introspector.introspectClassAnnotations(javaType));
            AnnotatedClass annotated = description.getClassInfo();
            AnnotationIntrospector annotations = config.getAnnotationIntrospector();
            return annotations.findSerializer(config, annotated) != null
                    || annotations.findSerializationConverter(config, annotated) != null
                    || description.findJsonValueAccessor() != null || arrayShape(config, type, annotated);
        }));
    }

    /**
     * Whether the record is not read property by property: {@code @JsonDeserialize(using = ...)} or
     * {@code (converter = ...)}, or {@code @JsonFormat(shape = ARRAY)}.
     */
    private boolean customInput(Class<?> record) {
        return customInput.computeIfAbsent(record, type -> jackson(() -> {
            DeserializationConfig config = mapper.deserializationConfig();
            AnnotatedClass annotated = config
                    .classIntrospectorInstance()
                    .introspectClassAnnotations(mapper.constructType(type));
            AnnotationIntrospector annotations = config.getAnnotationIntrospector();
            return annotations.findDeserializer(config, annotated) != null
                    || annotations.findDeserializationConverter(config, annotated) != null
                    || arrayShape(config, type, annotated);
        }));
    }

    /**
     * Whether Jackson writes and reads the type as an array of its properties. As in Jackson's class-level format, the
     * mapper's config override for the type wins over the class's {@code @JsonFormat}.
     */
    private static boolean arrayShape(MapperConfig<?> config, Class<?> type, AnnotatedClass annotated) {
        JsonFormat.Value format = JsonFormat.Value
                .merge(config.getAnnotationIntrospector().findFormat(config, annotated),
                        config.getDefaultPropertyFormat(type));
        return format != null && format.getShape() == JsonFormat.Shape.ARRAY;
    }

    /** The record components the mapper writes or reads, in component order, with their JSON names. */
    private Map<String, String> names(Class<?> record, View view) {
        Map<Class<?>, Map<String, String>> cache = view == View.OUTPUT ? outputNames : inputNames;
        return cache.computeIfAbsent(record, type -> {
            Map<String, String> json = jackson(
                    () -> view == View.OUTPUT ? JsonNames.serialized(mapper, type) : JsonNames.of(mapper, type));
            Map<String, String> names = new LinkedHashMap<>();
            for (RecordComponent component : Objects.requireNonNull(type.getRecordComponents())) {
                String name = json.get(component.getName());
                if (name != null) {
                    names.put(component.getName(), name);
                }
            }
            return Collections.unmodifiableMap(names);
        });
    }

    /**
     * Runs one lookup in Jackson. When Jackson fails on the type (an exception, or a stack overflow on a type it can't
     * resolve, such as a growing generic), the type is documented as {@code {}} instead of failing the walk.
     */
    private static <T> T jackson(Supplier<T> lookup) {
        try {
            return lookup.get();
        } catch (RuntimeException | StackOverflowError failure) {
            throw new JacksonFailure(failure);
        }
    }

    /**
     * Applies the {@code Default} group constraints of a record component to its schema, and those of its container
     * elements to the items or values.
     *
     * @return whether a constraint requires a value
     */
    private boolean constrain(Class<?> record, String component, Schema<?> schema) {
        PropertyDescriptor property = validator.getConstraintsForClass(record).getConstraintsForProperty(component);
        if (property == null) {
            return false;
        }
        boolean required = keywords(
                property.findConstraints().unorderedAndMatchingGroups(Default.class).getConstraintDescriptors(),
                schema);
        elements(property.getConstrainedContainerElementTypes(), schema);
        return required;
    }

    private static void elements(Set<ContainerElementTypeDescriptor> elements, Schema<?> schema) {
        for (ContainerElementTypeDescriptor element : elements) {
            Schema<?> target = element(element, schema);
            if (target != null) {
                keywords(element.findConstraints().unorderedAndMatchingGroups(Default.class).getConstraintDescriptors(),
                        target);
                elements(element.getConstrainedContainerElementTypes(), target);
            }
        }
    }

    /** The schema a container element's constraints apply to: the items, the map values, or an optional's value. */
    private static @Nullable Schema<?> element(ContainerElementTypeDescriptor element, Schema<?> schema) {
        Class<?> container = element.getContainerClass();
        if (container == Optional.class) {
            return schema;
        }
        if (Map.class.isAssignableFrom(container)) {
            return Integer.valueOf(1).equals(element.getTypeArgumentIndex())
                    && schema.getAdditionalProperties() instanceof Schema<?> values ? values : null;
        }
        if (container.isArray() || Iterable.class.isAssignableFrom(container)) {
            return schema.getItems();
        }
        return null;
    }

    private static boolean keywords(Set<ConstraintDescriptor<?>> constraints, Schema<?> schema) {
        boolean required = false;
        for (ConstraintDescriptor<?> constraint : constraints) {
            required |= keywords(constraint, schema);
        }
        return required;
    }

    /** Applies one constraint, and the constraints it is composed of; returns whether one requires a value. */
    private static boolean keywords(ConstraintDescriptor<?> constraint, Schema<?> schema) {
        Annotation annotation = constraint.getAnnotation();
        boolean required = annotation instanceof NotNull || annotation instanceof NotBlank
                || annotation instanceof NotEmpty;
        if (annotation instanceof NotBlank || annotation instanceof NotEmpty) {
            size(schema, 1, Integer.MAX_VALUE);
        } else if (annotation instanceof Size size) {
            size(schema, size.min(), size.max());
        } else if (annotation instanceof Pattern pattern && has(schema, "string")) {
            schema.pattern(pattern.regexp());
        } else if (annotation instanceof Email && has(schema, "string")) {
            schema.format("email");
        } else if (has(schema, "integer") || has(schema, "number")) {
            bounds(annotation, schema);
        }
        for (ConstraintDescriptor<?> composing : constraint.getComposingConstraints()) {
            required |= keywords(composing, schema);
        }
        return required;
    }

    /** Numeric bounds; when two constraints bound one side, the tighter bound wins. */
    private static void bounds(Annotation annotation, Schema<?> schema) {
        if (annotation instanceof Min min) {
            schema.setMinimum(higher(schema.getMinimum(), BigDecimal.valueOf(min.value())));
        } else if (annotation instanceof Max max) {
            schema.setMaximum(lower(schema.getMaximum(), BigDecimal.valueOf(max.value())));
        } else if (annotation instanceof DecimalMin min) {
            lowerBound(schema, new BigDecimal(min.value()), min.inclusive());
        } else if (annotation instanceof DecimalMax max) {
            upperBound(schema, new BigDecimal(max.value()), max.inclusive());
        } else if (annotation instanceof Positive) {
            lowerBound(schema, BigDecimal.ZERO, false);
        } else if (annotation instanceof PositiveOrZero) {
            lowerBound(schema, BigDecimal.ZERO, true);
        } else if (annotation instanceof Negative) {
            upperBound(schema, BigDecimal.ZERO, false);
        } else if (annotation instanceof NegativeOrZero) {
            upperBound(schema, BigDecimal.ZERO, true);
        }
    }

    private static void lowerBound(Schema<?> schema, BigDecimal value, boolean inclusive) {
        if (inclusive) {
            schema.setMinimum(higher(schema.getMinimum(), value));
        } else {
            schema.setExclusiveMinimumValue(higher(schema.getExclusiveMinimumValue(), value));
        }
    }

    private static void upperBound(Schema<?> schema, BigDecimal value, boolean inclusive) {
        if (inclusive) {
            schema.setMaximum(lower(schema.getMaximum(), value));
        } else {
            schema.setExclusiveMaximumValue(lower(schema.getExclusiveMaximumValue(), value));
        }
    }

    private static BigDecimal higher(@Nullable BigDecimal current, BigDecimal value) {
        return current == null || value.compareTo(current) > 0 ? value : current;
    }

    private static BigDecimal lower(@Nullable BigDecimal current, BigDecimal value) {
        return current == null || value.compareTo(current) < 0 ? value : current;
    }

    /**
     * {@code minLength}/{@code maxLength}, {@code minItems}/{@code maxItems} or {@code minProperties}/
     * {@code maxProperties}, by the schema's type; when two constraints bound one side, the tighter bound wins.
     */
    private static void size(Schema<?> schema, int min, int max) {
        if (has(schema, "array")) {
            schema.setMinItems(atLeast(schema.getMinItems(), min));
            schema.setMaxItems(atMost(schema.getMaxItems(), max));
        } else if (has(schema, "object")) {
            schema.setMinProperties(atLeast(schema.getMinProperties(), min));
            schema.setMaxProperties(atMost(schema.getMaxProperties(), max));
        } else if (has(schema, "string")) {
            schema.setMinLength(atLeast(schema.getMinLength(), min));
            schema.setMaxLength(atMost(schema.getMaxLength(), max));
        }
    }

    /** The tighter lower bound; {@code null} while there is none (0 bounds nothing). */
    private static @Nullable Integer atLeast(@Nullable Integer current, int min) {
        if (min <= 0) {
            return current;
        }
        return current == null ? Integer.valueOf(min) : Integer.valueOf(Math.max(current, min));
    }

    /** The tighter upper bound; {@code null} while there is none ({@code Integer.MAX_VALUE} bounds nothing). */
    private static @Nullable Integer atMost(@Nullable Integer current, int max) {
        if (max == Integer.MAX_VALUE) {
            return current;
        }
        return current == null ? Integer.valueOf(max) : Integer.valueOf(Math.min(current, max));
    }

    private static boolean has(Schema<?> schema, String type) {
        Set<String> types = schema.getTypes();
        return types != null && types.contains(type);
    }

    /** Admits {@code null}: {@code types: [<t>, "null"]}, or {@code anyOf: [<ref>, {type: null}]} for a reference. */
    private Schema<?> nullable(Schema<?> schema) {
        if (schema.get$ref() != null) {
            Schema<?> none = typed("null", null);
            return newSchema().addAnyOfItem(schema).addAnyOfItem(none);
        }
        Set<String> types = schema.getTypes();
        if (types != null && !types.isEmpty()) {
            schema.addType("null");
        }
        return schema;
    }

    private Schema<?> typed(String type, @Nullable String format) {
        Schema<?> schema = newSchema();
        schema.addType(type);
        if (format != null) {
            schema.setFormat(format);
        }
        return schema;
    }

    private Schema<?> array(Schema<?> items) {
        return typed("array", null).items(items);
    }

    /**
     * Every schema the walk keeps comes from here, so that the limits count it: a component over its share stops
     * being walked ({@link TooLarge}), and the total stops the walk.
     */
    private Schema<?> newSchema() {
        schemas++;
        if (building != null && ++buildSchemas > limits.schemasPerType()) {
            throw new TooLarge();
        }
        return new JsonSchema();
    }

    /** The length of the type's simple component name, counted no further than just past the limit. */
    private static int nameLength(TypeKey type, int limit) {
        if (type.raw().isArray()) {
            return nameLength(type.arguments().getFirst(), limit) + "Array".length();
        }
        int length = type.raw().getSimpleName().length();
        List<TypeKey> arguments = type.arguments();
        if (!arguments.isEmpty()) {
            length += "Of".length() + "And".length() * (arguments.size() - 1);
        }
        for (int i = 0; i < arguments.size() && length <= limit; i++) {
            length += nameLength(arguments.get(i), limit - length);
        }
        return length;
    }

    /** A record component's type, with the record's type arguments substituted. */
    private static TypeKey componentType(RecordComponent component, TypeKey owner) {
        return resolve(component.getGenericType(), bindings(owner), new HashSet<>());
    }

    /**
     * A type argument of the type's supertype {@code target}, such as the element type of a collection or the value
     * type of a map; {@code Object} when the type doesn't say.
     */
    private static TypeKey argument(TypeKey type, Class<?> target, int index) {
        List<TypeKey> arguments = supertypeArguments(type, target);
        return arguments != null && index < arguments.size() ? arguments.get(index) : OBJECT;
    }

    private static @Nullable List<TypeKey> supertypeArguments(TypeKey type, Class<?> target) {
        Class<?> raw = type.raw();
        if (raw == target) {
            return type.arguments();
        }
        Map<TypeVariable<?>, TypeKey> bindings = bindings(type);
        List<Type> supertypes = new ArrayList<>(List.of(raw.getGenericInterfaces()));
        Type superclass = raw.getGenericSuperclass();
        if (superclass != null) {
            supertypes.add(superclass);
        }
        for (Type supertype : supertypes) {
            TypeKey resolved = resolve(supertype, bindings, new HashSet<>());
            if (target.isAssignableFrom(resolved.raw())) {
                List<TypeKey> arguments = supertypeArguments(resolved, target);
                if (arguments != null) {
                    return arguments;
                }
            }
        }
        return null;
    }

    /** The type's type variables, bound to its arguments. */
    private static Map<TypeVariable<?>, TypeKey> bindings(TypeKey type) {
        TypeVariable<?>[] variables = type.raw().getTypeParameters();
        if (variables.length == 0 || variables.length != type.arguments().size()) {
            return Map.of();
        }
        Map<TypeVariable<?>, TypeKey> bindings = new HashMap<>();
        for (int i = 0; i < variables.length; i++) {
            bindings.put(variables[i], type.arguments().get(i));
        }
        return bindings;
    }

    /**
     * Resolves a declared type with the given bindings. An unbound type variable or a wildcard is its bound (a lower
     * bound first, as Spring resolves it); a variable bounded by itself, such as {@code T extends Comparable<T>}, ends
     * at {@code Object}.
     */
    private static TypeKey resolve(Type type, Map<TypeVariable<?>, TypeKey> bindings,
            Set<TypeVariable<?>> expanding) {
        if (type instanceof Class<?> raw) {
            if (raw.isArray()) {
                return new TypeKey(raw, List.of(resolve(raw.getComponentType(), bindings, expanding)));
            }
            List<TypeKey> arguments = new ArrayList<>();
            for (TypeVariable<?> variable : raw.getTypeParameters()) {
                arguments.add(resolve(variable, Map.of(), expanding));
            }
            return new TypeKey(raw, List.copyOf(arguments));
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            List<TypeKey> arguments = new ArrayList<>();
            for (Type argument : parameterized.getActualTypeArguments()) {
                arguments.add(resolve(argument, bindings, expanding));
            }
            return new TypeKey(raw, List.copyOf(arguments));
        }
        if (type instanceof GenericArrayType array) {
            TypeKey component = resolve(array.getGenericComponentType(), bindings, expanding);
            return new TypeKey(component.raw().arrayType(), List.of(component));
        }
        if (type instanceof TypeVariable<?> variable) {
            TypeKey bound = bindings.get(variable);
            if (bound != null) {
                return bound;
            }
            if (!expanding.add(variable)) {
                return OBJECT;
            }
            try {
                return resolve(variable.getBounds()[0], bindings, expanding);
            } finally {
                expanding.remove(variable);
            }
        }
        if (type instanceof WildcardType wildcard) {
            Type[] lower = wildcard.getLowerBounds();
            return resolve(lower.length > 0 ? lower[0] : wildcard.getUpperBounds()[0], bindings, expanding);
        }
        return OBJECT;
    }

    private static TypeKey classKey(Class<?> type) {
        return resolve(type, Map.of(), new HashSet<>());
    }

    /** Names the components; a clash makes both claimants qualified, and a clash left after that gets a suffix. */
    private void assignNames() {
        if (nodes.size() == named) {
            return;
        }
        named = nodes.size();
        markInputsDocumentedAsOutputs();
        List<Node> published = new ArrayList<>();
        for (Node node : nodes.values()) {
            node.representative = node;
            if (node.same) {
                Node twin = nodes.get(new NodeKey(node.key.type(), Kind.RECORD, View.OUTPUT));
                if (twin != null) {
                    node.representative = twin;
                }
            }
            if (node.representative == node) {
                published.add(node);
            }
        }
        Map<String, Integer> claims = new HashMap<>();
        Map<String, Integer> taken = new HashMap<>();
        for (String reserved : RESERVED) {
            // Claimed and taken already, so a type with that simple name takes its qualified name.
            claims.put(reserved, 1);
            taken.put(reserved, 1);
        }
        for (Node node : published) {
            claims.merge(node.candidate(false), 1, Integer::sum);
        }
        for (Node node : published) {
            String name = node.candidate(claims.getOrDefault(node.candidate(false), 0) > 1);
            int count = taken.merge(name, 1, Integer::sum);
            node.name = count == 1 ? name : name + "-" + count;
        }
        for (Node node : nodes.values()) {
            String ref = COMPONENTS + node.representative.name;
            for (Schema<?> schema : node.refs) {
                schema.set$ref(ref);
            }
        }
    }

    /**
     * Marks the input components documented as their output: those whose JSON names are the same both ways and that
     * hold only such input components.
     */
    private void markInputsDocumentedAsOutputs() {
        // Node keeps identity equality, so a HashMap keys it by identity.
        Map<Node, List<Node>> parents = new HashMap<>();
        Deque<Node> differing = new ArrayDeque<>();
        for (Node node : nodes.values()) {
            if (node.key.view() == View.INPUT) {
                node.same = !node.viewsDiffer;
                if (!node.same) {
                    differing.add(node);
                }
                for (Node child : node.children) {
                    parents.computeIfAbsent(child, key -> new ArrayList<>()).add(node);
                }
            }
        }
        for (Node node = differing.poll(); node != null; node = differing.poll()) {
            for (Node parent : parents.getOrDefault(node, List.of())) {
                if (parent.same) {
                    parent.same = false;
                    differing.add(parent);
                }
            }
        }
    }

    /** The key of a type a caller hands in; the walk itself resolves types without {@code ResolvableType}. */
    private static TypeKey key(ResolvableType type) {
        return key(type, new HashSet<>());
    }

    private static TypeKey key(ResolvableType type, Set<Type> expanding) {
        Type declared = type.getType();
        boolean variable = declared instanceof TypeVariable<?>;
        if (variable && !expanding.add(declared)) {
            return OBJECT;
        }
        try {
            if (type.isArray()) {
                return new TypeKey(type.toClass(), List.of(key(type.getComponentType(), expanding)));
            }
            List<TypeKey> arguments = new ArrayList<>();
            for (ResolvableType generic : type.getGenerics()) {
                arguments.add(key(generic, expanding));
            }
            return new TypeKey(type.toClass(), List.copyOf(arguments));
        } finally {
            if (variable) {
                expanding.remove(declared);
            }
        }
    }

    private static String name(TypeKey type, boolean qualified) {
        if (type.raw().isArray()) {
            return name(type.arguments().getFirst(), qualified) + "Array";
        }
        String raw = qualified ? qualifiedName(type.raw()) : type.raw().getSimpleName();
        if (type.arguments().isEmpty()) {
            return raw;
        }
        StringJoiner name = new StringJoiner("And", raw + "Of", "");
        for (TypeKey argument : type.arguments()) {
            name.add(name(argument, qualified));
        }
        return name.toString();
    }

    private static String render(TypeKey type) {
        if (type.raw().isArray()) {
            return render(type.arguments().getFirst()) + "[]";
        }
        String raw = qualifiedName(type.raw());
        if (type.arguments().isEmpty()) {
            return raw;
        }
        StringJoiner rendered = new StringJoiner(", ", raw + "<", ">");
        for (TypeKey argument : type.arguments()) {
            rendered.add(render(argument));
        }
        return rendered.toString();
    }

    private static String qualifiedName(Class<?> type) {
        String canonical = type.getCanonicalName();
        return canonical != null ? canonical : type.getName().replace('$', '.');
    }

    private static Map<Class<?>, Simple> simpleTypes() {
        Map<Class<?>, Simple> types = new HashMap<>();
        types.put(String.class, new Simple("string", null));
        types.put(UUID.class, new Simple("string", "uuid"));
        types.put(URI.class, new Simple("string", "uri"));
        types.put(Boolean.class, new Simple("boolean", null));
        types.put(Byte.class, new Simple("integer", "int32"));
        types.put(Short.class, new Simple("integer", "int32"));
        types.put(Integer.class, new Simple("integer", "int32"));
        types.put(Long.class, new Simple("integer", "int64"));
        types.put(BigInteger.class, new Simple("integer", null));
        types.put(Float.class, new Simple("number", "float"));
        types.put(Double.class, new Simple("number", "double"));
        types.put(BigDecimal.class, new Simple("number", null));
        types.put(Instant.class, new Simple("string", "date-time"));
        types.put(OffsetDateTime.class, new Simple("string", "date-time"));
        types.put(ZonedDateTime.class, new Simple("string", "date-time"));
        types.put(LocalDate.class, new Simple("string", "date"));
        types.put(Duration.class, new Simple("string", "duration"));
        // Jackson writes a Year as a number; the other java.time types are strings.
        types.put(Year.class, new Simple("integer", null));
        return Map.copyOf(types);
    }

    /** Which way the mapper sees a record: writing it (outputs) or reading it (inputs). */
    private enum View {
        OUTPUT, INPUT
    }

    private enum Kind {
        RECORD, ENUM, PAGE
    }

    /** A JSON type and format. */
    private record Simple(String type, @Nullable String format) {
    }

    /** Stops the walk of a type Jackson fails on (see {@link #jackson}), with Jackson's failure as its cause. */
    private static final class JacksonFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        JacksonFailure(Throwable cause) {
            super(null, cause, false, false);
        }
    }

    /** Stops the walk of a component that needs more than its share of schemas. */
    private static final class TooLarge extends RuntimeException {

        private static final long serialVersionUID = 1L;

        TooLarge() {
            super(null, null, false, false);
        }
    }

    /**
     * A resolved type, compared by value: its class and its type arguments (for an array, its component type).
     *
     * @param hash the hash code, computed once and order-sensitive: the default record hash of a one-argument list
     *        adds the argument's hash unscaled, so {@code Grow<List<Set<T>>>} and {@code Grow<Set<List<T>>>} would
     *        collide, and every parameterization at one depth with them
     */
    private record TypeKey(Class<?> raw, List<TypeKey> arguments, int hash) {

        TypeKey(Class<?> raw, List<TypeKey> arguments) {
            this(raw, arguments, hash(raw, arguments));
        }

        private static int hash(Class<?> raw, List<TypeKey> arguments) {
            int hash = raw.hashCode();
            for (TypeKey argument : arguments) {
                hash = 31 * hash + argument.hash;
            }
            return 31 * hash + arguments.size();
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return this == other || (other instanceof TypeKey key && hash == key.hash && raw == key.raw
                    && arguments.equals(key.arguments));
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** A component's identity: the type, what it describes, and the view (enums and pages are outputs). */
    private record NodeKey(TypeKey type, Kind kind, View view) {
    }

    /** A component: its schema, and every {@code $ref} to it, which naming updates. */
    private static final class Node {

        final NodeKey key;

        final List<Schema<?>> refs = new ArrayList<>();

        /** For an input record: the input records its schema references. */
        final List<Node> children = new ArrayList<>();

        Schema<?> body = new JsonSchema();

        /** For an input record: whether it is documented differently from its output, before what it holds. */
        boolean viewsDiffer;

        /** For an input record: whether it is documented as its output. */
        boolean same;

        /** The component published for this one: itself, or the output an input is documented as. */
        Node representative = this;

        String name = "";

        Node(NodeKey key) {
            this.key = key;
        }

        String candidate(boolean qualified) {
            String base = name(key.type(), qualified);
            return switch (key.kind()) {
                case PAGE -> base + "Page";
                case ENUM -> base;
                case RECORD -> key.view() == View.INPUT && !same ? base + "Input" : base;
            };
        }
    }
}
