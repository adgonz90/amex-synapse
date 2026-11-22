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
package com.example.arch.good.contract.proto;

import io.grpc.Channel;
import io.grpc.stub.annotations.GrpcGenerated;

/**
 * Stand-in for a generated gRPC service class: annotated {@link GrpcGenerated}; its nested classes are generated too.
 */
@GrpcGenerated
public final class BookServiceGrpc {

    public static final String SERVICE_NAME = "books.BookService";

    private BookServiceGrpc() {
    }

    public static BookServiceStub newStub(Channel channel) {
        return new BookServiceStub(channel);
    }

    /**
     * Stand-in for a generated stub.
     */
    public static final class BookServiceStub {

        private final Channel channel;

        BookServiceStub(Channel channel) {
            this.channel = channel;
        }

        public String authority() {
            return channel.authority();
        }
    }
}
