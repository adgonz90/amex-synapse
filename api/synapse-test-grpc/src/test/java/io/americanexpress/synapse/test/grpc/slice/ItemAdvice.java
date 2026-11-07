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
package io.americanexpress.synapse.test.grpc.slice;

import io.grpc.Status;
import org.springframework.grpc.server.advice.GrpcAdvice;
import org.springframework.grpc.server.advice.GrpcExceptionHandler;

/**
 * The application's gRPC advice, which the slice keeps: in production it answers an {@link IllegalArgumentException}
 * before Synapse's own exception handler does. It is public because Spring gRPC calls its handler methods without
 * making them accessible.
 */
@GrpcAdvice
public class ItemAdvice {

    @GrpcExceptionHandler(IllegalArgumentException.class)
    public Status rejected(IllegalArgumentException exception) {
        return Status.INVALID_ARGUMENT.withDescription("rejected by the advice");
    }
}
