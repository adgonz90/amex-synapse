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

import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

import io.americanexpress.synapse.test.grpc.fixture.proto.PingReply;
import io.americanexpress.synapse.test.grpc.fixture.proto.PingRequest;
import io.americanexpress.synapse.test.grpc.fixture.proto.PingServiceGrpc;

/** A hand-written service, which the slice keeps. */
@Service
class PingGrpcService extends PingServiceGrpc.PingServiceImplBase {

    @Override
    public void ping(PingRequest request, StreamObserver<PingReply> responseObserver) {
        responseObserver.onNext(PingReply.newBuilder().setMessage("pong " + request.getMessage()).build());
        responseObserver.onCompleted();
    }
}
