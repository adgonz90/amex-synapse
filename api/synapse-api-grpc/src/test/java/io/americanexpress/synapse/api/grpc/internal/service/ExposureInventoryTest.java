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

import io.grpc.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.lifecycle.GrpcServerStartedEvent;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class ExposureInventoryTest {

    private static final String SERVICE = "synapse.fixture.v1.CatalogService";

    private static ExposureInventory inventory() {
        GrpcBindings bindings = GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.GetItemOperation())
                .unary(CatalogServiceGrpc.getListTagsMethod(), new BindingFixtures.LoadItemOperation())
                .bindAllByName()
                .build();
        return new ExposureInventory(
                new StaticListableBeanFactory(Map.of("catalog", bindings)).getBeanProvider(GrpcBindings.class));
    }

    private static GrpcServerStartedEvent started(int port) {
        return new GrpcServerStartedEvent(mock(GrpcServerLifecycle.class), mock(Server.class), "localhost", port);
    }

    @Test
    void logsEveryBoundMethodWithItsOperationAndPort(CapturedOutput output) {
        inventory().onApplicationEvent(started(9090));

        assertThat(output)
                .contains("Synapse gRPC exposes " + SERVICE + "/GetItem -> get-item on port 9090")
                .contains("Synapse gRPC exposes " + SERVICE + "/CreateItem -> create-item on port 9090")
                .contains("Synapse gRPC exposes " + SERVICE + "/ListTags -> load-item on port 9090")
                .contains("Synapse gRPC exposes " + SERVICE + "/Fail -> fail on port 9090");
    }

    @Test
    void aServerWithoutAPortIsInProcess(CapturedOutput output) {
        inventory().onApplicationEvent(started(-1));

        assertThat(output)
                .contains("Synapse gRPC exposes " + SERVICE + "/Ping -> ping in process")
                .doesNotContain("on port -1");
    }

    @Test
    void methodsWithoutABindingAreNotListed() {
        GrpcBindings bindings = GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.GetItemOperation())
                .build();

        assertThat(ServiceCompiler.operationNames(GrpcBindingsAccess.declaration(bindings)))
                .containsExactly(Map.entry(SERVICE + "/GetItem", "get-item"));
    }
}
