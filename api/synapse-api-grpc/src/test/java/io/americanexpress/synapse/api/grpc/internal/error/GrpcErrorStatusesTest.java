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
package io.americanexpress.synapse.api.grpc.internal.error;

import java.util.HashMap;
import java.util.Map;

import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.americanexpress.synapse.api.grpc.GrpcErrors;
import io.americanexpress.synapse.api.grpc.internal.conditional.GrpcPreconditions;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrpcErrorStatusesTest {

    private static final GrpcErrorStatuses DEFAULTS = new GrpcErrorStatuses(Map.of(), Map.of());

    /** The category table of BUILD.md section 6.1. */
    private static final Map<ErrorCategory, Status.Code> CATEGORY_TABLE = Map
            .of(ErrorCategory.INVALID_INPUT, Status.Code.INVALID_ARGUMENT, ErrorCategory.UNAUTHENTICATED,
                    Status.Code.UNAUTHENTICATED, ErrorCategory.FORBIDDEN, Status.Code.PERMISSION_DENIED,
                    ErrorCategory.NOT_FOUND, Status.Code.NOT_FOUND, ErrorCategory.CONFLICT, Status.Code.ABORTED,
                    ErrorCategory.PRECONDITION_FAILED, Status.Code.FAILED_PRECONDITION, ErrorCategory.RATE_LIMITED,
                    Status.Code.RESOURCE_EXHAUSTED, ErrorCategory.UNAVAILABLE, Status.Code.UNAVAILABLE,
                    ErrorCategory.TIMEOUT, Status.Code.DEADLINE_EXCEEDED, ErrorCategory.INTERNAL,
                    Status.Code.INTERNAL);

    @ParameterizedTest
    @EnumSource(ErrorCategory.class)
    void categoryDefaultsMatchTheSpec(ErrorCategory category) {
        assertThat(DEFAULTS.statusFor(new Custom("CUSTOM", category), null)).isEqualTo(CATEGORY_TABLE.get(category));
        assertThat(DEFAULTS.statusFor(new Custom("CUSTOM", category), "get-book"))
                .isEqualTo(CATEGORY_TABLE.get(category));
    }

    @Test
    void perOperationBeatsGlobalBeatsCodeDefaultBeatsCategory() {
        GrpcErrorStatuses statuses = new GrpcErrorStatuses(
                Map
                        .of("BOOK_NOT_FOUND", Status.Code.FAILED_PRECONDITION, "ALREADY_EXISTS", Status.Code.ABORTED,
                                "METHOD_NOT_FOUND", Status.Code.NOT_FOUND),
                Map
                        .of("get-book", Map
                                .of("BOOK_NOT_FOUND", Status.Code.OUT_OF_RANGE, "ALREADY_EXISTS",
                                        Status.Code.UNAVAILABLE)));

        assertThat(statuses.statusFor(BookErrors.BOOK_NOT_FOUND, "get-book")).isEqualTo(Status.Code.OUT_OF_RANGE);
        assertThat(statuses.statusFor(BookErrors.BOOK_NOT_FOUND, "list-books"))
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(statuses.statusFor(BookErrors.BOOK_NOT_FOUND, null)).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(statuses.statusFor(new Custom("UNKNOWN_CODE", ErrorCategory.NOT_FOUND), "get-book"))
                .isEqualTo(Status.Code.NOT_FOUND);
        // An override beats a built-in code default, too.
        assertThat(statuses.statusFor(SynapseErrors.ALREADY_EXISTS, "get-book")).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(statuses.statusFor(SynapseErrors.ALREADY_EXISTS, "list-books")).isEqualTo(Status.Code.ABORTED);
        assertThat(statuses.statusFor(GrpcErrors.METHOD_NOT_FOUND, null)).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void builtInCodeDefaults() {
        assertThat(DEFAULTS.statusFor(SynapseErrors.ALREADY_EXISTS, null)).isEqualTo(Status.Code.ALREADY_EXISTS);
        assertThat(DEFAULTS.statusFor(SynapseErrors.STALE_VERSION, null)).isEqualTo(Status.Code.ABORTED);
        assertThat(DEFAULTS.statusFor(SynapseErrors.CONFLICT, null)).isEqualTo(Status.Code.ABORTED);
        assertThat(DEFAULTS.statusFor(GrpcErrors.METHOD_NOT_FOUND, null)).isEqualTo(Status.Code.UNIMPLEMENTED);
        // Only Synapse's own ALREADY_EXISTS has the code default: another error with the same category doesn't.
        assertThat(DEFAULTS.statusFor(new Custom("BOOK_EXISTS", ErrorCategory.CONFLICT), null))
                .isEqualTo(Status.Code.ABORTED);
    }

    /** A stale etag, as {@code GrpcPreconditions.translate} answers it: the operation's STALE_VERSION as the cause. */
    private static SynapseException stale() {
        return GrpcPreconditions.translate(true, new SynapseException(SynapseErrors.STALE_VERSION));
    }

    @Test
    void staleCauseIsAborted() {
        SynapseException built = new SynapseException(SynapseErrors.PRECONDITION_FAILED,
                new SynapseException(SynapseErrors.STALE_VERSION));

        assertThat(DEFAULTS.statusFor(built, null)).isEqualTo(Status.Code.ABORTED);
        assertThat(DEFAULTS.statusFor(built, "update-book")).isEqualTo(Status.Code.ABORTED);
        assertThat(DEFAULTS.statusFor(stale(), "update-book")).isEqualTo(Status.Code.ABORTED);
        // The code's own status, without the exception, is still the category's.
        assertThat(DEFAULTS.statusFor(SynapseErrors.PRECONDITION_FAILED, "update-book"))
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void weakAndUnboundStayFailedPrecondition() {
        // A weak tag, or an etag the write doesn't bind: PRECONDITION_FAILED without a cause, before the operation.
        assertThat(DEFAULTS.statusFor(new SynapseException(SynapseErrors.PRECONDITION_FAILED), "update-book"))
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
        // A cause that isn't a stale version.
        assertThat(DEFAULTS
                .statusFor(new SynapseException(SynapseErrors.PRECONDITION_FAILED,
                        new SynapseException(SynapseErrors.CONFLICT)), "update-book"))
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(DEFAULTS
                .statusFor(new SynapseException(SynapseErrors.PRECONDITION_FAILED, new IllegalStateException("x")),
                        null))
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
        // A missing etag.
        assertThat(DEFAULTS.statusFor(new SynapseException(SynapseErrors.PRECONDITION_REQUIRED), "update-book"))
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void operationsOwnPreconditionFailedStaysFailedPrecondition() {
        // An operation that throws PRECONDITION_FAILED itself (a create-only put on an existing resource), or its own
        // code in the PRECONDITION_FAILED category, even with a STALE_VERSION cause.
        assertThat(DEFAULTS.statusFor(new SynapseException(SynapseErrors.PRECONDITION_FAILED), "put-doc"))
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(DEFAULTS
                .statusFor(new SynapseException(new Custom("OUT_OF_STOCK", ErrorCategory.PRECONDITION_FAILED),
                        new SynapseException(SynapseErrors.STALE_VERSION)), "adjust-stock"))
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
        // A STALE_VERSION the operation throws on a request without an etag stays ABORTED, by its category.
        assertThat(DEFAULTS.statusFor(new SynapseException(SynapseErrors.STALE_VERSION), "update-book"))
                .isEqualTo(Status.Code.ABORTED);
    }

    @Test
    void configuredOverrideWinsOverTheStaleDefault() {
        GrpcErrorStatuses global = new GrpcErrorStatuses(
                Map.of("PRECONDITION_FAILED", Status.Code.FAILED_PRECONDITION), Map.of());
        GrpcErrorStatuses perOperation = new GrpcErrorStatuses(Map.of(),
                Map.of("update-book", Map.of("PRECONDITION_FAILED", Status.Code.FAILED_PRECONDITION)));

        assertThat(global.statusFor(stale(), "update-book")).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(global.statusFor(stale(), null)).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(perOperation.statusFor(stale(), "update-book")).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(perOperation.statusFor(stale(), "delete-book")).isEqualTo(Status.Code.ABORTED);
        assertThat(perOperation.statusFor(stale(), null)).isEqualTo(Status.Code.ABORTED);
    }

    @Test
    void staleVersionOverrideDoesNotApplyToTheTranslatedCode() {
        GrpcErrorStatuses statuses = new GrpcErrorStatuses(Map.of("STALE_VERSION", Status.Code.UNAVAILABLE),
                Map.of("update-book", Map.of("STALE_VERSION", Status.Code.OUT_OF_RANGE)));

        // The caller sees PRECONDITION_FAILED, so only a [PRECONDITION_FAILED] override applies.
        assertThat(statuses.statusFor(stale(), "update-book")).isEqualTo(Status.Code.ABORTED);
        assertThat(statuses.statusFor(stale(), null)).isEqualTo(Status.Code.ABORTED);
        // The overrides still apply to STALE_VERSION itself.
        assertThat(statuses.statusFor(new SynapseException(SynapseErrors.STALE_VERSION), "update-book"))
                .isEqualTo(Status.Code.OUT_OF_RANGE);
        assertThat(statuses.statusFor(new SynapseException(SynapseErrors.STALE_VERSION), null))
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void rejectsOk() {
        assertThatThrownBy(() -> new GrpcErrorStatuses(Map.of("X", Status.Code.OK), Map.of()))
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> {
                    assertThat(ex.description())
                            .isEqualTo("synapse.api.grpc.errors.status.[X] is OK, which is not an error status.");
                    assertThat(ex.action())
                            .isEqualTo("Set synapse.api.grpc.errors.status.[X] to a gRPC error status code, such as"
                                    + " FAILED_PRECONDITION, or remove it.");
                });
        assertThatThrownBy(() -> new GrpcErrorStatuses(Map.of(), Map.of("get-book", Map.of("X", Status.Code.OK))))
                .isInstanceOfSatisfying(SynapseConfigurationException.class,
                        ex -> assertThat(ex.description())
                                .isEqualTo("synapse.api.grpc.operations.get-book.errors.status.[X] is OK, which is not"
                                        + " an error status."));
    }

    @Test
    void copiesTheTables() {
        Map<String, Status.Code> global = new HashMap<>(Map.of("X", Status.Code.ABORTED));
        Map<String, Status.Code> own = new HashMap<>(Map.of("X", Status.Code.OUT_OF_RANGE));
        Map<String, Map<String, Status.Code>> perOperation = new HashMap<>(Map.of("op", own));
        GrpcErrorStatuses statuses = new GrpcErrorStatuses(global, perOperation);
        global.clear();
        own.clear();
        perOperation.clear();

        assertThat(statuses.statusFor(new Custom("X", ErrorCategory.INTERNAL), "op"))
                .isEqualTo(Status.Code.OUT_OF_RANGE);
        assertThat(statuses.statusFor(new Custom("X", ErrorCategory.INTERNAL), null)).isEqualTo(Status.Code.ABORTED);
    }

    private record Custom(String code, ErrorCategory category) implements ErrorDetails {

        @Override
        public String messageTemplate() {
            return "custom";
        }
    }
}
