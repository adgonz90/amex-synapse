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
package io.americanexpress.synapse.api.grpc.internal.conditional;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

/**
 * How a version conflict the operation reports is answered over gRPC: REST's rule, so that both protocols answer the
 * same write with the same code. The version check itself is the operation's: it compares the version bound from the
 * request's {@code etag} with the one it reads, and writes with a compare-and-set, throwing
 * {@link SynapseErrors#STALE_VERSION} when either fails.
 */
public final class GrpcPreconditions {

    private GrpcPreconditions() {
    }

    /**
     * Answers a failure of the operation. A {@link SynapseErrors#STALE_VERSION} on a request that sent a non-empty
     * {@code etag} bound to a {@code VersionCondition} (including {@code *}) means the precondition failed: it becomes
     * {@link SynapseErrors#PRECONDITION_FAILED} ({@code FAILED_PRECONDITION}), with the failure as its cause, as REST
     * answers a stale {@code If-Match} 412. Without an {@code etag} the write was unconditional, so a lost race stays
     * {@code STALE_VERSION} ({@code ABORTED}, REST's 409). Every other failure is returned as is.
     *
     * @param etagSent whether the request's {@code etag}, bound to a {@code VersionCondition}, was non-empty
     * @param failure what the operation threw
     * @return the exception to throw
     */
    public static SynapseException translate(boolean etagSent, SynapseException failure) {
        if (etagSent && failure.details() == SynapseErrors.STALE_VERSION) {
            return new SynapseException(SynapseErrors.PRECONDITION_FAILED, failure);
        }
        return failure;
    }
}
