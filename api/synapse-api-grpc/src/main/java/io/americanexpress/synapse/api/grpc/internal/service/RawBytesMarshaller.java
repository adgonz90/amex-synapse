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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * Passes a request's bytes through unparsed. A compiled method parses its request inside its handler, after every
 * interceptor has run, so a malformed message becomes a Synapse error with details; grpc-java would close a call whose
 * request its marshaller fails to parse with {@code UNKNOWN}, below every interceptor. The transport bounds the bytes
 * by {@code spring.grpc.server.inbound.message.max-size} before they get here.
 */
public final class RawBytesMarshaller implements MethodDescriptor.Marshaller<byte[]> {

    /** The marshaller; it has no state. */
    public static final RawBytesMarshaller INSTANCE = new RawBytesMarshaller();

    private RawBytesMarshaller() {
    }

    @Override
    public InputStream stream(byte[] value) {
        return new ByteArrayInputStream(value);
    }

    @Override
    public byte[] parse(InputStream stream) {
        try {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw Status.INTERNAL.withDescription("Failed to read the message").withCause(e).asRuntimeException();
        }
    }
}
