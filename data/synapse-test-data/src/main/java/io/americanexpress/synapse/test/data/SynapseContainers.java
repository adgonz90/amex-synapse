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
package io.americanexpress.synapse.test.data;

import java.util.HashMap;
import java.util.Map;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL containers shared by every test class of a JVM: one per key, image {@value #POSTGRES_IMAGE}, started on
 * first use and never stopped (Testcontainers removes them when the JVM ends). Test classes that use the same key
 * share one database, so their contexts can be cached together; two keys give two databases, such as one per store.
 * A {@link SynapseDataTest} connects its store to one with {@link StoreConnection}:
 * {@code @StoreConnection("books") static PostgreSQLContainer books = SynapseContainers.postgres();}.
 */
public final class SynapseContainers {

    /**
     * The PostgreSQL image: {@code postgres:18.6-alpine}, by the digest that tag had on 2026-11-07, so a re-pushed tag
     * can't change the image under the tests. The reference names the digest only: Testcontainers 2.0.5 reads
     * {@code postgres:18.6-alpine@sha256:...} as the repository {@code postgres:18.6-alpine}, which it then rejects as
     * a substitute for {@code postgres}.
     */
    public static final String POSTGRES_IMAGE = "postgres@sha256:"
            + "123b17998ccaf47e53b502e8c6a1e79afd8f579c6f3eec83c563da026f957395";

    /** The key of {@link #postgres()}. */
    private static final String DEFAULT_KEY = "default";

    private static final Map<String, PostgreSQLContainer> POSTGRES = new HashMap<>();

    private SynapseContainers() {
    }

    /**
     * Returns the shared PostgreSQL container, the one of {@code postgres("default")}, starting it on first use.
     *
     * @return the running container
     */
    public static PostgreSQLContainer postgres() {
        return postgres(DEFAULT_KEY);
    }

    /**
     * Returns the PostgreSQL container shared under a key, starting it on first use.
     *
     * @param key the key, such as a store name
     * @return the running container
     */
    public static synchronized PostgreSQLContainer postgres(String key) {
        PostgreSQLContainer container = POSTGRES.get(key);
        if (container == null) {
            container = new PostgreSQLContainer(POSTGRES_IMAGE);
            container.start();
            POSTGRES.put(key, container);
        }
        return container;
    }
}
