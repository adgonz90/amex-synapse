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
package io.americanexpress.synapse.api.rest.internal.edge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RequestSizeLimitFilterTest {

    private static final DataSize ONE_MB = DataSize.ofMegabytes(1);

    private static final int LIMIT = (int) ONE_MB.toBytes();

    @Test
    void oversizedContentLengthIsRejectedBeforeReading() throws Exception {
        SourceRequest request = new SourceRequest(2_000_000, 2_000_000);

        HttpServletRequest limited = filter(request);

        assertThatExceptionOfType(RequestTooLargeException.class)
                .isThrownBy(limited::getInputStream)
                .satisfies(e -> assertThat(e.limit()).isEqualTo(LIMIT));
        assertThat(request.source.read).isZero();
        assertThat(request.opened).isFalse();
    }

    @Test
    void chunkedBodyOverTheLimitFailsAtTheLimit() throws Exception {
        SourceRequest request = new SourceRequest(LIMIT + 1, -1);

        ServletInputStream stream = filter(request).getInputStream();

        assertThatExceptionOfType(RequestTooLargeException.class).isThrownBy(stream::readAllBytes);
        assertThat(request.source.read).isLessThanOrEqualTo(LIMIT + 1);
        // The stream stays failed.
        assertThatExceptionOfType(RequestTooLargeException.class).isThrownBy(stream::read);
    }

    @Test
    void bodyOfExactlyTheLimitIsReadFully() throws Exception {
        SourceRequest chunked = new SourceRequest(LIMIT, -1);
        SourceRequest declared = new SourceRequest(LIMIT, LIMIT);

        assertThat(filter(chunked).getInputStream().readAllBytes()).hasSize(LIMIT);
        assertThat(filter(declared).getInputStream().readAllBytes()).hasSize(LIMIT);
    }

    @Test
    void singleByteReadsAreCountedToo() throws Exception {
        SourceRequest request = new SourceRequest(3, -1);
        HttpServletRequest limited = new SizeLimitedRequest(request, 2);

        ServletInputStream stream = limited.getInputStream();

        assertThat(stream.read()).isEqualTo('x');
        assertThat(stream.read()).isEqualTo('x');
        assertThatExceptionOfType(RequestTooLargeException.class).isThrownBy(stream::read);
        assertThat(request.source.read).isEqualTo(3);
    }

    @Test
    void endOfStreamIsPassedThrough() throws Exception {
        SourceRequest request = new SourceRequest(1, -1);
        ServletInputStream stream = new SizeLimitedRequest(request, 10).getInputStream();

        assertThat(stream.read(new byte[4], 0, 4)).isEqualTo(1);
        assertThat(stream.read(new byte[4], 0, 4)).isEqualTo(-1);
        assertThat(stream.read()).isEqualTo(-1);
        assertThat(stream.isFinished()).isTrue();
        assertThat(stream.isReady()).isTrue();
        assertThat(stream.available()).isZero();
    }

    @Test
    void routeLimitReplacesTheDefault() throws Exception {
        int size = 1_572_864;
        SourceRequest request = new SourceRequest(size, size);
        HttpServletRequest limited = filter(request);

        RequestSizeLimits.apply(limited, DataSize.ofMegabytes(2));

        assertThat(limited.getInputStream().readAllBytes()).hasSize(size);
    }

    @Test
    void routeLimitIsFoundThroughOtherWrappers() throws Exception {
        SourceRequest request = new SourceRequest(20, 20);
        HttpServletRequest wrapped = new HttpServletRequestWrapper(filter(request));

        RequestSizeLimits.apply(wrapped, DataSize.ofBytes(10));

        assertThatExceptionOfType(RequestTooLargeException.class)
                .isThrownBy(wrapped::getInputStream)
                .satisfies(e -> assertThat(e.limit()).isEqualTo(10));
    }

    @Test
    void limitIsFixedOnceTheStreamIsOpen() throws Exception {
        SourceRequest request = new SourceRequest(20, -1);
        HttpServletRequest limited = new SizeLimitedRequest(request, 10);
        ServletInputStream stream = limited.getInputStream();

        RequestSizeLimits.apply(limited, DataSize.ofBytes(100));

        assertThatExceptionOfType(RequestTooLargeException.class).isThrownBy(stream::readAllBytes);
        assertThat(limited.getInputStream()).isSameAs(stream);
    }

    @Test
    void lateRouteLimitIsLoggedAtDebug() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level level = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            HttpServletRequest limited = new SizeLimitedRequest(new SourceRequest(5, -1), 10);
            RequestSizeLimits.apply(limited, DataSize.ofBytes(20));
            assertThat(appender.list).isEmpty();

            limited.getInputStream();
            RequestSizeLimits.apply(limited, DataSize.ofBytes(100));
        } finally {
            logger.setLevel(level);
            logger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Kept the request body limit of 20 bytes: the body stream was already open when "
                            + "the route limit of 100 bytes was applied to POST /books");
        });
    }

    @Test
    void failuresOfTheChainLeaveTheFilterUnchanged() {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(DataSize.ofBytes(10));
        // As Boot's FormContentFilter does: it reads the body before the DispatcherServlet and lets the failure out.
        // The client error filter inside this one answers it (ClientErrorFilterTest).
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatExceptionOfType(RequestTooLargeException.class)
                .isThrownBy(() -> filter
                        .doFilter(new SourceRequest(20, -1), response,
                                (request, out) -> request.getInputStream().readAllBytes()));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.isCommitted()).isFalse();

        IOException reset = new IOException("connection reset");
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> filter
                        .doFilter(new SourceRequest(5, -1), new MockHttpServletResponse(),
                                (request, out) -> {
                                    throw reset;
                                }))
                .isSameAs(reset);
    }

    @Test
    void applyWithoutTheFilterDoesNothing() throws Exception {
        SourceRequest request = new SourceRequest(20, 20);

        RequestSizeLimits.apply(request, DataSize.ofBytes(10));

        assertThat(request.getInputStream().readAllBytes()).hasSize(20);
    }

    @Test
    void readerIsLimitedToo() throws Exception {
        SourceRequest request = new SourceRequest(20, -1);
        request.setCharacterEncoding(StandardCharsets.UTF_8);
        HttpServletRequest limited = new SizeLimitedRequest(request, 10);

        BufferedReader reader = limited.getReader();

        assertThat(limited.getReader()).isSameAs(reader);
        assertThatExceptionOfType(RequestTooLargeException.class).isThrownBy(() -> drain(reader));
    }

    @Test
    void readerDecodesWithTheRequestEncoding() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/");
        request.setContent("é".getBytes(StandardCharsets.UTF_8));
        request.setCharacterEncoding(StandardCharsets.UTF_8);

        assertThat(new SizeLimitedRequest(request, 10).getReader().readLine()).isEqualTo("é");

        MockHttpServletRequest latin = new MockHttpServletRequest("POST", "/");
        latin.setContent("é".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(new SizeLimitedRequest(latin, 10).getReader().readLine()).isEqualTo("é");
    }

    @Test
    void filterOrderFollowsTheContextAndConcurrencyFilters() {
        assertThat(new RequestSizeLimitFilter(ONE_MB).getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 4);
    }

    @Test
    void failureIsFoundAnywhereInTheCauseChain() {
        RequestTooLargeException tooLarge = new RequestTooLargeException(1);
        IllegalStateException looping = new IllegalStateException("outer");
        IllegalStateException inner = new IllegalStateException("inner", looping);
        looping.initCause(inner);

        assertThat(RequestTooLargeException.isCauseOf(tooLarge)).isTrue();
        assertThat(RequestTooLargeException.isCauseOf(new IOException(new IllegalStateException(tooLarge)))).isTrue();
        assertThat(RequestTooLargeException.isCauseOf(new IOException("reset"))).isFalse();
        assertThat(RequestTooLargeException.isCauseOf(null)).isFalse();
        assertThat(RequestTooLargeException.isCauseOf(looping)).isFalse();
        assertThat(tooLarge.getMessage()).isEqualTo("The request body is larger than 1 bytes");
    }

    private static HttpServletRequest filter(MockHttpServletRequest request) throws Exception {
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        new RequestSizeLimitFilter(ONE_MB)
                .doFilter(request, new MockHttpServletResponse(),
                        (downstream, response) -> seen.set((HttpServletRequest) downstream));
        HttpServletRequest downstream = Objects.requireNonNull(seen.get(), "the chain was not called");
        assertThat(downstream).isInstanceOf(SizeLimitedRequest.class);
        return downstream;
    }

    private static void drain(Reader reader) throws IOException {
        char[] buffer = new char[4];
        while (reader.read(buffer) != -1) {
            // Read until the end or the limit.
        }
    }

    /** A source of {@code size} bytes that counts what is read from it. */
    static final class CountingSource extends InputStream {

        private final long size;

        long read;

        CountingSource(long size) {
            this.size = size;
        }

        @Override
        public int read() {
            if (read >= size) {
                return -1;
            }
            read++;
            return 'x';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (read >= size) {
                return -1;
            }
            int count = (int) Math.min(length, size - read);
            Arrays.fill(buffer, offset, offset + count, (byte) 'x');
            read += count;
            return count;
        }

        @Override
        public int available() {
            return 0;
        }
    }

    /** A request whose body is a {@link CountingSource}, with a declared length ({@code -1} for chunked). */
    static final class SourceRequest extends MockHttpServletRequest {

        final CountingSource source;

        private final long contentLength;

        boolean opened;

        SourceRequest(long size, long contentLength) {
            super("POST", "/books");
            this.source = new CountingSource(size);
            this.contentLength = contentLength;
        }

        @Override
        public long getContentLengthLong() {
            return contentLength;
        }

        @Override
        public ServletInputStream getInputStream() {
            opened = true;
            return new DelegatingServletInputStream(source);
        }
    }
}
