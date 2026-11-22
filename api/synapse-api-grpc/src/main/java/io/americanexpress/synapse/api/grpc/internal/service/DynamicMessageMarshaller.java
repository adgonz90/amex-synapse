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

import java.io.IOException;
import java.io.InputStream;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * Writes a compiled method's response, a {@link DynamicMessage} of the method's response type, in the protobuf wire
 * format. Generated message classes are never instantiated.
 */
public final class DynamicMessageMarshaller implements MethodDescriptor.PrototypeMarshaller<DynamicMessage> {

    private final Descriptor descriptor;

    /**
     * Creates the marshaller.
     *
     * @param descriptor the message type
     */
    public DynamicMessageMarshaller(Descriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public InputStream stream(DynamicMessage value) {
        return value.toByteString().newInput();
    }

    /**
     * Parses a message of this type. A server never parses its responses; a client that uses this marshaller does.
     *
     * @param stream the message bytes
     * @return the message
     */
    @Override
    public DynamicMessage parse(InputStream stream) {
        try {
            return DynamicMessage.parseFrom(descriptor, stream);
        } catch (IOException e) {
            throw Status.INTERNAL.withDescription("Invalid protobuf byte sequence").withCause(e).asRuntimeException();
        }
    }

    @Override
    public DynamicMessage getMessagePrototype() {
        return DynamicMessage.getDefaultInstance(descriptor);
    }

    @Override
    public Class<DynamicMessage> getMessageClass() {
        return DynamicMessage.class;
    }
}
