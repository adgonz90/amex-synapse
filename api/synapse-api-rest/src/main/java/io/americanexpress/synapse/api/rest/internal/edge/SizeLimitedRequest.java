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
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.jspecify.annotations.Nullable;

/**
 * A request whose body may not be larger than a limit. The first {@link #getInputStream()} fails with
 * {@link RequestTooLargeException}, without reading anything, when the declared {@code Content-Length} is over the
 * limit; otherwise it returns a stream that fails once more than the limit has been read (a chunked body has no declared
 * length). {@link #getReader()} reads through that stream. Until the stream is opened, a route can replace the limit
 * ({@link RequestSizeLimits#apply}).
 *
 * <p>
 * Form parameters that the servlet container parses itself ({@code getParameter} on a form post) don't go through this
 * stream; Tomcat limits them with {@code server.tomcat.max-http-form-post-size}.
 */
public final class SizeLimitedRequest extends HttpServletRequestWrapper {

    private long limit;

    private @Nullable LimitedInputStream stream;

    private @Nullable BufferedReader reader;

    /**
     * Wraps the request.
     *
     * @param request the request
     * @param limit the largest body accepted, in bytes
     */
    public SizeLimitedRequest(HttpServletRequest request, long limit) {
        super(request);
        this.limit = limit;
    }

    /**
     * Returns the limit in force.
     *
     * @return the largest body accepted, in bytes
     */
    long limit() {
        return limit;
    }

    /**
     * Replaces the limit, unless the body stream is already open.
     *
     * @param limit the largest body accepted, in bytes
     * @return true when the limit was replaced
     */
    boolean limit(long limit) {
        if (stream != null) {
            return false;
        }
        this.limit = limit;
        return true;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        LimitedInputStream current = stream;
        if (current == null) {
            if (getContentLengthLong() > limit) {
                throw new RequestTooLargeException(limit);
            }
            current = new LimitedInputStream(super.getInputStream(), limit);
            stream = current;
        }
        return current;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        BufferedReader current = reader;
        if (current == null) {
            current = new BufferedReader(new InputStreamReader(getInputStream(), charset()));
            reader = current;
        }
        return current;
    }

    /** The request's character encoding, or ISO-8859-1, the Servlet default. */
    private Charset charset() {
        String encoding = getCharacterEncoding();
        return encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
    }

    /** Counts the bytes read and fails once there are more than the limit. */
    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;

        private final long limit;

        private long count;

        LimitedInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            checkLimit();
            int value = delegate.read();
            if (value != -1) {
                count++;
                checkLimit();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            checkLimit();
            // Never read more than one byte past the limit from the source.
            long remaining = limit - count;
            int allowed = remaining >= length ? length : (int) remaining + 1;
            int bytes = delegate.read(buffer, offset, allowed);
            if (bytes > 0) {
                count += bytes;
                checkLimit();
            }
            return bytes;
        }

        private void checkLimit() throws RequestTooLargeException {
            if (count > limit) {
                throw new RequestTooLargeException(limit);
            }
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
