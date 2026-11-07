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

import com.google.protobuf.DynamicMessage;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.grpc.fixture.proto.Item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class MarshallersTest {

    private static final Item ITEM = Item.newBuilder().setId("i-1").setName("Dune").build();

    @Test
    void rawBytesPassThroughUnchanged() {
        byte[] bytes = {(byte) 0xFF, 0x00, 0x7F};

        assertThat(RawBytesMarshaller.INSTANCE.parse(RawBytesMarshaller.INSTANCE.stream(bytes))).isEqualTo(bytes);
    }

    @Test
    void unreadableRawBytesAreInternal() {
        assertThatExceptionOfType(StatusRuntimeException.class)
                .isThrownBy(() -> RawBytesMarshaller.INSTANCE.parse(new Failing()))
                .satisfies(e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void dynamicMessagesRoundTripInTheWireFormat() throws IOException {
        DynamicMessageMarshaller marshaller = new DynamicMessageMarshaller(Item.getDescriptor());
        DynamicMessage message = DynamicMessage.parseFrom(Item.getDescriptor(), ITEM.toByteArray());

        byte[] written = marshaller.stream(message).readAllBytes();

        assertThat(Item.parseFrom(written)).isEqualTo(ITEM);
        assertThat(marshaller.parse(new ByteArrayInputStream(written))).isEqualTo(message);
        assertThat(marshaller.getMessagePrototype()).isEqualTo(DynamicMessage.getDefaultInstance(Item.getDescriptor()));
        assertThat(marshaller.getMessageClass()).isEqualTo(DynamicMessage.class);
    }

    @Test
    void invalidDynamicMessageBytesAreInternal() {
        DynamicMessageMarshaller marshaller = new DynamicMessageMarshaller(Item.getDescriptor());

        assertThatExceptionOfType(StatusRuntimeException.class)
                .isThrownBy(() -> marshaller.parse(new ByteArrayInputStream(new byte[]{(byte) 0xFF, (byte) 0xFF})))
                .satisfies(e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    /** A stream whose every read fails. */
    private static final class Failing extends InputStream {

        @Override
        public int read() throws IOException {
            throw new IOException("broken");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            throw new IOException("broken");
        }
    }
}
