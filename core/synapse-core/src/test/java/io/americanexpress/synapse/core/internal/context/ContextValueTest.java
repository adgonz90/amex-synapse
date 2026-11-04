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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.security.auth.x500.X500Principal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextValueTest {

    @Test
    void toStringLeavesOutThePrincipalAndTheAttributeValues() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tenant", "acme-secret");
        attributes.put("channel", "mobile-secret");
        ContextValue value = new ContextValue("c-1", new X500Principal("CN=tony"), Locale.FRANCE,
                Instant.parse("2026-01-01T00:00:05Z"), attributes);

        assertThat(value.toString())
                .isEqualTo("ContextValue[correlationId=c-1, locale=fr_FR, deadline=2026-01-01T00:00:05Z, "
                        + "principal=present, attributes=[tenant, channel]]")
                .doesNotContain("tony")
                .doesNotContain("secret");
    }

    @Test
    void toStringShowsWhatIsAbsent() {
        ContextValue value = new ContextValue("c-2", null, null, null, Map.of());

        assertThat(value.toString())
                .isEqualTo(
                        "ContextValue[correlationId=c-2, locale=null, deadline=null, principal=absent, attributes=[]]");
    }
}
