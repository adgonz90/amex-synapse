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
package io.americanexpress.synapse.api.grpc.internal.edge;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.protobuf.StatusProto;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.CreateDocRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.DeleteDocRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Doc;
import io.americanexpress.synapse.api.grpc.fixture.proto.DocConditionsServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.DocServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetDocRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.PutDocRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.ReadDocRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.RenameDocRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.UpdateDocRequest;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.conditional.VersionCondition.AnyVersion;
import io.americanexpress.synapse.core.conditional.VersionCondition.Version;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A {@code VersionCondition} over gRPC, through the real pipeline on the in-process server: the request's
 * {@code etag} field carries REST's entity tag token, a required one that is missing is {@code PRECONDITION_REQUIRED},
 * and a stale write follows REST's rule ({@code PRECONDITION_FAILED} when the request named a version, otherwise
 * {@code STALE_VERSION}). Both are {@code ABORTED}; a weak or unbound tag is {@code FAILED_PRECONDITION}.
 */
@SpringBootTest(classes = GrpcConditionalIT.TestApplication.class)
@TestPropertySource(properties = {"spring.grpc.server.inprocess.name=synapse-grpc-conditional",
        "spring.grpc.server.port=0", "management.tracing.export.otlp.enabled=false",
        "management.logging.export.otlp.enabled=false", "management.otlp.metrics.export.enabled=false"})
@ActiveProfiles("test")
class GrpcConditionalIT {

    @Value("${spring.grpc.server.inprocess.name}")
    String server;

    @Autowired
    DocStore store;

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    @Tag("smoke")
    void missingRequiredEtagIsFailedPreconditionWithReason() throws InterruptedException {
        Doc doc = create("draft");

        StatusRuntimeException e = failure(stub -> stub.deleteDoc(delete(doc.getId(), "")));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("PRECONDITION_REQUIRED");
        assertThat(detail(e, BadRequest.class).getFieldViolationsList())
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getField()).isEqualTo("etag");
                    assertThat(violation.getDescription())
                            .isEqualTo("is required; send the etag from a read of this resource, or * for any "
                                    + "current version");
                });
        assertThat(store.deletes.get()).isZero();
    }

    @Test
    void staleEtagIsAbortedPreconditionFailed() throws InterruptedException {
        Doc doc = create("draft");
        assertThat(call(stub -> stub.updateDoc(update(doc.getId(), "first", "\"1\""))).getVersion()).isEqualTo(2);

        StatusRuntimeException e = failure(stub -> stub.updateDoc(update(doc.getId(), "second", "\"1\"")));

        // The code is REST's for a stale If-Match (412): the request named a version, and the precondition failed.
        // The status is AIP-154's for a stale etag: ABORTED, so the client re-reads and retries.
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("PRECONDITION_FAILED");
        assertThat(store.find(doc.getId()).text()).isEqualTo("first");
    }

    @Test
    void starOnAConcurrentlyChangedRowIsAborted() throws InterruptedException {
        Doc doc = create("draft");
        store.raceNext.set(true);

        StatusRuntimeException e = failure(stub -> stub.updateDoc(update(doc.getId(), "mine", "*")));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("PRECONDITION_FAILED");
        assertThat(store.lastCondition.get()).isInstanceOf(AnyVersion.class);
    }

    @Test
    void unconditionalLostRaceIsAbortedStaleVersion() throws InterruptedException {
        Doc doc = create("draft");
        store.raceNext.set(true);

        StatusRuntimeException e = failure(stub -> stub.updateDoc(update(doc.getId(), "mine", "")));

        // As REST answers a write without If-Match that lost a race 409: the request named no version.
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("STALE_VERSION");
        assertThat(store.updates.get()).isEqualTo(1);
        assertThat(store.lastCondition.get()).isNull();
    }

    @Test
    void weakEtagIsFailedPreconditionBeforeTheOperation() throws InterruptedException {
        Doc doc = create("draft");

        StatusRuntimeException e = failure(stub -> stub.updateDoc(update(doc.getId(), "mine", "W/\"1\"")));

        // A weak tag never matches, so re-reading won't help: FAILED_PRECONDITION, not a stale etag's ABORTED.
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("PRECONDITION_FAILED");
        assertThat(store.updates.get()).isZero();
    }

    @Test
    void twoTagsAreInvalidArgument() throws InterruptedException {
        Doc doc = create("draft");

        StatusRuntimeException e = failure(stub -> stub.deleteDoc(delete(doc.getId(), "\"1\", \"2\"")));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("VALIDATION_FAILED");
        assertThat(detail(e, BadRequest.class).getFieldViolationsList())
                .singleElement()
                .satisfies(violation -> assertThat(violation.getField()).isEqualTo("etag"));
        assertThat(store.deletes.get()).isZero();
    }

    @Test
    void grpcCarriesRestsETagHeaderValue() throws InterruptedException {
        Doc doc = create("draft");
        call(stub -> stub.updateDoc(update(doc.getId(), "second", "\"1\"")));
        call(stub -> stub.updateDoc(update(doc.getId(), "third", "\"2\"")));
        // The literal ETag header REST sends for version 3 (SynapseRestAutoConfigurationTest and ETagValuesTest pin
        // REST's side to the same literal; both come from core's VersionTokens).
        String restHeader = "\"3\"";

        Doc read = call(stub -> stub.getDoc(GetDocRequest.newBuilder().setId(doc.getId()).build()));
        call(stub -> stub.deleteDoc(delete(doc.getId(), restHeader)));

        assertThat(read.getEtag()).isEqualTo(restHeader);
        assertThat(store.lastCondition.get()).isEqualTo(new Version<>(3L));
        assertThat(store.docs).doesNotContainKey(doc.getId());
    }

    @Test
    void anEtagTheWriteDoesntBindIsFailedPrecondition() throws InterruptedException {
        Doc doc = create("draft");

        StatusRuntimeException etag = conditionsFailure(stub -> stub
                .renameDoc(RenameDocRequest
                        .newBuilder()
                        .setId(doc.getId())
                        .setText("renamed")
                        .setEtag("\"1\"")
                        .build()));
        StatusRuntimeException ifNoneMatch = failure(stub -> stub
                .updateDoc(UpdateDocRequest
                        .newBuilder()
                        .setId(doc.getId())
                        .setText("mine")
                        .setIfNoneMatch("*")
                        .build()));
        Doc renamed = conditions(stub -> stub
                .renameDoc(RenameDocRequest
                        .newBuilder()
                        .setId(doc.getId())
                        .setText("renamed")
                        .build()));

        // As REST answers an If-Match or If-None-Match a write route doesn't bind: 412, the operation not called.
        for (StatusRuntimeException e : List.of(etag, ifNoneMatch)) {
            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
            assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("PRECONDITION_FAILED");
        }
        assertThat(store.updates.get()).isZero();
        assertThat(store.renames.get()).isEqualTo(1);
        assertThat(renamed.getText()).isEqualTo("renamed");
    }

    @Test
    void createOnlyPutHasIfNoneMatchSemantics() throws InterruptedException {
        // REST's create-only PUT (rest.adoc "If-None-Match on writes"): * creates only, a weak tag binds its value.
        Doc created = conditions(stub -> stub.putDoc(putDoc("p1", "first", "*")));
        StatusRuntimeException exists = conditionsFailure(stub -> stub.putDoc(putDoc("p1", "again", "*")));
        // At version 1, not 2: unless-at-2 holds, so the put replaces it (a weak tag binds its value, 2).
        Doc replaced = conditions(stub -> stub.putDoc(putDoc("p1", "unless-2", "W/\"2\"")));
        StatusRuntimeException unlessAt2 = conditionsFailure(stub -> stub.putDoc(putDoc("p1", "unless-2", "\"2\"")));

        assertThat(created.getVersion()).isEqualTo(1);
        assertThat(exists.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(detail(exists, ErrorInfo.class).getReason()).isEqualTo("PRECONDITION_FAILED");
        assertThat(replaced.getVersion()).isEqualTo(2);
        assertThat(unlessAt2.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(store.lastCondition.get()).isEqualTo(new Version<>(2L));
    }

    @Test
    void ifNoneMatchLostRaceStaysAbortedStaleVersion() throws InterruptedException {
        // REST: "The STALE_VERSION to 412 rule applies to If-Match only".
        conditions(stub -> stub.putDoc(putDoc("p2", "first", "")));
        store.raceNext.set(true);

        StatusRuntimeException e = conditionsFailure(stub -> stub.putDoc(putDoc("p2", "mine", "\"7\"")));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("STALE_VERSION");
    }

    @Test
    void conditionalReadPassesIfNoneMatchToTheOperation() throws InterruptedException {
        // As REST binds a @Nullable VersionCondition from If-None-Match on GET; a weak tag binds its value.
        Doc doc = create("draft");

        Doc strong = conditions(stub -> stub.readDoc(readDoc(doc.getId(), "\"1\"")));
        VersionCondition<Long> strongCondition = store.lastCondition.get();
        conditions(stub -> stub.readDoc(readDoc(doc.getId(), "W/\"1\"")));
        VersionCondition<Long> weakCondition = store.lastCondition.get();
        conditions(stub -> stub.readDoc(readDoc(doc.getId(), "*")));

        assertThat(strong.getEtag()).isEqualTo("\"1\"");
        assertThat(strongCondition).isEqualTo(new Version<>(1L));
        assertThat(weakCondition).isEqualTo(new Version<>(1L));
        assertThat(store.lastCondition.get()).isInstanceOf(AnyVersion.class);
    }

    @Test
    void etagIsSentOnReadsAndCreatesOnly() throws InterruptedException {
        Doc created = create("draft");
        Doc updated = call(stub -> stub.updateDoc(update(created.getId(), "second", "\"1\"")));
        Doc read = call(stub -> stub.getDoc(GetDocRequest.newBuilder().setId(created.getId()).build()));

        assertThat(created.getEtag()).isEqualTo("\"1\"");
        assertThat(updated.getVersion()).isEqualTo(2);
        assertThat(updated.getEtag()).isEmpty();
        assertThat(read.getEtag()).isEqualTo("\"2\"");
    }

    /** A per-code status override flips a missing required etag to AIP-154's INVALID_ARGUMENT. */
    @Nested
    @TestPropertySource(properties = {"spring.grpc.server.inprocess.name=synapse-grpc-conditional-override",
            "synapse.api.grpc.errors.status.[PRECONDITION_REQUIRED]=INVALID_ARGUMENT"})
    class WithOverride {

        @Test
        void overrideTurnsItIntoInvalidArgument() throws InterruptedException {
            Doc doc = create("draft");

            StatusRuntimeException e = failure(stub -> stub.deleteDoc(delete(doc.getId(), "")));

            assertThat(server).isEqualTo("synapse-grpc-conditional-override");
            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("PRECONDITION_REQUIRED");
            assertThat(store.deletes.get()).isZero();
        }
    }

    private Doc create(String text) throws InterruptedException {
        return call(stub -> stub.createDoc(CreateDocRequest.newBuilder().setText(text).build()));
    }

    private static UpdateDocRequest update(String id, String text, String etag) {
        return UpdateDocRequest.newBuilder().setId(id).setText(text).setEtag(etag).build();
    }

    private static DeleteDocRequest delete(String id, String etag) {
        return DeleteDocRequest.newBuilder().setId(id).setEtag(etag).build();
    }

    private static PutDocRequest putDoc(String id, String text, String ifNoneMatch) {
        return PutDocRequest.newBuilder().setId(id).setText(text).setIfNoneMatch(ifNoneMatch).build();
    }

    private static ReadDocRequest readDoc(String id, String ifNoneMatch) {
        return ReadDocRequest.newBuilder().setId(id).setIfNoneMatch(ifNoneMatch).build();
    }

    private StatusRuntimeException conditionsFailure(
            Function<DocConditionsServiceGrpc.DocConditionsServiceBlockingStub, ?> rpc) {
        return catchThrowableOfType(StatusRuntimeException.class, () -> conditions(rpc));
    }

    private <T> T conditions(Function<DocConditionsServiceGrpc.DocConditionsServiceBlockingStub, T> rpc)
            throws InterruptedException {
        ManagedChannel channel = InProcessChannelBuilder.forName(server).directExecutor().build();
        try {
            return rpc
                    .apply(DocConditionsServiceGrpc
                            .newBlockingStub(channel)
                            .withDeadlineAfter(10, TimeUnit.SECONDS));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private <T> T call(Function<DocServiceGrpc.DocServiceBlockingStub, T> rpc) throws InterruptedException {
        ManagedChannel channel = InProcessChannelBuilder.forName(server).directExecutor().build();
        try {
            return rpc.apply(DocServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private StatusRuntimeException failure(Function<DocServiceGrpc.DocServiceBlockingStub, ?> rpc) {
        return catchThrowableOfType(StatusRuntimeException.class, () -> call(rpc));
    }

    private static <T extends Message> T detail(StatusRuntimeException e, Class<T> type) {
        com.google.rpc.Status status = Objects.requireNonNull(StatusProto.fromThrowable(e), "status details");
        for (Any any : status.getDetailsList()) {
            if (any.is(type)) {
                try {
                    return any.unpack(type);
                } catch (InvalidProtocolBufferException ex) {
                    throw new AssertionError(ex);
                }
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + status);
    }

    /** The key of a doc. */
    record DocKey(String id) {
    }

    /** A doc: {@code synapse.fixture.v1.Doc}, whose etag {@code .etag(DocView::version)} fills. */
    record DocView(String id, String text, long version) {
    }

    /** The input of a create: it names no version, so the response may carry the etag (D46). */
    record NewDoc(String text) {
    }

    /** An update, conditional when the request sends an etag. */
    record DocUpdate(String id, String text, @Nullable VersionCondition<Long> version) {
    }

    /** A delete, which must name a version. */
    record DocDeletion(String id, VersionCondition<Long> version) {
    }

    /**
     * The docs, written with a compare-and-set on the version. {@link #raceNext} makes the next write lose a race: a
     * concurrent change lands between the operation's read and its write.
     */
    static final class DocStore {

        final Map<String, DocView> docs = new ConcurrentHashMap<>();

        final AtomicBoolean raceNext = new AtomicBoolean();

        final AtomicInteger updates = new AtomicInteger();

        final AtomicInteger deletes = new AtomicInteger();

        final AtomicInteger renames = new AtomicInteger();

        final AtomicReference<@Nullable VersionCondition<Long>> lastCondition = new AtomicReference<>();

        private final AtomicInteger ids = new AtomicInteger();

        void reset() {
            docs.clear();
            raceNext.set(false);
            updates.set(0);
            deletes.set(0);
            renames.set(0);
            lastCondition.set(null);
        }

        DocView find(String id) {
            DocView doc = docs.get(id);
            if (doc == null) {
                throw new SynapseException(SynapseErrors.NOT_FOUND);
            }
            return doc;
        }

        DocView create(String text) {
            DocView doc = new DocView("doc-" + ids.incrementAndGet(), text, 1);
            docs.put(doc.id(), doc);
            return doc;
        }

        /**
         * Creates or replaces a doc unless the If-None-Match condition holds: {@code *} refuses an existing doc, and
         * {@code Version(v)} a doc at {@code v} ({@code PRECONDITION_FAILED}, as rest.adoc's create-only PUT). The
         * write is a compare-and-set, so a lost race is {@code STALE_VERSION}.
         */
        DocView put(String id, String text, @Nullable VersionCondition<Long> unless) {
            lastCondition.set(unless);
            DocView current = docs.get(id);
            boolean refused = switch (unless) {
                case null -> false;
                case AnyVersion<Long> any -> current != null;
                case Version<Long>(Long version) -> current != null && current.version() == version;
            };
            if (refused) {
                throw new SynapseException(SynapseErrors.PRECONDITION_FAILED);
            }
            if (current != null && raceNext.getAndSet(false)) {
                docs.put(id, new DocView(id, "theirs", current.version() + 1));
            }
            DocView next = new DocView(id, text, current == null ? 1 : current.version() + 1);
            boolean written = current == null ? docs.putIfAbsent(id, next) == null : docs.replace(id, current, next);
            if (!written) {
                throw new SynapseException(SynapseErrors.STALE_VERSION);
            }
            return next;
        }

        /** Writes over the version the condition names, or the version just read for {@code *} and none. */
        @Nullable
        DocView write(String id, @Nullable VersionCondition<Long> condition, @Nullable String text) {
            lastCondition.set(condition);
            DocView read = find(id);
            long expected = switch (condition) {
                case null -> read.version();
                case AnyVersion<Long> any -> read.version();
                case Version<Long>(Long version) -> version;
            };
            if (raceNext.getAndSet(false)) {
                docs.put(id, new DocView(id, "theirs", read.version() + 1));
            }
            DocView current = find(id);
            DocView next = text == null ? null : new DocView(id, text, current.version() + 1);
            boolean written = current.version() == expected
                    && (next == null ? docs.remove(id, current) : docs.replace(id, current, next));
            if (!written) {
                throw new SynapseException(SynapseErrors.STALE_VERSION);
            }
            return next;
        }
    }

    /** Reads a doc. */
    static class GetDocOperation implements QueryOperation<DocKey, Optional<DocView>> {

        private final DocStore store;

        GetDocOperation(DocStore store) {
            this.store = store;
        }

        @Override
        public Optional<DocView> execute(DocKey input) {
            return Optional.ofNullable(store.docs.get(input.id()));
        }
    }

    /** Creates a doc at version 1. */
    static class CreateDocOperation implements CommandOperation<NewDoc, DocView> {

        private final DocStore store;

        CreateDocOperation(DocStore store) {
            this.store = store;
        }

        @Override
        public DocView execute(NewDoc input) {
            return store.create(input.text());
        }
    }

    /** Replaces a doc's text. */
    static class UpdateDocOperation implements CommandOperation<DocUpdate, DocView> {

        private final DocStore store;

        UpdateDocOperation(DocStore store) {
            this.store = store;
        }

        @Override
        public DocView execute(DocUpdate input) {
            store.updates.incrementAndGet();
            return Objects.requireNonNull(store.write(input.id(), input.version(), input.text()));
        }
    }

    /** Deletes a doc. */
    static class DeleteDocOperation implements CommandOperation<DocDeletion, Void> {

        private final DocStore store;

        DeleteDocOperation(DocStore store) {
            this.store = store;
        }

        @Override
        @SuppressWarnings("NullAway") // A Void output is null.
        public Void execute(DocDeletion input) {
            store.deletes.incrementAndGet();
            store.write(input.id(), input.version(), null);
            return null;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        DocStore docStore() {
            return new DocStore();
        }

        @Bean
        GetDocOperation getDocOperation(DocStore store) {
            return new GetDocOperation(store);
        }

        @Bean
        CreateDocOperation createDocOperation(DocStore store) {
            return new CreateDocOperation(store);
        }

        @Bean
        UpdateDocOperation updateDocOperation(DocStore store) {
            return new UpdateDocOperation(store);
        }

        @Bean
        DeleteDocOperation deleteDocOperation(DocStore store) {
            return new DeleteDocOperation(store);
        }

        @Bean
        GrpcBindings docBindings(GetDocOperation getDoc, CreateDocOperation createDoc) {
            return GrpcBindings
                    .service(DocServiceGrpc.getServiceDescriptor())
                    .unary(DocServiceGrpc.getGetDocMethod(), getDoc)
                    .etag(DocView::version)
                    .unary(DocServiceGrpc.getCreateDocMethod(), createDoc)
                    .etag(DocView::version)
                    // UpdateDoc and DeleteDoc: their VersionCondition binds from the request's etag field.
                    .bindAllByName()
                    .build();
        }

        @Bean
        PutDocOperation putDocOperation(DocStore store) {
            return new PutDocOperation(store);
        }

        @Bean
        ReadDocOperation readDocOperation(DocStore store) {
            return new ReadDocOperation(store);
        }

        @Bean
        RenameDocOperation renameDocOperation(DocStore store) {
            return new RenameDocOperation(store);
        }

        @Bean
        GrpcBindings docConditionsBindings(PutDocOperation putDoc, ReadDocOperation readDoc,
                RenameDocOperation renameDoc) {
            return GrpcBindings
                    .service(DocConditionsServiceGrpc.getServiceDescriptor())
                    // As REST's .header("If-None-Match", PutDocInput::unless).
                    .unary(DocConditionsServiceGrpc.getPutDocMethod(), putDoc)
                    .map("if_none_match", PutDocInput::unless)
                    .unary(DocConditionsServiceGrpc.getReadDocMethod(), readDoc)
                    .map("if_none_match", ReadDocInput::unless)
                    .etag(DocView::version)
                    // RenameDocRequest has an etag field, which RenameDocInput doesn't bind.
                    .unary(DocConditionsServiceGrpc.getRenameDocMethod(), renameDoc)
                    .build();
        }
    }

    /** A create-only (unless {@code *}) or unless-at-version put, as REST's {@code If-None-Match} on a write. */
    record PutDocInput(String id, String text, @Nullable VersionCondition<Long> unless) {
    }

    /** A read that passes its {@code if_none_match} to the operation. */
    record ReadDocInput(String id, @Nullable VersionCondition<Long> unless) {
    }

    /** A rename, unconditional. */
    record RenameDocInput(String id, String text) {
    }

    /** Puts a doc: creates it, or replaces it with a compare-and-set, unless the condition says no. */
    static class PutDocOperation implements CommandOperation<PutDocInput, DocView> {

        private final DocStore store;

        PutDocOperation(DocStore store) {
            this.store = store;
        }

        @Override
        public DocView execute(PutDocInput input) {
            return store.put(input.id(), input.text(), input.unless());
        }
    }

    /** Reads a doc, recording the condition it got. */
    static class ReadDocOperation implements QueryOperation<ReadDocInput, DocView> {

        private final DocStore store;

        ReadDocOperation(DocStore store) {
            this.store = store;
        }

        @Override
        public DocView execute(ReadDocInput input) {
            store.lastCondition.set(input.unless());
            return store.find(input.id());
        }
    }

    /** Renames a doc, unconditionally. */
    static class RenameDocOperation implements CommandOperation<RenameDocInput, DocView> {

        private final DocStore store;

        RenameDocOperation(DocStore store) {
            this.store = store;
        }

        @Override
        public DocView execute(RenameDocInput input) {
            store.renames.incrementAndGet();
            DocView current = store.find(input.id());
            DocView next = new DocView(input.id(), input.text(), current.version() + 1);
            store.docs.put(input.id(), next);
            return next;
        }
    }
}
