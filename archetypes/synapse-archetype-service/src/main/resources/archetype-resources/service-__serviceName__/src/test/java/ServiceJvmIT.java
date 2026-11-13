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
package ${package};

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;

import ${package}.scenario.ItemScenarios;
import ${package}.scenario.Protocol;
import ${package}.scenario.Target;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parity scenarios against the whole service in the test context: Tomcat and Netty on free ports, the
 * ${serviceName} store on a PostgreSQL container (Docker), migrated by its Flyway at startup. Error detail is off, as
 * in every environment but local, so the scenarios see the answers a deployed service gives; the slice tests turn it
 * on in {@code application-test.yaml}. Readiness, the API docs' default and the audit user are in
 * {@code ServiceOperationalIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.grpc.server.port=0",
        "synapse.errors.include-detail=false"})
@ActiveProfiles("test")
class ServiceJvmIT extends ItemScenarios {

    @StoreConnection("${serviceName}")
    static final PostgreSQLContainer STORE = SynapseContainers.postgres();

    /** The ${serviceName} store's database. */
    @Autowired
    @Qualifier("${serviceName}")
    JdbcClient jdbc;

    @LocalServerPort
    int restPort;

    @LocalGrpcServerPort
    int grpcPort;

    @Override
    protected Target target() {
        return new Target(URI.create("http://localhost:" + restPort), "localhost", grpcPort);
    }

    /** The row an item added over gRPC wrote: its description, and version 0. */
    @Test
    void createWritesTheRow() {
        UUID id = idOf(client(Protocol.GRPC).create(name(), "In the table"));

        assertThat(jdbc.sql("SELECT description FROM item WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("In the table");
        assertThat(jdbc.sql("SELECT version FROM item WHERE id = ?").param(id).query(Long.class).single()).isZero();
    }
}
