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
package io.americanexpress.synapse.data.autoconfigure;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.DefaultValue;

import io.americanexpress.synapse.data.internal.store.JdbcUrls;

/**
 * One store's settings, under {@code synapse.data.stores.<name>}. They are bound per store when the store is built,
 * never as a bean. The pool ({@code hikari.*}) and template ({@code jdbc.template.*}) settings are bound onto Boot's
 * and Hikari's own types instead. {@code url}, {@code username} and {@code password} have no default.
 *
 * @param url JDBC URL of the database, without a user name or password.
 * @param username User name to connect with.
 * @param password Password to connect with. May be empty.
 * @param driverClassName JDBC driver class. Derived from the URL when not set.
 * @param primary Whether the store's beans are the primary ones when the service has several stores.
 * @param databaseVersion Version of the database the store's SQL is generated for. Defaults to the vendor's.
 * @param allowDdlAuto Whether ddl-auto may create, drop or update the schema at startup.
 * @param migrations The store's schema migrations.
 * @param health The store's health indicator.
 */
public record SynapseDataStoreProperties(@Nullable String url, @Nullable String username, @Nullable String password,
        @Nullable String driverClassName, @DefaultValue("false") boolean primary, @Nullable String databaseVersion,
        @DefaultValue("false") boolean allowDdlAuto, @DefaultValue Migrations migrations, @DefaultValue Health health) {

    /**
     * Describes the settings without the credentials: the URL is shown only as its scheme (such as
     * {@code jdbc:postgresql}), because it may carry a user name or password, and {@code username} and
     * {@code password} are left out.
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "SynapseDataStoreProperties[url=" + (url == null ? null : JdbcUrls.scheme(url))
                + ", driverClassName=" + driverClassName + ", primary=" + primary + ", databaseVersion="
                + databaseVersion + ", allowDdlAuto=" + allowDdlAuto + ", migrations=" + migrations + ", health="
                + health + "]";
    }

    /**
     * A store's schema migrations.
     *
     * @param enabled Whether the store's migrations run at startup.
     * @param locations Where the migrations are. Defaults to classpath:db/migration/ followed by the store name.
     * @param flyway Flyway settings, by Flyway's own key without the flyway. prefix, such as baseline-on-migrate. url,
     *        user, password and locations are rejected: Flyway uses the store's DataSource and
     *        migrations.locations.
     */
    public record Migrations(@DefaultValue("true") boolean enabled, @Nullable List<String> locations,
            @DefaultValue Map<String, String> flyway) {

        /**
         * Describes the settings with the Flyway keys only, since a Flyway value may be a credential.
         *
         * @return the description
         */
        @Override
        public String toString() {
            return "Migrations[enabled=" + enabled + ", locations=" + locations + ", flyway=" + flyway.keySet() + "]";
        }
    }

    /**
     * A store's health indicator.
     *
     * @param readiness Whether the store is part of the readiness group.
     * @param timeout How long the indicator waits for a connection.
     */
    public record Health(@DefaultValue("false") boolean readiness, @DefaultValue("1s") Duration timeout) {
    }
}
