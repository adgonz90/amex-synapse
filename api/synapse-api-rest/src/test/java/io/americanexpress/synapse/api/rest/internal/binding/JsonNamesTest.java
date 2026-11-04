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

import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.AuditedBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GuardedRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PublishBookRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class JsonNamesTest {

    @Test
    void honoursNamingStrategyAndJsonProperty() {
        JsonMapper snakeCase = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

        assertThat(JsonNames.of(snakeCase, PublishBookRequest.class))
                .containsExactly(entry("publishedOn", "published_on"), entry("isbn", "isbn13"));
    }

    @Test
    void leavesOutIgnoredComponents() {
        assertThat(JsonNames.of(JsonMapper.builder().build(), AuditedBookRequest.class))
                .containsExactly(entry("title", "title"));
    }

    @Test
    void leavesOutEveryComponentJacksonWouldNotRead() {
        assertThat(JsonNames.of(JsonMapper.builder().build(), GuardedRequest.class))
                .containsExactly(entry("name", "name"));
    }

    @Test
    void keepsComponentNamesWithoutAStrategy() {
        assertThat(JsonNames.of(JsonMapper.builder().build(), PublishBookRequest.class))
                .containsExactly(entry("publishedOn", "publishedOn"), entry("isbn", "isbn13"));
    }
}
