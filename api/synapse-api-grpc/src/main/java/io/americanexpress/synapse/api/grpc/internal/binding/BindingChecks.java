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
package io.americanexpress.synapse.api.grpc.internal.binding;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.internal.binding.ComponentRefs;
import io.americanexpress.synapse.core.internal.binding.OperationTypes;
import io.americanexpress.synapse.core.internal.binding.PersistenceTypes;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.internal.conditional.VersionTokens;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * The startup checks of a {@code GrpcBindings} bean that need only its declarations, not the messages' fields. Each
 * failure is a {@link SynapseConfigurationException} whose description starts with
 * {@code gRPC method <service>/<method> (<operation>): }. For every {@code .unary} declaration, in declaration
 * order, it fails when:
 * <ul>
 * <li>the method belongs to another service than the one the bindings were created for;</li>
 * <li>the method is not unary;</li>
 * <li>the method was declared before;</li>
 * <li>the input or the output is nested more than 32 levels deep, or holds more than 10,000 types;</li>
 * <li>a {@code PageQuery} is neither the input nor a component of the input record itself, the input has more than
 * one, or one is named by {@code .map} or {@code .context} (it binds from the request's AIP-158 page fields); a
 * {@code PageResult} is anywhere but the operation's direct output (in the input, an {@code Optional}, a list, a
 * record component or a page's items); a {@code PageQuery} is in the output;</li>
 * <li>{@code .sortable} is declared twice, on a method whose input binds no {@code PageQuery}, or with an enum without
 * constants or whose constants' lower-case names are not distinct names of letters, digits and {@code _};</li>
 * <li>on a method that binds a {@code PageQuery} or returns a {@code PageResult}, an input component other than the
 * {@code PageQuery} and the {@code VersionCondition}s can't be written into the cursor's input digest (as REST checks
 * its paged routes);</li>
 * <li>the input or the output holds a persistence entity;</li>
 * <li>the input is neither a record nor {@code Void};</li>
 * <li>a {@code .map} component is in neither the input type nor the output type (the record itself or a record
 * nested in it), or a {@code .context} component is not in the input type;</li>
 * <li>a component has two sources: {@code .map} and {@code .context}, or two of either;</li>
 * <li>{@code .notFound} is declared but the output is not an {@code Optional};</li>
 * <li>a {@code VersionCondition} is named by {@code .context}, or by a {@code .map} to a field other than {@code etag}
 * (If-Match, its default) or {@code if_none_match} (If-None-Match); sits inside another type of the input rather than
 * being a component of the input record itself; has no version type of core's version token codec; appears in the
 * output; shares its field with another one; or isn't {@code @Nullable} on a {@code QueryOperation} (a read can't
 * require a precondition, as on REST);</li>
 * <li>{@code .etag} is declared twice, on a write whose input has a {@code VersionCondition} (REST sends no
 * {@code ETag} on {@code PUT} or {@code DELETE}), on an output that is not a record or an {@code Optional} of one, or
 * for a component of the output record whose type is not a version type (a string, an integral number or a
 * {@code java.time} value; the same set as REST).</li>
 * </ul>
 * Field-level checks are made when the messages are mapped; unbound methods and {@code .bindAllByName} are resolved
 * when the service is compiled.
 */
public final class BindingChecks {

    /** The request field a {@code VersionCondition} binds from by default: If-Match. */
    private static final String ETAG_FIELD = "etag";

    /** The request fields a {@code VersionCondition} binds from: If-Match and If-None-Match, as REST's two headers. */
    private static final Set<String> TOKEN_FIELDS = Set.of(ETAG_FIELD, "if_none_match");

    /** How deep a type walk follows nested records and containers. */
    private static final int MAX_TYPE_DEPTH = 32;

    /** How many types a type walk visits at most, so a generic record that grows can't hang startup. */
    private static final int MAX_TYPE_VISITS = 10_000;

    private BindingChecks() {
    }

    /**
     * Checks a service's declarations.
     *
     * @param declaration the bindings as declared
     * @throws SynapseConfigurationException on the first problem, with the method named
     */
    public static void check(ServiceDeclaration declaration) {
        Set<String> bound = new HashSet<>();
        for (MethodDeclaration method : declaration.methods()) {
            checkService(declaration.service(), method);
            checkUnary(method);
            if (!bound.add(method.method().getFullMethodName())) {
                throw failure(method, "bound more than once", "Bind each method once.");
            }
            TypeTree input = walk(method, "input", method.inputType(), method.inputGenericType());
            // A page's items are walked, so a PageResult left in the tree is one nested in the output.
            ResolvableType outputType = method.outputGenericType();
            TypeTree output = walk(method, "output", method.outputValueType(),
                    method.pagedOutput() ? outputType.getGeneric(0) : outputType);
            checkPaging(method, input, output);
            checkEntities(method, input, output);
            if (!method.inputType().isRecord() && method.inputType() != Void.class) {
                throw failure(method, "the input type " + method.inputType().getName() + " is not a record or Void",
                        "Declare the operation's input as a record, or as Void if it takes none.");
            }
            checkSources(method, input, output);
            if (method.notFound() != null && method.outputType() != Optional.class) {
                throw failure(method,
                        ".notFound(...) needs an Optional output, but the output type is "
                                + method.outputType().getSimpleName(),
                        "Return an Optional from the operation, or remove .notFound(...).");
            }
            boolean conditional = checkVersionConditions(method, output);
            // A read may carry a condition and still send its etag, as REST's GET binds If-None-Match.
            checkETag(method, conditional && !isOperation(method, QueryOperation.class));
        }
    }

    /**
     * Returns a binding failure with the method and operation named.
     *
     * @param method the method declaration
     * @param problem what is wrong
     * @param action what to do about it
     * @return the failure, to throw
     */
    public static SynapseConfigurationException failure(MethodDeclaration method, String problem, String action) {
        return failure(method.method(), method.operationName(), problem, action);
    }

    /**
     * Returns a binding failure with the method and operation named, for a method whose declaration doesn't exist yet.
     *
     * @param method the method descriptor
     * @param operationName the operation name
     * @param problem what is wrong
     * @param action what to do about it
     * @return the failure, to throw
     */
    public static SynapseConfigurationException failure(MethodDescriptor<?, ?> method, String operationName,
            String problem, String action) {
        return new SynapseConfigurationException(
                "gRPC method " + method.getFullMethodName() + " (" + operationName + "): " + problem, action);
    }

    private static void checkService(ServiceDescriptor service, MethodDeclaration method) {
        String owner = method.method().getServiceName();
        if (!service.getName().equals(owner)) {
            throw failure(method, "belongs to " + owner + ", not to " + service.getName(),
                    "Use the method descriptors of the service the bindings were created for.");
        }
    }

    private static void checkUnary(MethodDeclaration method) {
        MethodDescriptor.MethodType type = method.method().getType();
        if (type != MethodDescriptor.MethodType.UNARY) {
            throw failure(method, "is " + type + "; only unary methods can be bound in Synapse 2.0",
                    "Implement it in a hand-written *ImplBase service.");
        }
    }

    /**
     * Checks the paging types, as REST does: a {@code PageQuery} is the input or one component of the input record
     * itself, bound from the request's page fields only; a {@code PageResult} is the operation's direct output only;
     * {@code .sortable} is declared once, on a method that binds a {@code PageQuery}, with valid keys.
     */
    private static void checkPaging(MethodDeclaration method, TypeTree input, TypeTree output) {
        Class<?> inputType = method.inputType();
        if (input.types.contains(PageResult.class)) {
            throw failure(method,
                    "the input type " + inputType.getSimpleName() + " holds a PageResult, which is output only",
                    "Take a PageQuery in the input, and return the PageResult from the operation.");
        }
        if (output.types.contains(PageQuery.class)) {
            throw failure(method,
                    "the output type " + method.outputValueType().getSimpleName()
                            + " holds a PageQuery, which is input only",
                    "Return a PageResult, or remove the PageQuery from the output.");
        }
        if (output.types.contains(PageResult.class)) {
            throw failure(method,
                    "the output type " + method.outputGenericType().resolve(Object.class).getSimpleName()
                            + " holds a PageResult, but a PageResult must be the operation's direct output",
                    "Return the PageResult itself from the operation, so Synapse writes it as the page and wraps its "
                            + "cursor.");
        }
        List<ResolvedComponent> pages = new ArrayList<>();
        if (inputType.isRecord() && inputType != PageQuery.class) {
            ResolvableType generic = method.inputGenericType();
            for (ResolvedComponent component : ComponentRefs.components(inputType)) {
                if (component.type() == PageQuery.class) {
                    pages.add(component);
                } else if (TypeTree
                        .of(ResolvableType.forType(component.genericType(), generic)).types
                        .contains(PageQuery.class)) {
                    throw failure(method,
                            describe(component) + " holds a PageQuery, but gRPC binds one only as the input or as a "
                                    + "component of the input record itself, from the request's page fields",
                            "Declare the PageQuery as a component of " + inputType.getSimpleName()
                                    + " itself, not inside another type.");
                }
            }
        }
        if (pages.size() > 1) {
            throw failure(method,
                    "the input type " + inputType.getSimpleName() + " has more than one PageQuery component ("
                            + pages.stream().map(BindingChecks::describe).collect(Collectors.joining(", ")) + ")",
                    "Keep one PageQuery component: the request's page fields fill it.");
        }
        for (FieldOverride override : method.overrides()) {
            if (override.component().type() == PageQuery.class) {
                throw failure(method, notAPageSource(override.component(), ".map(\"" + override.fieldPath()
                        + "\", ...)"), "Remove .map(\"" + override.fieldPath() + "\", ...).");
            }
        }
        for (ContextSource context : method.contexts()) {
            if (context.component().type() == PageQuery.class) {
                throw failure(method, notAPageSource(context.component(), ".context(...)"), "Remove .context(...).");
            }
        }
        checkSortable(method);
        if (method.pagedInput() || method.pagedOutput()) {
            checkDigestible(method);
        }
    }

    /**
     * Fails when an input component of a paged method can't be written into the cursor's input digest, as REST checks
     * its paged routes, so a garbage token is always {@code INVALID_ARGUMENT}, never {@code INTERNAL}. The
     * {@code PageQuery} and the {@code VersionCondition}s are not digested.
     */
    private static void checkDigestible(MethodDeclaration method) {
        Class<?> inputType = method.inputType();
        if (!inputType.isRecord() || inputType == PageQuery.class) {
            return;
        }
        ResolvableType input = method.inputGenericType();
        Set<String> checked = new HashSet<>();
        for (ResolvedComponent component : ComponentRefs.components(inputType)) {
            Class<?> type = component.type();
            if (type == PageQuery.class || VersionCondition.class.isAssignableFrom(type)) {
                continue;
            }
            if (!digestible(ResolvableType.forType(component.genericType(), input), checked)) {
                throw failure(method,
                        describe(component) + " (type " + component.genericType().getTypeName()
                                + ") can't be bound into a paging cursor; use a record or simple type",
                        "Make the component a record, an enum, a byte[], or a string, number, boolean, UUID or "
                                + "java.time value (or a List, a Set or a Map of them with simple keys), or remove it "
                                + "from the input of this paged method.");
            }
        }
    }

    /**
     * Whether core's {@code InputDigest} writes the type canonically: REST's digestible types, plus {@code byte[]}
     * (a gRPC {@code bytes} field) and the simple keys of a gRPC {@code map}. Each type is checked once (the walk was
     * bounded by {@link TypeTree}); a record that contains itself is digestible when its other components are.
     */
    private static boolean digestible(ResolvableType type, Set<String> checked) {
        Class<?> raw = type.resolve();
        if (raw == null) {
            return false;
        }
        if (isSimpleValue(raw) || raw == byte[].class) {
            return true;
        }
        if (!checked.add(type.toString())) {
            return true;
        }
        if (raw.isRecord()) {
            for (RecordComponent component : raw.getRecordComponents()) {
                if (!digestible(ResolvableType.forType(component.getGenericType(), type), checked)) {
                    return false;
                }
            }
            return true;
        }
        if (raw == Optional.class || List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw)) {
            return digestible(type.getGeneric(0), checked);
        }
        if (Map.class.isAssignableFrom(raw)) {
            Class<?> key = type.getGeneric(0).resolve();
            return key != null && isSimpleValue(key) && digestible(type.getGeneric(1), checked);
        }
        return false;
    }

    /** A string, number, boolean, enum, {@code java.time} value or UUID, as REST's digest admits them. */
    private static boolean isSimpleValue(Class<?> type) {
        Class<?> boxed = ClassUtils.resolvePrimitiveIfNecessary(type);
        return boxed == String.class || Number.class.isAssignableFrom(boxed) || boxed == Boolean.class
                || boxed.isEnum() || "java.time".equals(boxed.getPackageName()) || boxed == UUID.class;
    }

    private static String notAPageSource(ResolvedComponent component, String source) {
        return describe(component) + " is a PageQuery, which binds only from the request's page_size, page_token, "
                + "skip and order_by fields, not from " + source;
    }

    private static void checkSortable(MethodDeclaration method) {
        List<Class<? extends Enum<?>>> sortables = method.sortables();
        if (sortables.isEmpty()) {
            return;
        }
        if (sortables.size() > 1) {
            throw failure(method, ".sortable(...) is declared twice",
                    "Declare .sortable once per method, with every key callers may sort by.");
        }
        if (!method.pagedInput()) {
            throw failure(method,
                    ".sortable(...) needs a method that binds PageQuery, but the input type "
                            + method.inputType().getSimpleName() + " has no PageQuery component",
                    "Add a PageQuery component to the input, or remove .sortable(...) from this method.");
        }
        try {
            SortKeys.of(sortables.getFirst());
        } catch (SynapseConfigurationException e) {
            SynapseConfigurationException failure = failure(method, e.description(), e.action());
            failure.initCause(e);
            throw failure;
        }
    }

    private static void checkEntities(MethodDeclaration method, TypeTree input, TypeTree output) {
        for (TypeTree tree : List.of(input, output)) {
            for (Class<?> type : tree.types) {
                if (PersistenceTypes.isPersistenceType(type)) {
                    throw failure(method, type.getName() + " is a persistence entity",
                            "Bind a record from the service's model and map the entity in the operation.");
                }
            }
        }
    }

    private static void checkSources(MethodDeclaration method, TypeTree input, TypeTree output) {
        Map<String, String> sources = new HashMap<>();
        for (FieldOverride override : method.overrides()) {
            ResolvedComponent component = override.component();
            String source = ".map(\"" + override.fieldPath() + "\", ...)";
            if (!input.records.contains(component.recordType()) && !output.records.contains(component.recordType())) {
                throw failure(method,
                        source + " names " + describe(component) + ", which is not in the input type "
                                + method.inputType().getSimpleName() + " or the output type "
                                + method.outputValueType().getSimpleName(),
                        "Name a component of the operation's input or output record, or of a record nested in one.");
            }
            claim(method, sources, component, source);
        }
        for (ContextSource context : method.contexts()) {
            ResolvedComponent component = context.component();
            String source = ".context(...)";
            if (!input.records.contains(component.recordType())) {
                throw failure(method,
                        source + " names " + describe(component) + ", which is not in the input type "
                                + method.inputType().getSimpleName(),
                        "Name a component of the operation's input record, or of a record nested in it.");
            }
            claim(method, sources, component, source);
        }
    }

    /**
     * Checks the input's {@code VersionCondition}s, as REST checks the components it binds from {@code If-Match} and
     * {@code If-None-Match}: each is a component of the input record itself with a version type of core's codec,
     * bound from a token field ({@code etag} for If-Match by default, or {@code .map("if_none_match", ...)} for
     * If-None-Match), at most one per field, and {@code @Nullable} on a read. The output holds none.
     *
     * @return whether the input has a {@code VersionCondition} component
     */
    private static boolean checkVersionConditions(MethodDeclaration method, TypeTree output) {
        Map<String, String> fields = new HashMap<>();
        for (FieldOverride override : method.overrides()) {
            ResolvedComponent component = override.component();
            if (component.type() != VersionCondition.class) {
                continue;
            }
            String source = ".map(\"" + override.fieldPath() + "\", ...)";
            if (!TOKEN_FIELDS.contains(override.fieldPath())) {
                throw failure(method, notATokenSource(component, source),
                        "Map it to \"etag\" (If-Match) or \"if_none_match\" (If-None-Match), or remove " + source
                                + ".");
            }
            fields.put(component.name(), override.fieldPath());
        }
        for (ContextSource context : method.contexts()) {
            if (context.component().type() == VersionCondition.class) {
                throw failure(method, notATokenSource(context.component(), ".context(...)"), "Remove .context(...).");
            }
        }
        Class<?> inputType = method.inputType();
        Map<String, List<ResolvedComponent>> byField = new LinkedHashMap<>();
        if (inputType.isRecord()) {
            ResolvableType input = method.inputGenericType();
            for (ResolvedComponent component : ComponentRefs.components(inputType)) {
                if (component.type() == VersionCondition.class) {
                    byField
                            .computeIfAbsent(fields.getOrDefault(component.name(), ETAG_FIELD),
                                    field -> new ArrayList<>())
                            .add(component);
                } else if (TypeTree
                        .of(ResolvableType.forType(component.genericType(), input)).types
                        .contains(VersionCondition.class)) {
                    throw failure(method,
                            describe(component) + " holds a VersionCondition, but gRPC binds one only as a component "
                                    + "of the input record itself, from the request's etag or if_none_match field",
                            "Declare the VersionCondition as a component of " + inputType.getSimpleName()
                                    + " itself, not inside another type.");
                }
            }
        }
        for (Map.Entry<String, List<ResolvedComponent>> field : byField.entrySet()) {
            List<ResolvedComponent> conditions = field.getValue();
            if (conditions.size() > 1) {
                throw failure(method,
                        "the input " + inputType.getSimpleName() + " has " + conditions.size()
                                + " VersionCondition components bound to the " + field.getKey() + " field ("
                                + conditions.stream().map(ResolvedComponent::name).collect(Collectors.joining(", "))
                                + "); a gRPC request carries one per field",
                        "Bind the If-None-Match one with .map(\"if_none_match\", ...), or keep one VersionCondition "
                                + "component per field.");
            }
        }
        if (output.types.contains(VersionCondition.class)) {
            throw failure(method,
                    "the output type " + method.outputValueType().getSimpleName()
                            + " holds a VersionCondition, which is input only",
                    "Return the version itself, such as a long, and send it as the etag with .etag(...).");
        }
        boolean read = isOperation(method, QueryOperation.class);
        for (List<ResolvedComponent> conditions : byField.values()) {
            ResolvedComponent condition = conditions.getFirst();
            if (read && !condition.nullable()) {
                throw failure(method,
                        describe(condition) + " is a VersionCondition that isn't @Nullable, but the operation is a "
                                + "QueryOperation: a read can't require a precondition",
                        "Declare " + describe(condition) + " @Nullable, or bind a CommandOperation.");
            }
            checkVersionType(method, condition);
        }
        return !byField.isEmpty();
    }

    private static String notATokenSource(ResolvedComponent component, String source) {
        return describe(component) + " is a VersionCondition, which binds only from the request's etag or "
                + "if_none_match field, not from " + source;
    }

    /**
     * Fails when the condition's version type is missing (raw, a wildcard or a type variable), or is {@code Object} or
     * another abstract supertype of {@code String} other than {@code CharSequence}: the etag's value would stay a
     * {@code String}, which never equals a version of another type, so every conditional write would fail (as REST
     * checks).
     */
    private static void checkVersionType(MethodDeclaration method, ResolvedComponent condition) {
        Type argument = condition.genericType() instanceof ParameterizedType parameterized
                ? parameterized.getActualTypeArguments()[0]
                : null;
        if (!(argument instanceof Class<?> || argument instanceof ParameterizedType)) {
            throw failure(method, describe(condition) + " is a VersionCondition without a version type",
                    "Declare the version type, for example VersionCondition<Long>.");
        }
        Class<?> version = ResolvableType.forType(argument).toClass();
        boolean abstractType = version == Object.class || version.isInterface()
                || Modifier.isAbstract(version.getModifiers());
        if (abstractType && version != CharSequence.class && version.isAssignableFrom(String.class)) {
            throw failure(method,
                    describe(condition) + " is a VersionCondition<" + version.getSimpleName()
                            + ">; the version type must be a concrete type such as Long or String",
                    "Declare the type of the resource's version, for example VersionCondition<Long>: the etag's value "
                            + "is converted to it and compared with the version the operation reads.");
        }
        if (!VersionTokens.supports(version)) {
            // One set of version types for every protocol: the ones core's version token codec reads.
            throw failure(method,
                    describe(condition) + " is a VersionCondition<" + version.getSimpleName()
                            + ">, which is not a version type",
                    "Declare a version type: " + VersionTokens.TYPES + ".");
        }
    }

    /**
     * Checks {@code .etag}: declared once, on a read or on a write whose input has no {@code VersionCondition} (a
     * create), as REST allows it on {@code GET}/{@code HEAD} and {@code POST} with {@code .created} only, for one
     * simple component of a single record output.
     */
    private static void checkETag(MethodDeclaration method, boolean conditional) {
        List<ResolvedComponent> etags = method.etags();
        if (etags.isEmpty()) {
            return;
        }
        if (etags.size() > 1) {
            throw failure(method, ".etag(...) is declared twice",
                    "Declare .etag(...) once per method, with the output component that versions the resource.");
        }
        if (conditional) {
            throw failure(method,
                    ".etag(...) can't be used on a write whose input has a VersionCondition: Synapse sends no etag in "
                            + "an update or delete response, as REST sends no ETag on PUT or DELETE",
                    "Remove .etag(...) from this method, and declare it on the method clients read the resource "
                            + "from.");
        }
        Class<?> outputType = method.outputType();
        Class<?> outputValue = method.outputValueType();
        if (outputType == List.class || outputType == PageResult.class || !outputValue.isRecord()) {
            throw failure(method,
                    ".etag(...) needs a single output, but the output type is " + outputType.getSimpleName(),
                    "Return a record or an Optional of one, or remove .etag(...).");
        }
        ResolvedComponent component = etags.getFirst();
        if (component.recordType() != outputValue) {
            throw failure(method,
                    "the component " + describe(component) + " of .etag(...) is not part of the output type "
                            + outputValue.getSimpleName(),
                    "Reference a component of " + outputValue.getSimpleName() + ", such as its version.");
        }
        if (!VersionTokens.supports(component.type())) {
            // The version types of core's version token codec, as REST's .etag takes them.
            throw failure(method,
                    "the etag needs a version type, but " + describe(component) + " is "
                            + component.genericType().getTypeName(),
                    "Use a component of a version type for the etag: " + VersionTokens.TYPES + ".");
        }
    }

    private static boolean isOperation(MethodDeclaration method, Class<?> kind) {
        return kind.isAssignableFrom(OperationTypes.userClassOf(method.operation()));
    }

    private static void claim(MethodDeclaration method, Map<String, String> sources, ResolvedComponent component,
            String source) {
        String previous = sources.putIfAbsent(component.recordType().getName() + "#" + component.name(), source);
        if (previous != null) {
            throw failure(method, describe(component) + " has more than one source: " + previous + " and " + source,
                    "Declare one source for each component.");
        }
    }

    private static String describe(ResolvedComponent component) {
        return component.recordType().getSimpleName() + "." + component.name();
    }

    private static TypeTree walk(MethodDeclaration method, String side, Class<?> named, ResolvableType root) {
        TypeTree tree = TypeTree.of(root);
        String overflow = tree.overflow;
        if (overflow != null) {
            throw failure(method,
                    "the " + side + " type " + named.getSimpleName() + " is too deeply nested: " + overflow,
                    "Flatten the operation's input and output types.");
        }
        return tree;
    }

    /**
     * The types reachable from an input or output type: the type itself, the components of records, the type
     * arguments of {@code Optional}, collections and maps, and array elements. {@code PageQuery} and
     * {@code PageResult} are listed but not walked into. The walk is bounded in depth and in visits, so a generic
     * record that grows with each level can't hang startup; a type that exceeds either bound fails the check.
     */
    private static final class TypeTree {

        /** Every type reached, records included. */
        private final Set<Class<?>> types = new LinkedHashSet<>();

        /** The records reached. */
        private final Set<Class<?>> records = new LinkedHashSet<>();

        private final Set<String> visited = new HashSet<>();

        private int remaining = MAX_TYPE_VISITS;

        /** Which bound the walk exceeded, or {@code null} when the whole type was walked. */
        private @Nullable String overflow;

        static TypeTree of(ResolvableType root) {
            TypeTree tree = new TypeTree();
            tree.walk(root, 0);
            return tree;
        }

        private void walk(ResolvableType type, int depth) {
            Class<?> raw = type.resolve();
            if (overflow != null || raw == null) {
                return;
            }
            if (depth > MAX_TYPE_DEPTH) {
                overflow = "more than " + MAX_TYPE_DEPTH + " levels of records and containers";
                return;
            }
            if (!visited.add(type.toString())) {
                return;
            }
            if (remaining <= 0) {
                overflow = "more than " + MAX_TYPE_VISITS + " nested types";
                return;
            }
            remaining--;
            types.add(raw);
            if (raw == PageQuery.class || raw == PageResult.class) {
                return;
            }
            if (raw.isRecord()) {
                records.add(raw);
                for (RecordComponent component : raw.getRecordComponents()) {
                    walk(ResolvableType.forType(component.getGenericType(), type), depth + 1);
                }
            } else if (raw.isArray()) {
                walk(type.getComponentType(), depth + 1);
            } else if (raw == Optional.class || Collection.class.isAssignableFrom(raw)
                    || Map.class.isAssignableFrom(raw)) {
                for (ResolvableType argument : type.getGenerics()) {
                    walk(argument, depth + 1);
                }
            }
        }
    }
}
