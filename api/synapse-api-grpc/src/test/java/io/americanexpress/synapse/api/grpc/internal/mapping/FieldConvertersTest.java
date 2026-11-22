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
package io.americanexpress.synapse.api.grpc.internal.mapping;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.fixture.proto.Scalars;
import io.americanexpress.synapse.api.grpc.internal.mapping.FieldConverter.Reading;
import io.americanexpress.synapse.core.error.Violation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The {@code repeated} and {@code map} converters: element paths are built only for the elements that fail. */
class FieldConvertersTest {

    private static final int MILLION = 1_000_000;

    private static final FieldConverters.Site SITE = new FieldConverters.Site("Numbers.values", "Scalars.values");

    private static final FieldConverter INT32 = Objects
            .requireNonNull(FieldConverters.int32(scalar("int32_value"), Integer.class, SITE));

    private static final FieldConverter UINT32 = Objects
            .requireNonNull(FieldConverters.int32(scalar("uint32_value"), Integer.class, SITE));

    @Test
    void aMillionInt32ElementsReadWithoutViolations() {
        List<Integer> values = values(MILLION);
        Reading reading = new Reading();

        Object read = FieldConverters.list(INT32, SITE).read(values, reading, "values");

        assertThat(reading.violations()).isEmpty();
        assertThat(read).isEqualTo(values);
    }

    /**
     * A packed {@code repeated int32} of small values takes one byte per element on the wire, so a message within
     * grpc-java's 4 MiB limit holds about four million elements. Reading them allocates the list's slots and nothing
     * per element; an element path string per element would cost more than 30 bytes each.
     */
    @Test
    void elementPathsAllocateNothingPerValidElement() {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory
                .getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled(),
                "per-thread allocation is measured");
        List<Integer> values = values(MILLION);
        FieldConverter list = FieldConverters.list(INT32, SITE);
        for (int warmUp = 0; warmUp < 3; warmUp++) {
            list.read(values, new Reading(), "values");
        }

        long before = threads.getCurrentThreadAllocatedBytes();
        Object read = list.read(values, new Reading(), "values");
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertThat(read).isNotSameAs(FieldConverter.FAILED);
        assertThat(allocated / (double) MILLION).as("bytes allocated per element").isLessThan(16);
    }

    @Test
    void anInvalidElementStillNamesItsIndex() {
        Reading reading = new Reading();

        Object read = FieldConverters.list(UINT32, SITE).read(List.of(1, -1, 2, -2), reading, "tags");

        assertThat(FieldConverter.failed(read)).isTrue();
        assertThat(reading.violations())
                .containsExactly(new Violation("tags[1]", Reading.INVALID), new Violation("tags[3]", Reading.INVALID));
    }

    @Test
    void aMissingElementStillNamesItsIndex() {
        FieldConverter nullForZero = new FieldConverter() {

            @Override
            public @Nullable Object read(Object value, Reading reading, String path) {
                return (Integer) value == 0 ? null : value;
            }

            @Override
            public Object write(Object value) {
                return value;
            }
        };
        Reading reading = new Reading();

        Object read = FieldConverters.list(nullForZero, SITE).read(List.of(1, 0), reading, "genres");

        assertThat(FieldConverter.failed(read)).isTrue();
        assertThat(reading.violations()).containsExactly(new Violation("genres[1]", Reading.MISSING));
    }

    /**
     * A map entry's violation names the map field only, because the key is the caller's data; no path is built per
     * entry.
     */
    @Test
    void anInvalidMapValueNamesTheMapField() {
        Descriptor entry = Item.getDescriptor().findFieldByName("counts").getMessageType();
        FieldDescriptor key = entry.findFieldByName("key");
        FieldDescriptor value = entry.findFieldByName("value");
        FieldConverter nonNegative = new FieldConverter() {

            @Override
            public Object read(Object read, Reading reading, String path) {
                if ((Long) read < 0) {
                    throw new IllegalArgumentException("negative");
                }
                return read;
            }

            @Override
            public Object write(Object written) {
                return written;
            }
        };
        List<DynamicMessage> entries = List
                .of(DynamicMessage.newBuilder(entry).setField(key, "a").setField(value, 1L).build(),
                        DynamicMessage.newBuilder(entry).setField(key, "b").setField(value, -1L).build());
        Reading reading = new Reading();

        Object read = FieldConverters
                .map(entry, key, value, FieldConverters.IDENTITY, nonNegative, SITE)
                .read(entries, reading, "counts");

        assertThat(FieldConverter.failed(read)).isTrue();
        assertThat(reading.violations()).containsExactly(new Violation("counts", Reading.INVALID));
        assertThat(FieldConverters
                .map(entry, key, value, FieldConverters.IDENTITY, nonNegative, SITE)
                .read(entries.subList(0, 1), new Reading(), "counts")).isEqualTo(Map.of("a", 1L));
    }

    private static List<Integer> values(int size) {
        List<Integer> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(i % 100);
        }
        return values;
    }

    private static FieldDescriptor scalar(String name) {
        return Scalars.getDescriptor().findFieldByName(name);
    }
}
