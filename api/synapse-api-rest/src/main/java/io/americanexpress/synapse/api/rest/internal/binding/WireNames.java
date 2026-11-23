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
package io.americanexpress.synapse.api.rest.internal.binding;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.paging.PageQuery;

/**
 * Renames a route's violation fields to the names the client sent (BUILD 6.2). Core names a violation by the path of
 * the rejected value in Java names: property names joined with dots, {@code [index]} for a list or array item, and
 * never a map key ({@code ViolationPaths}). REST answers with:
 * <ul>
 * <li>a body property's JSON name as the mapper reads it, at every level: the naming strategy, a per-class
 * {@code @JsonNaming} and {@code @JsonProperty} apply, aliases don't; a list or array item keeps its index, a
 * {@code Set} item has none, an {@code Optional} adds nothing, and a map value continues with the value's
 * property;</li>
 * <li>a component bound from a query parameter, a header, a cookie or a path variable by the name the binding declares,
 * with its item indexes; what follows is cut, since the value is text on the wire;</li>
 * <li>a component of a record bound from the query string by its own name, which is its parameter;</li>
 * <li>a path under a {@code PageQuery} by the paging parameter it is read from;</li>
 * <li>a path under a whole-body component ({@code .body(...)}) from the body, its JSON root: {@code changes.title} is
 * {@code title}, and the component itself keeps its name;</li>
 * <li>a {@code .context} or {@code .from} component by its name, and the path cut there.</li>
 * </ul>
 * A segment Jackson can't name (a type with its own deserializer, a delegating creator or a builder, or a property it
 * doesn't read) cuts the path there, so the field names the deepest part the client sent. A path whose first segment
 * no component has (a hand-built violation such as {@code If-Match}) is returned unchanged. The result holds only
 * names from the binding and Jackson and item indexes, so no part of the caller's data is echoed.
 */
public final class WireNames {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    /**
     * Each mapper's names per type. A {@link Namer} holds no reference to its mapper, so a mapper that is gone takes
     * its names with it.
     */
    private static final Map<JsonMapper, Namer> NAMERS = Collections.synchronizedMap(new WeakHashMap<>());

    private final Map<String, Root> roots;

    private WireNames(Map<String, Root> roots) {
        this.roots = roots;
    }

    /**
     * Returns the route's names: each component's declared source name (query parameter, header, cookie, path
     * variable), a body component's JSON name as Jackson reads it, the paging parameter names for a
     * {@code PageQuery} component, and the component name for {@code .context}, {@code .from} and a whole-body
     * component.
     *
     * @param route the resolved route
     * @param bodyMapper the mapper that reads the route's body
     * @param paging the paging parameter names
     * @return the route's names
     */
    public static WireNames of(RouteDefinition route, JsonMapper bodyMapper, PagingSettings paging) {
        Naming namer = naming(bodyMapper);
        Map<String, String> pageParameters = pageParameters(paging);
        Map<String, Root> roots = new HashMap<>();
        if (route.inputType() == PageQuery.class) {
            // The whole input is read from the paging parameters.
            pageParameters.forEach((component, parameter) -> roots.put(component, (path, at) -> parameter));
        }
        for (InputSource source : route.sources()) {
            ResolvedComponent component = source.component();
            String name = component.name();
            Root root = switch (source) {
                case InputSource.Path path -> source(path.variable());
                case InputSource.Query query -> source(query.parameter());
                case InputSource.Header header -> source(header.name());
                case InputSource.Cookie cookie -> source(cookie.name());
                case InputSource.QueryRecord query -> queryRecord(name, query.components());
                case InputSource.Body body -> bodyProperty(namer, route.inputType(), component);
                case InputSource.BodyComponent body -> wholeBody(namer, component);
                case InputSource.Page page -> paged(name, pageParameters);
                case InputSource.Context context -> (path, at) -> name;
                case InputSource.Custom custom -> (path, at) -> name;
            };
            roots.put(name, root);
        }
        return new WireNames(Map.copyOf(roots));
    }

    /**
     * Returns a core path in wire names. A first segment no component has is returned unchanged; a segment Jackson
     * can't name cuts the path there.
     *
     * @param path a violation field in core's format
     * @return the field in wire names
     */
    public String rename(String path) {
        int end = segmentEnd(path, 0);
        Root root = roots.get(path.substring(0, end));
        return root == null ? path : root.rename(path, end);
    }

    /**
     * Returns the violations with their fields in wire names, in the same order.
     *
     * @param violations violations whose fields are in core's format
     * @return the renamed violations
     */
    public List<Violation> rename(List<Violation> violations) {
        List<Violation> renamed = new ArrayList<>(violations.size());
        for (Violation violation : violations) {
            renamed.add(new Violation(rename(violation.field()), violation.message()));
        }
        return List.copyOf(renamed);
    }

    /**
     * Returns a body path in Spring's form from its root type in JSON names, for a controller's {@code @RequestBody}
     * when no constraint violation stands behind the field: {@code [n]} on a list or array is kept, and a {@code Set}
     * item's empty {@code []} is dropped. Spring writes a map key as it is, and a key may hold anything, path-like text
     * included, so the path is cut at the first map: no part of a key is ever read as path. A first segment the type
     * doesn't read is returned up to its first bracket.
     *
     * @param mapper the mapper that read the body
     * @param rootType the body's type
     * @param path the field, in Spring's form
     * @return the field in JSON names
     */
    public static String body(JsonMapper mapper, Class<?> rootType, String path) {
        return body(mapper, rootType, path, true);
    }

    /**
     * Returns a body path from its root type in JSON names, as {@link #body(JsonMapper, Class, String)} does.
     *
     * @param mapper the mapper that read the body
     * @param rootType the body's type
     * @param path the field
     * @param keys {@code true} for Spring's form, where a bracket after a map holds its key and cuts the path;
     *        {@code false} for core's ({@code ViolationPaths}), which has no keys, so a bracket after a map of lists is
     *        the value's item index
     * @return the field in JSON names
     */
    public static String body(JsonMapper mapper, Class<?> rootType, String path, boolean keys) {
        Naming namer = naming(mapper);
        JavaType root = mapper.constructType(rootType);
        if (path.isEmpty()) {
            return path;
        }
        if (path.charAt(0) == '[') {
            // A list or map body: its items have no property name.
            return withoutLeadingDot(new Walk(namer, path, keys).lenient(root, 0));
        }
        int end = segmentEnd(path, 0);
        String first = path.substring(0, end);
        String json = namer.names(root.getRawClass()).get(first);
        JavaType type = json == null ? null : namer.propertyType(root, first);
        if (json == null || type == null) {
            int bracket = path.indexOf('[');
            return bracket < 0 ? path : path.substring(0, bracket);
        }
        return json + new Walk(namer, path, keys).lenient(type, end);
    }

    private static Naming naming(JsonMapper mapper) {
        return new Naming(mapper, NAMERS.computeIfAbsent(mapper, ignored -> new Namer()));
    }

    /** The paging parameter of each {@code PageQuery} component. */
    private static Map<String, String> pageParameters(PagingSettings paging) {
        return Map
                .of("cursor", paging.cursor(), "offset", paging.offset(), "size", paging.size(), "sort", paging.sort());
    }

    /** A value read from text: the declared name and its item indexes; what follows is cut. */
    private static Root source(String declared) {
        return (path, at) -> declared + indexes(path, at);
    }

    /** A record bound from the query string: each of its components is the parameter of the same name. */
    private static Root queryRecord(String name, List<ResolvedComponent> components) {
        Set<String> parameters = components.stream().map(ResolvedComponent::name).collect(Collectors.toSet());
        return (path, at) -> {
            if (at < path.length() && path.charAt(at) == '.') {
                int end = segmentEnd(path, at + 1);
                String parameter = path.substring(at + 1, end);
                if (parameters.contains(parameter)) {
                    return parameter + indexes(path, end);
                }
            }
            return name;
        };
    }

    /** A {@code PageQuery} component: a path under it is the parameter its component is read from. */
    private static Root paged(String name, Map<String, String> parameters) {
        return (path, at) -> {
            if (at < path.length() && path.charAt(at) == '.') {
                String parameter = parameters.get(path.substring(at + 1, segmentEnd(path, at + 1)));
                if (parameter != null) {
                    return parameter;
                }
            }
            return name;
        };
    }

    /** A component filled from a body property: its JSON name, then the path below it in JSON names. */
    private static Root bodyProperty(Naming namer, Class<?> inputType, ResolvedComponent component) {
        String json = namer.names(inputType).get(component.name());
        if (json == null) {
            // The input is read whole (its own deserializer, a delegating creator) or never reads this component.
            String name = component.name();
            return (path, at) -> name;
        }
        JavaType type = namer.mapper().constructType(component.genericType());
        return (path, at) -> json + new Walk(namer, path, false).lenient(type, at);
    }

    /** A component bound to the whole body: a path under it starts at the body, the JSON root. */
    private static Root wholeBody(Naming namer, ResolvedComponent component) {
        String name = component.name();
        JavaType type = namer.mapper().constructType(component.genericType());
        return (path, at) -> {
            String below = withoutLeadingDot(new Walk(namer, path, false).lenient(type, at));
            return below.isEmpty() ? name : below;
        };
    }

    private static String withoutLeadingDot(String path) {
        return path.startsWith(".") ? path.substring(1) : path;
    }

    /** The {@code [n]} items that follow {@code at}, up to the first part that is not one. */
    private static String indexes(String path, int at) {
        StringBuilder items = new StringBuilder();
        int from = at;
        while (from < path.length() && path.charAt(from) == '[') {
            int close = path.indexOf(']', from);
            if (close < 0 || !isIndex(path.substring(from + 1, close))) {
                break;
            }
            items.append(path, from, close + 1);
            from = close + 1;
        }
        return items.toString();
    }

    /** The end of the name that starts at {@code from}: the next {@code .} or {@code [}, or the end. */
    private static int segmentEnd(String path, int from) {
        int end = from;
        while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
            end++;
        }
        return end;
    }

    private static boolean isIndex(String text) {
        return !text.isEmpty() && text.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** Renames a path from the end of its first segment ({@code at}). */
    @FunctionalInterface
    private interface Root {

        String rename(String path, int at);
    }

    /**
     * One mapper's JSON names, per type. It holds only names, never the mapper, so the mapper stays collectable while
     * it is a key of {@link #NAMERS}.
     */
    private static final class Namer {

        private final Map<Class<?>, Map<String, String>> names = new ConcurrentHashMap<>();

        /** The JSON names of the properties the mapper reads for a type; empty when it doesn't read it by property. */
        Map<String, String> names(JsonMapper mapper, Class<?> type) {
            Map<String, String> known = names.get(type);
            if (known == null) {
                known = read(mapper, type);
                names.putIfAbsent(type, known);
            }
            return known;
        }

        private static Map<String, String> read(JsonMapper mapper, Class<?> type) {
            try {
                return JsonNames.readsByProperty(mapper, type) ? JsonNames.read(mapper, type) : Map.of();
            } catch (RuntimeException e) {
                // Naming must never fail the error response: a type Jackson can't describe has no names.
                LOG.debug("No JSON names for {}", type.getName(), e);
                return Map.of();
            }
        }

        /** The type of an owner's property, its generics resolved; {@code null} when there is none. */
        static @Nullable JavaType propertyType(JsonMapper mapper, JavaType owner, String property) {
            Class<?> raw = owner.getRawClass();
            if (raw.isRecord()) {
                for (RecordComponent component : Objects.requireNonNull(raw.getRecordComponents())) {
                    if (component.getName().equals(property)) {
                        return mapper
                                .getTypeFactory()
                                .resolveMemberType(component.getGenericType(), owner.getBindings());
                    }
                }
                return null;
            }
            try {
                for (BeanPropertyDefinition definition : JsonNames.deserializationProperties(mapper, owner)) {
                    if (definition.getInternalName().equals(property)) {
                        return definition.getPrimaryType();
                    }
                }
            } catch (RuntimeException e) {
                LOG.debug("No property {} on {}", property, raw.getName(), e);
            }
            return null;
        }
    }

    /** A mapper and its names: what a walk reads with. Only routes and walks hold it, never {@link #NAMERS}. */
    private record Naming(JsonMapper mapper, Namer cache) {

        Map<String, String> names(Class<?> type) {
            return cache.names(mapper, type);
        }

        @Nullable
        JavaType propertyType(JavaType owner, String property) {
            return Namer.propertyType(mapper, owner, property);
        }
    }

    /** Reads one path below a type, left to right. */
    private static final class Walk {

        private final Naming namer;

        private final String path;

        /** Whether a bracket after a map holds its key, as Spring writes it; core writes no key. */
        private final boolean keys;

        Walk(Naming namer, String path, boolean keys) {
            this.namer = namer;
            this.path = path;
            this.keys = keys;
        }

        /** The rest of the path from {@code at}, in JSON names, cut where a part can't be named. */
        String lenient(JavaType type, int at) {
            StringBuilder out = new StringBuilder();
            walk(type, at, out);
            return out.toString();
        }

        /**
         * Appends the rest of the path from {@code at} to {@code out}, in JSON names, up to the first part that can't
         * be named. Returns whether every part was named.
         */
        private boolean walk(JavaType start, int from, StringBuilder out) {
            JavaType type = start;
            int at = from;
            while (at < path.length()) {
                type = unwrapped(type);
                char next = path.charAt(at);
                if (next == '.') {
                    // A dot after a list, set, array or map continues into its item or value.
                    type = element(type);
                    int end = segmentEnd(path, at + 1);
                    String name = path.substring(at + 1, end);
                    String json = namer.names(type.getRawClass()).get(name);
                    JavaType property = json == null ? null : namer.propertyType(type, name);
                    if (json == null || property == null) {
                        return false;
                    }
                    out.append('.').append(json);
                    type = property;
                    at = end;
                    continue;
                }
                if (next != '[') {
                    return false;
                }
                if (type.isMapLikeType()) {
                    if (keys) {
                        // Spring writes the key as it is, and its end can't be told from path: cut at the map.
                        return false;
                    }
                    // Core writes no key: a bracket after a map is the item index of a list value.
                    type = unwrapped(type.getContentType());
                }
                int close = path.indexOf(']', at);
                if (!(type.isCollectionLikeType() || type.isArrayType()) || close < 0) {
                    return false;
                }
                String index = path.substring(at + 1, close);
                if (isIndex(index)) {
                    out.append('[').append(index).append(']');
                } else if (!index.isEmpty()) {
                    return false;
                }
                // Spring writes [] for a Set item, which has no index.
                type = type.getContentType();
                at = close + 1;
            }
            return true;
        }

        private static JavaType unwrapped(JavaType type) {
            JavaType current = type;
            while (current.isReferenceType()) {
                current = current.getReferencedType();
            }
            return current;
        }

        /** The item or value type of a list, set, array or map, through any nesting; the type itself otherwise. */
        private static JavaType element(JavaType type) {
            JavaType current = unwrapped(type);
            while (current.isContainerType()) {
                current = unwrapped(current.getContentType());
            }
            return current;
        }
    }
}
