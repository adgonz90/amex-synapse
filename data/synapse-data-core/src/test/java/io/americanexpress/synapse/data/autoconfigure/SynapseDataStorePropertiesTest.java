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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseDataStorePropertiesTest {

    private static final String SECRET = "s3cr3t";

    @Test
    void toStringShowsOnlyTheSchemeAndNoCredentials() {
        SynapseDataStoreProperties properties = new SynapseDataStoreProperties(
                "jdbc:postgresql://app:" + SECRET + "@db/books?password=" + SECRET, "app-user", SECRET,
                "org.postgresql.Driver", true, "16", false,
                new SynapseDataStoreProperties.Migrations(true, List.of("classpath:db/books"),
                        Map.of("password", SECRET)),
                new SynapseDataStoreProperties.Health(true, Duration.ofMillis(250)));

        assertThat(properties.toString())
                .doesNotContain(SECRET, "app-user", "db/books?")
                .startsWith("SynapseDataStoreProperties[url=jdbc:postgresql, driverClassName=org.postgresql.Driver, "
                        + "primary=true, databaseVersion=16, allowDdlAuto=false, migrations=Migrations[enabled=true, "
                        + "locations=[classpath:db/books], flyway=[password]], health=Health[readiness=true, "
                        + "timeout=PT0.25S]]");
    }

    @Test
    void toStringWithoutAUrl() {
        SynapseDataStoreProperties properties = new SynapseDataStoreProperties(null, null, null, null, false, null,
                false, new SynapseDataStoreProperties.Migrations(true, null, Map.of()),
                new SynapseDataStoreProperties.Health(false, Duration.ofSeconds(1)));

        assertThat(properties.toString()).startsWith("SynapseDataStoreProperties[url=null, driverClassName=null,");
    }
}
