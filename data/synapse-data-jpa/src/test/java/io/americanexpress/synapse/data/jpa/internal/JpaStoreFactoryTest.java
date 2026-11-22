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
package io.americanexpress.synapse.data.jpa.internal;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JpaStoreFactoryTest {

    private static final String MAJOR = "jakarta.persistence.database-major-version";

    private static final String MINOR = "jakarta.persistence.database-minor-version";

    @Test
    void majorAndMinorVersionComeFromTheVersion() {
        assertThat(versions("2.3.232")).containsExactlyInAnyOrderEntriesOf(Map.of(MAJOR, "2", MINOR, "3"));
        assertThat(versions("15")).containsExactlyInAnyOrderEntriesOf(Map.of(MAJOR, "15", MINOR, "0"));
        assertThat(versions(" 16.4 ")).containsExactlyInAnyOrderEntriesOf(Map.of(MAJOR, "16", MINOR, "4"));
    }

    @Test
    void aVersionThatIsNotNumericAddsNeither() {
        assertThat(versions("latest")).isEmpty();
        assertThat(versions("15.beta")).isEmpty();
    }

    /** The same precedence as the product version, which Synapse always sets from the store's database-version. */
    @Test
    void theStoresVersionWinsOverRawKeys() {
        Map<String, Object> properties = new HashMap<>(Map.of(MAJOR, "14", MINOR, "9"));
        JpaStoreFactory.putMajorAndMinorVersion(properties, "15.1");
        assertThat(properties).containsExactlyInAnyOrderEntriesOf(Map.of(MAJOR, "15", MINOR, "1"));
    }

    private static Map<String, Object> versions(String version) {
        Map<String, Object> properties = new HashMap<>();
        JpaStoreFactory.putMajorAndMinorVersion(properties, version);
        return properties;
    }
}
