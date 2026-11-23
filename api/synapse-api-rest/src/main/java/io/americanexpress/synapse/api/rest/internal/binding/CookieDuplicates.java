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
package io.americanexpress.synapse.api.rest.internal.binding;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a request that sent a cookie bound to a single-valued component more than once, whose first copy the
 * binder takes (as Spring's {@code @CookieValue} does): one DEBUG line naming the cookie, and one increment of the
 * counter {@value #METER}.
 *
 * <p>
 * The counter is tagged {@code cookie}, the name the binding declares (never a name read from the request, so the
 * series are bounded by the routes), and {@code operation}, the route's operation name. Each single-valued cookie
 * binding is registered at startup, so its series exists at 0 before any duplicate. Without a {@link MeterRegistry}
 * nothing is counted; the DEBUG line is still logged. Neither the line nor the counter carries a cookie's value.
 */
public final class CookieDuplicates {

    /** The name of the counter. */
    public static final String METER = "synapse.rest.cookie.duplicates";

    private static final String DESCRIPTION = "Requests that sent a cookie bound to a single-valued component more "
            + "than once; the first copy was used";

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    private static final CookieDuplicates NONE = new CookieDuplicates(null);

    private final @Nullable MeterRegistry registry;

    /** The counter of each registered or recorded binding; a binding's names are declared, so the map is bounded. */
    private final Map<Series, Counter> counters = new ConcurrentHashMap<>();

    /**
     * Creates the recorder.
     *
     * @param registry the registry the counter is registered with, or {@code null} to count nothing
     */
    public CookieDuplicates(@Nullable MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns a recorder without a registry: it counts nothing and only logs.
     *
     * @return the recorder
     */
    public static CookieDuplicates none() {
        return NONE;
    }

    /**
     * Registers the counter of a single-valued cookie binding at 0, at startup.
     *
     * @param operation the route's operation name
     * @param cookie the cookie name the binding declares
     */
    void register(String operation, String cookie) {
        counter(operation, cookie);
    }

    /**
     * Records one request that sent the cookie more than once: one DEBUG line by name, and one increment.
     *
     * @param operation the route's operation name
     * @param cookie the cookie name the binding declares
     */
    void record(String operation, String cookie) {
        LOG.debug("Request sent cookie {} more than once; the first copy is used", cookie);
        Counter counter = counter(operation, cookie);
        if (counter != null) {
            counter.increment();
        }
    }

    private @Nullable Counter counter(String operation, String cookie) {
        MeterRegistry meters = registry;
        if (meters == null) {
            return null;
        }
        return counters
                .computeIfAbsent(new Series(operation, cookie), series -> Counter
                        .builder(METER)
                        .description(DESCRIPTION)
                        .baseUnit("requests")
                        .tag("cookie", series.cookie())
                        .tag("operation", series.operation())
                        .register(meters));
    }

    /** One series of the counter. */
    private record Series(String operation, String cookie) {
    }
}
