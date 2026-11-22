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
package ${package}.scenario;

import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;

import ${package}.contract.ItemErrors;
import ${package}.scenario.Outcome.ItemView;
import ${package}.scenario.Outcome.Kind;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The items' contract, the same over both protocols: every scenario runs over REST and over gRPC and expects the same
 * {@link Outcome}, and the cross-protocol scenario reads over one protocol an item added over the other. A subclass
 * says where the service runs ({@link #target()}): a test context, a packaged jar, an image.
 *
 * <p>
 * The scenarios share the service's database, so each one works on the items it adds, each with a name of its own.
 */
public abstract class ItemScenarios {

    private RestItemClient rest;

    private GrpcItemClient grpc;

    /**
     * Returns where the service under test answers.
     *
     * @return the target
     */
    protected abstract Target target();

    @BeforeEach
    void connect() {
        Target target = target();
        rest = new RestItemClient(target.rest());
        grpc = new GrpcItemClient(target.grpcHost(), target.grpcPort());
    }

    @AfterEach
    void disconnect() {
        rest.close();
        grpc.close();
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void createReturnsTheItemAndItsEtag(Protocol protocol) {
        String name = name();

        Outcome created = client(protocol).create(name, "A desk lamp");

        // REST answers 201 with a Location; gRPC an Item. Both carry the new item's entity tag.
        assertThat(created).isEqualTo(Outcome.created(new ItemView(idOf(created), name, "A desk lamp", 0), "\"0\""));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void getReturnsTheSameItemAndEtag(Protocol protocol) {
        Outcome created = client(protocol).create(name(), null);

        // No description sent: REST's null, gRPC's unset wrapper.
        assertThat(Objects.requireNonNull(created.item()).description()).isNull();
        assertThat(client(protocol).get(idOf(created)))
                .isEqualTo(Outcome.ok(Objects.requireNonNull(created.item()), "\"0\""));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void duplicateNameIsConflictAlreadyExists(Protocol protocol) {
        String name = name();
        client(protocol).create(name, null);

        Outcome duplicate = client(protocol).create(name, "Another");

        // The table's unique constraint, translated by Synapse: REST 409, gRPC ALREADY_EXISTS.
        assertThat(duplicate).isEqualTo(Outcome.failed(SynapseErrors.ALREADY_EXISTS));
        assertThat(duplicate.category()).isEqualTo(ErrorCategory.CONFLICT);
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void blankNameIsInvalidInputOnName(Protocol protocol) {
        Outcome invalid = client(protocol).create(" ", null);

        assertThat(invalid).isEqualTo(Outcome.failed(SynapseErrors.VALIDATION_FAILED, "name"));
        assertThat(invalid.category()).isEqualTo(ErrorCategory.INVALID_INPUT);
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void unknownItemIsNotFound(Protocol protocol) {
        assertThat(client(protocol).get(UUID.randomUUID())).isEqualTo(Outcome.failed(ItemErrors.ITEM_NOT_FOUND));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void correlationIdIsEchoed(Protocol protocol) {
        Outcome created = client(protocol).create(name(), null);

        ItemClient.Echo read = client(protocol).echoedCorrelationId(idOf(created), "scenario-" + protocol);
        ItemClient.Echo missing = client(protocol)
                .echoedCorrelationId(UUID.randomUUID(), "scenario-missing-" + protocol);

        assertThat(read.outcome()).isEqualTo(Outcome.ok(Objects.requireNonNull(created.item()), "\"0\""));
        assertThat(read.correlationId()).isEqualTo("scenario-" + protocol);
        // A failure carries it too.
        assertThat(missing.outcome()).isEqualTo(Outcome.failed(ItemErrors.ITEM_NOT_FOUND));
        assertThat(missing.correlationId()).isEqualTo("scenario-missing-" + protocol);
    }

    /**
     * One operation behind both protocols: an item added over one is the same item over the other, with the same
     * entity tag. It is the smoke test: one fast check that both APIs serve the store, which every target runs.
     */
    @Test
    void sameOperationOverRestAndGrpc() {
        Outcome overRest = rest.create(name(), "Added over REST");
        Outcome overGrpc = grpc.create(name(), "Added over gRPC");

        assertThat(grpc.get(idOf(overRest))).isEqualTo(sameRead(overRest));
        assertThat(rest.get(idOf(overGrpc))).isEqualTo(sameRead(overGrpc));
    }

    /** The read of a created item: the same item, with the create's entity tag. */
    private static Outcome sameRead(Outcome created) {
        return Outcome.ok(Objects.requireNonNull(created.item()), Objects.requireNonNull(created.etag()));
    }

    /**
     * Returns the client of a protocol.
     *
     * @param protocol the protocol
     * @return its client
     */
    protected ItemClient client(Protocol protocol) {
        return switch (protocol) {
            case REST -> rest;
            case GRPC -> grpc;
        };
    }

    /**
     * Returns the id of the item an outcome carries.
     *
     * @param outcome a read or a create
     * @return the id
     */
    protected static UUID idOf(Outcome outcome) {
        assertThat(outcome.kind()).as("outcome of %s", outcome).isIn(Kind.OK, Kind.CREATED);
        return Objects.requireNonNull(outcome.item()).id();
    }

    /**
     * Returns a name no other scenario uses.
     *
     * @return the name
     */
    protected static String name() {
        return "Item " + UUID.randomUUID();
    }
}
