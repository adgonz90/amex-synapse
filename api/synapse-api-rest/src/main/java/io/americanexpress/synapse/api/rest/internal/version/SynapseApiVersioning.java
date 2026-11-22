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
package io.americanexpress.synapse.api.rest.internal.version;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.server.RequestPath;
import org.springframework.web.accept.PathApiVersionResolver;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RestBindingsAccess;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * Turns Spring's API versioning on when a route is versioned, so its version is optional and is read only from the
 * paths of versioned routes. With no versioned route it does nothing, and Spring's versioning stays off. Otherwise it:
 * <ul>
 * <li>checks each versioned route's {@code .version(...)} as the binding validator does, with the same message, since
 * Spring MVC may be configured before the routes are validated;</li>
 * <li>fails when {@value #PATH_SEGMENT} is set: Boot's path-segment resolver would parse every path, unversioned
 * routes included;</li>
 * <li>adds a {@link PathApiVersionResolver} on the first path segment, for the paths that match a versioned route's
 * template (parsed as {@code RequestPredicates.path} parses it), so {@code /v2/books/1} names version 2 and
 * {@code /books/1} names none;</li>
 * <li>makes the version optional, so unversioned routes and controllers keep working;</li>
 * <li>adds the declared versions as supported.</li>
 * </ul>
 * It runs before Boot's Spring MVC configuration, which applies {@code spring.mvc.apiversion.*} only when set, so a Boot
 * setting wins: {@code required=true}, for example, then applies to every route.
 */
public final class SynapseApiVersioning implements WebMvcConfigurer, Ordered {

    /** Boot's path-segment resolver property, which can't be combined with versioned routes. */
    static final String PATH_SEGMENT = "spring.mvc.apiversion.use.path-segment";

    private final ObjectProvider<RestBindings> bindings;

    private final Environment environment;

    /**
     * Creates the configuration.
     *
     * @param bindings the bindings, read when Spring MVC configures API versioning
     * @param environment the environment, for {@value #PATH_SEGMENT}
     */
    public SynapseApiVersioning(ObjectProvider<RestBindings> bindings, Environment environment) {
        this.bindings = bindings;
        this.environment = environment;
    }

    /**
     * Configures API versioning for the versioned routes, or leaves the configurer untouched when there are none.
     *
     * @param configurer Spring MVC's API version configurer
     * @throws SynapseConfigurationException when a route's version is malformed, or {@value #PATH_SEGMENT} is set
     *         while a route is versioned
     */
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        List<RouteDefinition> versioned = bindings
                .orderedStream()
                .flatMap(each -> RestBindingsAccess.routes(each).stream())
                .filter(route -> route.version() != null)
                .toList();
        if (versioned.isEmpty()) {
            return;
        }
        versioned.forEach(BindingValidator::checkVersion);
        if (Binder.get(environment).bind(PATH_SEGMENT, Integer.class).isBound()) {
            throw new SynapseConfigurationException(
                    PATH_SEGMENT + " is set while REST routes are versioned ("
                            + versioned.stream().map(SynapseApiVersioning::describe).collect(Collectors.joining(", "))
                            + ")",
                    "Remove " + PATH_SEGMENT + ". Synapse puts a route's version in its first path segment and "
                            + "parses only those paths; Boot's path-segment resolver would parse every path, "
                            + "unversioned routes included.");
        }
        List<PathPattern> templates = templates(versioned);
        Set<String> versions = new LinkedHashSet<>();
        versioned.forEach(route -> versions.add(Objects.requireNonNull(route.version())));
        configurer
                .useVersionResolver(new PathApiVersionResolver(0, path -> isVersioned(templates, path)))
                .setVersionRequired(false)
                .addSupportedVersions(versions.toArray(String[]::new));
    }

    /**
     * Runs before Boot's Spring MVC configuration, whose {@code spring.mvc.apiversion.*} settings then win.
     *
     * @return {@link Ordered#HIGHEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** The served path templates of the versioned routes, parsed as {@code RequestPredicates.path} parses them. */
    private static List<PathPattern> templates(List<RouteDefinition> versioned) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        Set<String> paths = new LinkedHashSet<>();
        versioned.forEach(route -> paths.add(route.path()));
        List<PathPattern> templates = new ArrayList<>(paths.size());
        paths.forEach(path -> templates.add(parser.parse(parser.initFullPathPattern(path))));
        return List.copyOf(templates);
    }

    private static boolean isVersioned(List<PathPattern> templates, RequestPath path) {
        return templates.stream().anyMatch(template -> template.matches(path.pathWithinApplication()));
    }

    private static String describe(RouteDefinition route) {
        return route.method().name() + " " + route.path() + " (" + route.operationName() + ")";
    }
}
