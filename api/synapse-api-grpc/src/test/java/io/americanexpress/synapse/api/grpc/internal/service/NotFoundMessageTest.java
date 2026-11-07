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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.internal.binding.MethodDeclaration;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures;
import io.americanexpress.synapse.api.grpc.internal.edge.GrpcCallState;
import io.americanexpress.synapse.api.grpc.internal.error.GrpcErrorStatuses;
import io.americanexpress.synapse.api.grpc.internal.error.GrpcStatusWriter;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.SynapseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The description of an empty {@code Optional} over gRPC: the edge has no message arguments, so a declared
 * {@code .notFound} code whose template has placeholders is described with {@code NOT_FOUND}'s message, never with
 * its own template or {@code MessageSource} entry left unformatted. Its reason and status stay the declared code's.
 */
class NotFoundMessageTest {

    private static final byte[] GET_I1 = GetItemRequest.newBuilder().setId("i-1").build().toByteArray();

    private final StaticMessageSource messageSource = new StaticMessageSource();

    /** Not-found codes with and without placeholders. */
    enum ShelfErrors implements ErrorDetails {

        /** A placeholder: the edge can't fill it. */
        SHELF_NOT_FOUND("Shelf {0} was not found"),

        /** Two placeholders, the second one formatted. */
        SHELF_ITEM_NOT_FOUND("Item {1,number} on shelf {0} was not found"),

        /** No placeholder. */
        SHELF_EMPTY("The shelf has no such item"),

        /** Quoted braces are text, not a placeholder. */
        SHELF_QUOTED("Shelf '{0}' was not found"),

        /** Not a valid pattern; formatting would print it as written. */
        SHELF_BROKEN("Shelf {0 was not found");

        private final String messageTemplate;

        ShelfErrors(String messageTemplate) {
            this.messageTemplate = messageTemplate;
        }

        @Override
        public String code() {
            return name();
        }

        @Override
        public String messageTemplate() {
            return messageTemplate;
        }

        @Override
        public ErrorCategory category() {
            return ErrorCategory.NOT_FOUND;
        }
    }

    private static UnaryOperationHandler handler(ErrorDetails notFound) {
        MethodDeclaration method = MethodDeclaration
                .declared(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.GetItemOperation(), List.of(),
                        List.of(), notFound);
        return UnaryOperationHandler.create(method, GetItemRequest.getDescriptor(), Item.getDescriptor());
    }

    private static SynapseException notFound(ErrorDetails declared) {
        return catchThrowableOfType(SynapseException.class,
                () -> handler(declared).invoke(GET_I1, new IgnoringObserver()));
    }

    private GrpcStatusWriter writer(boolean includeDetail, GrpcErrorStatuses statuses) {
        return new GrpcStatusWriter(statuses, new ErrorMessages(messageSource),
                new SynapseErrorProperties(includeDetail, null), "shelves");
    }

    private GrpcStatusWriter writer(boolean includeDetail) {
        return writer(includeDetail, new GrpcErrorStatuses(Map.of(), Map.of()));
    }

    private static GrpcCallState state(String operationName, Locale locale) {
        GrpcCallState state = new GrpcCallState(null, Map.of());
        state.correlationId("c-1");
        state.operationName(operationName);
        state.locale(locale);
        return state;
    }

    private String describe(ErrorDetails declared, Locale locale) {
        return describe(writer(true), declared, locale);
    }

    private static String describe(GrpcStatusWriter writer, ErrorDetails declared, Locale locale) {
        StatusException written = writer.write(notFound(declared), state("get-item", locale));
        return String.valueOf(written.getStatus().getDescription());
    }

    private static ErrorInfo errorInfo(StatusException written) throws InvalidProtocolBufferException {
        return statusOf(written).getDetails(0).unpack(ErrorInfo.class);
    }

    private static com.google.rpc.Status statusOf(StatusException written) {
        return Objects.requireNonNull(StatusProto.fromThrowable(written), "status details");
    }

    @Test
    void placeholderTemplateIsDescribedAsNotFoundWithTheDeclaredReason() throws Exception {
        SynapseException error = notFound(ShelfErrors.SHELF_NOT_FOUND);
        StatusException written = writer(true).write(error, state("get-item", Locale.ROOT));

        assertThat(error.details()).isEqualTo(ShelfErrors.SHELF_NOT_FOUND);
        assertThat(written.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(written.getStatus().getDescription()).isEqualTo("The resource was not found");
        assertThat(statusOf(written).getMessage()).isEqualTo("The resource was not found");
        assertThat(errorInfo(written).getReason()).isEqualTo("SHELF_NOT_FOUND");
        assertThat(describe(ShelfErrors.SHELF_ITEM_NOT_FOUND, Locale.ROOT)).isEqualTo("The resource was not found");
    }

    @Test
    void templateWithoutPlaceholdersKeepsItsText() {
        assertThat(describe(ShelfErrors.SHELF_EMPTY, Locale.ROOT)).isEqualTo("The shelf has no such item");
        assertThat(describe(ShelfErrors.SHELF_QUOTED, Locale.ROOT)).isEqualTo("Shelf {0} was not found");
        assertThat(describe(BindingFixtures.ItemErrors.ITEM_NOT_FOUND, Locale.ROOT)).isEqualTo("Item was not found");
    }

    @Test
    void templateWithoutPlaceholdersKeepsItsMessageSourceEntry() {
        messageSource.addMessage("SHELF_EMPTY", Locale.FRENCH, "L'étagère n'a pas cet article");

        assertThat(describe(ShelfErrors.SHELF_EMPTY, Locale.FRENCH)).isEqualTo("L'étagère n'a pas cet article");
    }

    @Test
    void anInvalidTemplateIsDescribedAsNotFound() {
        assertThat(describe(ShelfErrors.SHELF_BROKEN, Locale.ROOT)).isEqualTo("The resource was not found");
    }

    @Test
    void theDeclaredCodesEntryWithPlaceholdersDoesNotLeak() {
        messageSource.addMessage("SHELF_NOT_FOUND", Locale.ROOT, "Shelf {0} is missing");
        messageSource.addMessage("SHELF_NOT_FOUND", Locale.GERMAN, "Regal {0} nicht gefunden");

        assertThat(describe(ShelfErrors.SHELF_NOT_FOUND, Locale.ROOT)).isEqualTo("The resource was not found");
        assertThat(describe(ShelfErrors.SHELF_NOT_FOUND, Locale.GERMAN)).isEqualTo("The resource was not found");
    }

    @Test
    void aLocalizedNotFoundEntryApplies() {
        messageSource.addMessage("NOT_FOUND", Locale.FRENCH, "La ressource est introuvable");

        assertThat(describe(ShelfErrors.SHELF_NOT_FOUND, Locale.FRENCH)).isEqualTo("La ressource est introuvable");
    }

    @Test
    void statusOverridesOfTheDeclaredCodeStillApply() throws Exception {
        GrpcStatusWriter global = writer(true,
                new GrpcErrorStatuses(Map.of("SHELF_NOT_FOUND", Status.Code.FAILED_PRECONDITION), Map.of()));
        GrpcStatusWriter perOperation = writer(true, new GrpcErrorStatuses(Map.of(),
                Map.of("get-item", Map.of("SHELF_NOT_FOUND", Status.Code.OUT_OF_RANGE))));

        StatusException byGlobal = global
                .write(notFound(ShelfErrors.SHELF_NOT_FOUND), state("get-item", Locale.ROOT));
        StatusException byOperation = perOperation
                .write(notFound(ShelfErrors.SHELF_NOT_FOUND), state("get-item", Locale.ROOT));

        assertThat(byGlobal.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(byGlobal.getStatus().getDescription()).isEqualTo("The resource was not found");
        assertThat(errorInfo(byGlobal).getReason()).isEqualTo("SHELF_NOT_FOUND");
        assertThat(byOperation.getStatus().getCode()).isEqualTo(Status.Code.OUT_OF_RANGE);
        assertThat(errorInfo(byOperation).getReason()).isEqualTo("SHELF_NOT_FOUND");
    }

    @Test
    void detailOffIsEmpty() {
        StatusException written = writer(false)
                .write(notFound(ShelfErrors.SHELF_NOT_FOUND), state("get-item", Locale.ROOT));

        assertThat(written.getStatus().getDescription()).isNullOrEmpty();
        assertThat(statusOf(written).getMessage()).isEmpty();
    }

    /** The handler throws before it writes anything. */
    private static final class IgnoringObserver implements StreamObserver<com.google.protobuf.DynamicMessage> {

        @Override
        public void onNext(com.google.protobuf.DynamicMessage value) {
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }
}
