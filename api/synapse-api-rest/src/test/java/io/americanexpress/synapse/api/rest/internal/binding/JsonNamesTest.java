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
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonDeserialize;
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
    void serializedNamesFollowTheStrategyAndKeepReadOnlyComponents() {
        JsonMapper snakeCase = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

        assertThat(JsonNames.serialized(snakeCase, PublishBookRequest.class))
                .containsExactly(entry("publishedOn", "published_on"), entry("isbn", "isbn13"));
        assertThat(JsonNames.serialized(JsonMapper.builder().build(), GuardedRequest.class))
                .containsExactly(entry("name", "name"), entry("role", "role"));
    }

    @Test
    void serializedNamesLeaveOutWriteOnlyAndNotIncludedComponents() {
        assertThat(JsonNames.serialized(JsonMapper.builder().build(), Credentials.class))
                .containsExactly(entry("user", "user"));
        assertThat(JsonNames.serialized(JsonMapper.builder().build(), Narrowed.class))
                .containsExactly(entry("kept", "kept"));
    }

    @Test
    void serializedNamesLeaveOutPropertiesOfIgnoredTypes() {
        JsonMapper mapper = JsonMapper.builder().build();

        assertThat(JsonNames.serialized(mapper, Audited.class)).containsExactly(entry("title", "title"));
        assertThat(mapper.writeValueAsString(new Audited("t", new AuditTrail("x")))).isEqualTo("{\"title\":\"t\"}");
    }

    @Test
    void omittableFollowsTheEffectiveInclusion() {
        JsonMapper mapper = JsonMapper.builder().build();
        JsonMapper nonEmpty = JsonMapper
                .builder()
                .changeDefaultPropertyInclusion(
                        inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_EMPTY))
                .build();

        assertThat(JsonNames.omittable(mapper, Sparse.class)).containsExactly("note", "count");
        assertThat(JsonNames.omittable(mapper, Credentials.class)).isEmpty();
        assertThat(JsonNames.omittable(nonEmpty, Credentials.class)).containsExactly("user");
        assertThat(JsonNames.omittable(nonEmpty, Kept.class)).isEmpty();
        assertThat(mapper.writeValueAsString(new Sparse("", "t", 0))).isEqualTo("{\"title\":\"t\"}");
        assertThat(nonEmpty.writeValueAsString(new Credentials("", "p"))).isEqualTo("{}");
    }

    @Test
    void nonAbsentLeavesOutOnlyEmptyReferenceTypes() {
        JsonMapper mapper = JsonMapper.builder().build();

        // For a type that isn't a reference type, NON_ABSENT acts as NON_NULL.
        assertThat(JsonNames.omittable(mapper, Absent.class)).containsExactly("maybe");
        assertThat(mapper.writeValueAsString(new Absent("t", Optional.empty()))).isEqualTo("{\"title\":\"t\"}");
    }

    record Credentials(String user, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
    }

    record Sparse(@JsonInclude(JsonInclude.Include.NON_EMPTY) String note, String title,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) int count) {
    }

    /** The mapper's NON_EMPTY default, overridden on the class. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Kept(String user) {
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record Absent(String title, Optional<String> maybe) {
    }

    record Audited(String title, AuditTrail trail) {
    }

    @JsonIgnoreType
    record AuditTrail(String entry) {
    }

    @JsonIncludeProperties("kept")
    record Narrowed(String kept, String dropped) {
    }

    /** Jackson reads it from an object but never sets its component. */
    record Stamp(@JsonProperty(access = JsonProperty.Access.READ_ONLY) String at) {
    }

    /** Jackson reads it from its value alone. */
    record Token(@JsonValue String value) {
    }

    record Empty() {
    }

    /** Read through a delegating factory, from any object: not by its components. */
    record DelFactory(@Nullable String a, @Nullable String b) {

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static DelFactory of(Map<String, String> values) {
            return new DelFactory(values.get("x"), values.get("y"));
        }
    }

    /** A properties constructor and a delegating factory: Jackson reads it through the delegate. */
    record Both(String a, int n) {

        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        Both(@JsonProperty("a") String a, @JsonProperty("n") int n) {
            this.a = a;
            this.n = n;
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static Both of(Map<String, Object> values) {
            return new Both("delegate:" + values, -1);
        }
    }

    /** Read by its builder. */
    @JsonDeserialize(builder = Built.Builder.class)
    record Built(@Nullable String a) {

        static final class Builder {

            private @Nullable String a;

            Builder withA(@Nullable String a) {
                this.a = a;
                return this;
            }

            Built build() {
                return new Built(a);
            }
        }
    }

    @Test
    void readsByPropertyTellsAnObjectWithoutReadComponentsFromAValueRecord() {
        JsonMapper mapper = JsonMapper.builder().build();

        assertThat(JsonNames.of(mapper, Stamp.class)).isEmpty();
        assertThat(JsonNames.of(mapper, Token.class)).isEmpty();
        assertThat(JsonNames.readsByProperty(mapper, Stamp.class)).isTrue();
        assertThat(JsonNames.readsByProperty(mapper, Empty.class)).isTrue();
        assertThat(JsonNames.readsByProperty(mapper, PublishBookRequest.class)).isTrue();
        assertThat(JsonNames.readsByProperty(mapper, Token.class)).isFalse();
        assertThat(JsonNames.readsByProperty(mapper, String.class)).isFalse();
        // Jackson reads these from an object, but not by their components.
        assertThat(mapper.readValue("{\"x\":\"1\",\"y\":\"2\"}", DelFactory.class)).isEqualTo(new DelFactory("1", "2"));
        assertThat(mapper.readValue("{\"a\":\"x\",\"n\":1}", Both.class).n()).isEqualTo(-1);
        assertThat(mapper.readValue("{\"a\":\"x\"}", Built.class)).isEqualTo(new Built("x"));
        assertThat(JsonNames.readsByProperty(mapper, DelFactory.class)).isFalse();
        assertThat(JsonNames.readsByProperty(mapper, Both.class)).isFalse();
        assertThat(JsonNames.readsByProperty(mapper, Built.class)).isFalse();
    }

    @Test
    void keepsComponentNamesWithoutAStrategy() {
        assertThat(JsonNames.of(JsonMapper.builder().build(), PublishBookRequest.class))
                .containsExactly(entry("publishedOn", "publishedOn"), entry("isbn", "isbn13"));
    }
}
