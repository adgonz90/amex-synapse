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
package io.americanexpress.synapse.api.rest.internal.route;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.web.servlet.function.RouterFunction;

import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;

/**
 * Logs every operation the REST plugin exposes once the web server is initialized and its port is known, one INFO
 * line per route on {@code io.americanexpress.synapse.api.rest}:
 * {@code Synapse REST exposes GET /books/{id} -> get-book on port 8080}. The routes are read from the router
 * functions in the context, so a replaced routes bean is reported as it is. A separate management server is not
 * reported.
 */
public final class ExposureInventory implements ApplicationListener<ServletWebServerInitializedEvent> {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    private final ObjectProvider<RouterFunction<?>> routerFunctions;

    /**
     * Creates the inventory.
     *
     * @param routerFunctions the router functions of the application
     */
    public ExposureInventory(ObjectProvider<RouterFunction<?>> routerFunctions) {
        this.routerFunctions = routerFunctions;
    }

    @Override
    public void onApplicationEvent(ServletWebServerInitializedEvent event) {
        if (event.getApplicationContext().getServerNamespace() != null) {
            // A child context's server, such as the management server.
            return;
        }
        int port = event.getWebServer().getPort();
        for (RouteDefinition route : routes()) {
            LOG
                    .info("Synapse REST exposes {} {} -> {} on port {}", route.method().name(), route.path(),
                            route.operationName(), port);
        }
    }

    /**
     * Returns the Synapse routes of every router function, in order.
     *
     * @return the routes
     */
    List<RouteDefinition> routes() {
        return OperationRoutes.of(routerFunctions.orderedStream().toList());
    }
}
