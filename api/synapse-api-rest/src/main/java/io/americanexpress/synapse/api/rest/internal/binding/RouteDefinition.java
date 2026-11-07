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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;

import io.americanexpress.synapse.core.internal.binding.OperationTypes;
import io.americanexpress.synapse.core.operation.OperationNames;
import io.americanexpress.synapse.core.operation.ServiceOperation;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * One REST route. {@code RestBindings.routes()} returns routes as declared: {@link #sources()} is empty and the
 * {@link #response()} has no output headers or body component yet. {@link BindingValidator#validate} returns the
 * resolved routes, with a source for every input component that has one and the convention header rules applied;
 * request binding and response writing read only resolved routes.
 *
 * <p>
 * A route declared with {@code .version(v)} is served at {@code /v<v>} followed by its declared path ({@code /v<v>}
 * alone for {@code /}). {@link #path()} is that served path, which every route check and the routing use;
 * {@link #declaredPath()} is the path as declared.
 *
 * @param method the HTTP method
 * @param path the served path template: the declared path, prefixed with {@code /v<version>} on a versioned route
 * @param operation the operation bean (possibly a proxy)
 * @param operationName the operation name
 * @param inputType the operation's input type {@code I}
 * @param outputType the operation's raw output type {@code O} (for example {@code Optional})
 * @param sources the input sources in component order (empty until resolved)
 * @param response the response rules
 * @param declarations the component calls made on the route, in declaration order
 * @param paging how the route reads {@code PageQuery} from the paging parameters, or {@code null} when the route
 *        doesn't bind {@code PageQuery} (always {@code null} until resolved)
 * @param conditional the conditional request rules ({@link ConditionalRules#NONE} until resolved)
 * @param version the route's version as declared by the first {@code .version(...)}, or {@code null} for an
 *        unversioned route
 */
public record RouteDefinition(HttpMethod method, String path, ServiceOperation<?, ?> operation, String operationName,
        Class<?> inputType, Class<?> outputType, List<InputSource> sources, ResponseRules response,
        List<RouteDeclaration> declarations, @Nullable PagingRules paging, ConditionalRules conditional,
        @Nullable String version) {

    /** A template variable, {@code {name}} or a catch-all {@code {*name}}: group 1 is the name. */
    private static final Pattern VARIABLE = Pattern.compile("\\{\\*?([A-Za-z_$][\\w$]*)");

    /**
     * Creates the definition.
     *
     * @param method the method
     * @param path the path template
     * @param operation the operation
     * @param operationName the operation name
     * @param inputType the input type
     * @param outputType the output type
     * @param sources the input sources
     * @param response the response rules
     * @param declarations the declarations
     * @param paging the paging rules, or {@code null}
     * @param conditional the conditional request rules
     * @param version the version, or {@code null}
     */
    public RouteDefinition {
        sources = List.copyOf(sources);
        declarations = List.copyOf(declarations);
    }

    /**
     * Creates a route as declared, resolving the operation's name and its input and output types from the operation
     * class (through a CGLIB or Spring AOP proxy). The version is the first {@link RouteDeclaration.Version}'s, and
     * the path is the served path (see {@link #path()}).
     *
     * @param method the method
     * @param path the path template as declared
     * @param operation the operation
     * @param declarations the component calls, in order
     * @param response the response rules as declared
     * @return the route
     */
    public static RouteDefinition declared(HttpMethod method, String path, ServiceOperation<?, ?> operation,
            List<RouteDeclaration> declarations, ResponseRules response) {
        Class<?> operationClass = OperationTypes.userClassOf(operation);
        String version = declarations
                .stream()
                .filter(RouteDeclaration.Version.class::isInstance)
                .map(declaration -> ((RouteDeclaration.Version) declaration).version())
                .findFirst()
                .orElse(null);
        return new RouteDefinition(method, servedPath(path, version), operation, OperationNames.nameOf(operationClass),
                generic(operationClass, 0).resolve(Object.class), generic(operationClass, 1).resolve(Object.class),
                List.of(), response, declarations, null, ConditionalRules.NONE, version);
    }

    /**
     * Returns the path as declared, without the {@code /v<version>} prefix of a versioned route ({@code /} for a
     * route declared on {@code /}). A versioned route's declared path always starts with {@code /}.
     *
     * @return the declared path template
     */
    public String declaredPath() {
        if (version == null) {
            return path;
        }
        String declared = path.substring(prefix(version).length());
        return declared.isEmpty() ? "/" : declared;
    }

    /**
     * Returns the served path: {@code /v<version>} followed by the declared path, or the declared path when the route
     * has no version. A declared path without its leading {@code /} gets one, as Spring's routing adds it.
     */
    private static String servedPath(String path, @Nullable String version) {
        if (version == null) {
            return path;
        }
        if (path.isEmpty() || "/".equals(path)) {
            return prefix(version);
        }
        return prefix(version) + (path.startsWith("/") ? path : "/" + path);
    }

    private static String prefix(String version) {
        return "/v" + version;
    }

    /**
     * Returns the output type unwrapped from {@code Optional}, {@code List} or {@code PageResult}: the type whose
     * components output headers and the body component name (for a list or a page, the item type).
     *
     * @return the unwrapped output type
     */
    public Class<?> outputValueType() {
        return unwrap(generic(OperationTypes.userClassOf(operation), 1)).resolve(Object.class);
    }

    /**
     * Tells whether the operation returns a {@code PageResult}, which is written as a page.
     *
     * @return true when the raw output type is {@code PageResult}
     */
    public boolean pagedOutput() {
        return outputType == PageResult.class;
    }

    /**
     * Returns the names of the path template's variables, in template order ({@code /shelves/{shelf}/books/{id}} gives
     * {@code shelf}, {@code id}).
     *
     * @return the variable names
     */
    public List<String> pathVariables() {
        List<String> variables = new ArrayList<>();
        Matcher matcher = VARIABLE.matcher(path);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return List.copyOf(variables);
    }

    /**
     * Returns these resolved sources and output rules applied to this route, which keeps its path and version.
     *
     * @param resolvedSources the sources
     * @param resolvedResponse the response rules
     * @param resolvedPaging the paging rules, or {@code null} when the route doesn't bind {@code PageQuery}
     * @param resolvedConditional the conditional request rules
     * @return the resolved route
     */
    RouteDefinition resolved(List<InputSource> resolvedSources, ResponseRules resolvedResponse,
            @Nullable PagingRules resolvedPaging, ConditionalRules resolvedConditional) {
        return new RouteDefinition(method, path, operation, operationName, inputType, outputType, resolvedSources,
                resolvedResponse, declarations, resolvedPaging, resolvedConditional, version);
    }

    static ResolvableType generic(Class<?> operationClass, int index) {
        return ResolvableType.forClass(operationClass).as(ServiceOperation.class).getGeneric(index);
    }

    static ResolvableType unwrap(ResolvableType type) {
        Class<?> raw = type.resolve(Object.class);
        if (raw == Optional.class || raw == List.class || raw == PageResult.class) {
            return type.getGeneric(0);
        }
        return type;
    }
}
