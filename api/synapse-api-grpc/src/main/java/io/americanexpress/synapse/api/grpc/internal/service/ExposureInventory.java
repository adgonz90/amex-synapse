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
package io.americanexpress.synapse.api.grpc.internal.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationListener;
import org.springframework.grpc.server.lifecycle.GrpcServerStartedEvent;

import io.americanexpress.synapse.api.grpc.GrpcBindings;

/**
 * Logs every method the gRPC plugin binds once the gRPC server has started, one INFO line per method on
 * {@code io.americanexpress.synapse.api.grpc}:
 * {@code Synapse gRPC exposes synapse.samples.books.v1.BookService/GetBook -> get-book on port 9090}, or
 * {@code in process} instead of {@code on port <n>} for a server without a port. Hand-written services are not
 * listed.
 */
public final class ExposureInventory implements ApplicationListener<GrpcServerStartedEvent> {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.grpc");

    private final ObjectProvider<GrpcBindings> bindings;

    /**
     * Creates the inventory.
     *
     * @param bindings the bindings of the application
     */
    public ExposureInventory(ObjectProvider<GrpcBindings> bindings) {
        this.bindings = bindings;
    }

    @Override
    public void onApplicationEvent(GrpcServerStartedEvent event) {
        int port = event.getPort();
        String where = port < 0 ? "in process" : "on port " + port;
        bindings.orderedStream().forEach(service -> {
            for (Map.Entry<String, String> method : ServiceCompiler
                    .operationNames(GrpcBindingsAccess.declaration(service))
                    .entrySet()) {
                LOG.info("Synapse gRPC exposes {} -> {} {}", method.getKey(), method.getValue(), where);
            }
        });
    }
}
