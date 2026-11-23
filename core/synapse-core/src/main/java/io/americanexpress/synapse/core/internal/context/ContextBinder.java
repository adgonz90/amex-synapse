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
package io.americanexpress.synapse.core.internal.context;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.americanexpress.synapse.core.context.RequestContext;

/**
 * Opens the Synapse request context: validates the correlation id, applies the inbound trust setting and limits,
 * binds the context value to the thread and opens tracing baggage for the correlation id and each kept attribute.
 */
public final class ContextBinder {

    /** The baggage field (and MDC key) of the correlation id. */
    public static final String CORRELATION_BAGGAGE_FIELD = "correlationId";

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextBinder.class);

    /** The innermost open scope of this thread, used to skip scopes that were closed out of order. */
    private static final ThreadLocal<@Nullable Scope> OPEN = new ThreadLocal<>();

    private final Tracer tracer;
    private final CorrelationIdPolicy correlation;
    private final boolean trusted;
    private final int maxEntries;
    private final long maxSizeBytes;
    private final RateLimitedWarn dropped;
    private final RateLimitedWarn misuse;
    private final RateLimitedWarn droppedUnsafe;

    /**
     * Creates a binder that uses the system clock for its log rate limit.
     *
     * @param tracer the tracer that holds baggage
     * @param correlation the correlation id policy
     * @param trusted whether inbound attributes are accepted
     * @param maxEntries the most attributes kept
     * @param maxSizeBytes the most UTF-8 bytes of attribute names and values kept
     */
    public ContextBinder(Tracer tracer, CorrelationIdPolicy correlation, boolean trusted, int maxEntries,
            long maxSizeBytes) {
        this(tracer, correlation, trusted, maxEntries, maxSizeBytes, Clock.systemUTC());
    }

    /**
     * Creates a binder.
     *
     * @param tracer the tracer that holds baggage
     * @param correlation the correlation id policy
     * @param trusted whether inbound attributes are accepted
     * @param maxEntries the most attributes kept
     * @param maxSizeBytes the most UTF-8 bytes of attribute names and values kept
     * @param clock the clock that drives the log rate limit
     */
    public ContextBinder(Tracer tracer, CorrelationIdPolicy correlation, boolean trusted, int maxEntries,
            long maxSizeBytes, Clock clock) {
        this.tracer = tracer;
        this.correlation = correlation;
        this.trusted = trusted;
        this.maxEntries = maxEntries;
        this.maxSizeBytes = maxSizeBytes;
        this.dropped = new RateLimitedWarn(LOGGER, clock, Duration.ofMinutes(1));
        this.misuse = new RateLimitedWarn(LOGGER, clock, Duration.ofMinutes(1));
        this.droppedUnsafe = new RateLimitedWarn(LOGGER, clock, Duration.ofMinutes(1));
    }

    /**
     * Opens a context for the current thread. The caller must close the returned scope on this same thread, innermost
     * first, normally with try-with-resources. A scope closed on another thread, or before the scopes opened inside it,
     * is logged as a warning and left alone; it is unwound on this thread when the inner scopes close or when this
     * thread next opens a context.
     *
     * <p>
     * Open it inside the span of the server observation (the request's span must be in scope): the baggage this
     * method opens is attached to the current span. Without a span in scope, the context value is bound to this thread
     * but the correlation id does not reach the MDC of this thread, and the baggage does not propagate to
     * {@code @Async} or other context-propagating threads. An inbound edge therefore opens the Synapse scope after its
     * observation has opened its span; the pipeline, which opens a scope for a call that has none, does so inside the
     * operation's own observation.
     *
     * @param inbound what the edge read from the request
     * @return the open scope
     */
    public ContextScope open(InboundContext inbound) {
        unwindClosedScopes(true);
        String id = correlation.acceptOrGenerate(inbound.correlationId());
        Map<String, String> attributes = limit(inbound.attributes());
        ContextValue value = new ContextValue(id, inbound.principal(), inbound.locale(), inbound.deadline(),
                Collections.unmodifiableMap(attributes));
        ContextValue previous = ContextValue.currentOrNull();
        ContextValue.bind(value);
        List<BaggageInScope> baggage = new ArrayList<>();
        try {
            baggage.add(tracer.createBaggageInScope(CORRELATION_BAGGAGE_FIELD, id));
            for (Map.Entry<String, String> attribute : attributes.entrySet()) {
                baggage.add(tracer.createBaggageInScope(attribute.getKey(), attribute.getValue()));
            }
        } catch (RuntimeException | Error ex) {
            try {
                release(baggage, previous);
            } catch (RuntimeException suppressed) {
                ex.addSuppressed(suppressed);
            }
            throw ex;
        }
        return new Scope(value, baggage, previous, misuse);
    }

    private Map<String, String> limit(Map<String, String> candidates) {
        Map<String, String> kept = new LinkedHashMap<>();
        if (!trusted) {
            if (!candidates.isEmpty()) {
                dropped
                        .log("Dropped {} inbound context attribute(s): inbound context is not trusted",
                                candidates.size());
            }
            return kept;
        }
        Map<String, String> safe = new LinkedHashMap<>();
        int unsafe = 0;
        for (Map.Entry<String, String> candidate : candidates.entrySet()) {
            if (isUnsafe(candidate.getKey()) || isUnsafe(candidate.getValue())) {
                unsafe++;
            } else {
                safe.put(candidate.getKey(), candidate.getValue());
            }
        }
        if (unsafe > 0) {
            droppedUnsafe.log("Dropped {} inbound context attribute(s) containing a control character", unsafe);
        }
        long size = 0;
        for (Map.Entry<String, String> candidate : safe.entrySet()) {
            long entrySize = entrySize(utf8Length(candidate.getKey()), utf8Length(candidate.getValue()));
            if (kept.size() >= maxEntries || size + entrySize > maxSizeBytes) {
                dropped
                        .log("Dropped {} inbound context attribute(s) over the limit of {} entries and {} bytes",
                                safe.size() - kept.size(), maxEntries, maxSizeBytes);
                break;
            }
            kept.put(candidate.getKey(), candidate.getValue());
            size += entrySize;
        }
        return kept;
    }

    /** Control characters and line or paragraph separators could forge log lines or header values. */
    private static boolean isUnsafe(String text) {
        return text
                .codePoints()
                .anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.LINE_SEPARATOR
                        || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR);
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * The bytes an attribute counts against the size limit.
     *
     * @param nameBytes the UTF-8 length of its name
     * @param valueBytes the UTF-8 length of its value
     * @return their sum, computed as a long so that it can't wrap to a negative size that passes the limit
     */
    static long entrySize(int nameBytes, int valueBytes) {
        return (long) nameBytes + valueBytes;
    }

    /** Closes baggage in reverse order, then restores the previous value even if a close threw. */
    private static void release(List<BaggageInScope> baggage, @Nullable ContextValue previous) {
        RuntimeException failure = null;
        try {
            for (int i = baggage.size() - 1; i >= 0; i--) {
                try {
                    baggage.get(i).close();
                } catch (RuntimeException ex) {
                    if (failure == null) {
                        failure = ex;
                    } else {
                        failure.addSuppressed(ex);
                    }
                }
            }
        } finally {
            ContextValue.bind(previous);
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Unwinds the scopes of this thread that were closed while another scope was still open (or from another thread):
     * closes their baggage, innermost first, and restores the value each one replaced. Stops at the first scope that is
     * still open.
     *
     * @param quiet whether a failure is logged instead of thrown
     */
    private static void unwindClosedScopes(boolean quiet) {
        RuntimeException failure = null;
        Scope top = OPEN.get();
        while (top != null && top.closed.get()) {
            Scope below = top.previousScope;
            if (below == null) {
                OPEN.remove();
            } else {
                OPEN.set(below);
            }
            try {
                release(top.baggage, top.previous);
            } catch (RuntimeException ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
            top = below;
        }
        if (failure != null) {
            if (quiet) {
                LOGGER.warn("Failed to close a Synapse request context that was closed out of order", failure);
            } else {
                throw failure;
            }
        }
    }

    private static final class Scope implements ContextScope {

        private final ContextValue value;
        private final List<BaggageInScope> baggage;
        private final @Nullable ContextValue previous;
        private final Thread opener = Thread.currentThread();
        private final @Nullable Scope previousScope = OPEN.get();
        private final RateLimitedWarn misuse;
        private final AtomicBoolean closed = new AtomicBoolean();

        Scope(ContextValue value, List<BaggageInScope> baggage, @Nullable ContextValue previous,
                RateLimitedWarn misuse) {
            this.value = value;
            this.baggage = baggage;
            this.previous = previous;
            this.misuse = misuse;
            OPEN.set(this);
        }

        @Override
        public RequestContext context() {
            return value;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Thread current = Thread.currentThread();
            if (current == opener && OPEN.get() == this) {
                unwindClosedScopes(false);
            } else if (current != opener) {
                misuse
                        .log("Closed a Synapse request context on a different thread ({}) than the one that opened it ({}); "
                                + "it is unwound when its opening thread next opens or closes a context",
                                current.getName(), opener.getName());
            } else {
                misuse
                        .log("Closed a Synapse request context out of order: it is not the innermost open context; "
                                + "it is unwound when the inner contexts close");
            }
        }
    }
}
