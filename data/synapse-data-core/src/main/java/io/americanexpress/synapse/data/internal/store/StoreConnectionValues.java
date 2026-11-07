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
package io.americanexpress.synapse.data.internal.store;

import org.jspecify.annotations.Nullable;

/**
 * Where a store connects: from its qualified {@code JdbcConnectionDetails}, or else its properties. Never logged.
 *
 * @param url the JDBC URL
 * @param username the user name; {@code null} only when connection details give none
 * @param password the password; {@code null} only when connection details give none
 * @param driverClassName the driver class, or {@code null} to derive it from the URL
 */
record StoreConnectionValues(String url, @Nullable String username, @Nullable String password,
        @Nullable String driverClassName) {

    @Override
    public String toString() {
        return "StoreConnectionValues[" + JdbcUrls.scheme(url) + "]";
    }
}
