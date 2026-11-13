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
package ${package}.api.rest;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.americanexpress.synapse.test.rest.SynapseRestTest;

import ${package}.contract.CreateItemRequest;
import ${package}.contract.GetItemRequest;
import ${package}.contract.Item;
import ${package}.operation.CreateItemOperation;
import ${package}.operation.GetItemOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * The REST slice: the bindings, the edge and the problem responses on MockMvc, with every operation mocked. It checks
 * how requests bind to the operations' inputs and how outputs map to responses, including what only REST has: the
 * conditional GET (304). The answers both protocols must give alike are the scenarios of {@code ItemScenarios}; the
 * operations are tested in {@code ItemOperationsTest}. No store starts.
 */
@SynapseRestTest
class ItemRestTest {

    private static final UUID ID = UUID.fromString("019a5e3c-4f00-7000-8000-000000000001");

    private static final Item LAMP = new Item(ID, "Lamp", "A desk lamp", 4);

    @Autowired
    RestTestClient client;

    @Autowired
    ApplicationContext context;

    @MockitoBean
    CreateItemOperation createItem;

    @MockitoBean
    GetItemOperation getItem;

    @Test
    void createIs201WithLocationAndEtag() {
        given(createItem.execute(new CreateItemRequest("Lamp", "A desk lamp"))).willReturn(LAMP);

        client
                .post()
                .uri("/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"Lamp\",\"description\":\"A desk lamp\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectHeader()
                .valueEquals("Location", "/items/" + ID)
                .expectHeader()
                .valueEquals("ETag", "\"4\"")
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(ID.toString())
                .jsonPath("$.description")
                .isEqualTo("A desk lamp")
                .jsonPath("$.version")
                .isEqualTo(4);
    }

    @Test
    void aMissingDescriptionIsNull() {
        given(createItem.execute(any())).willReturn(LAMP);

        client
                .post()
                .uri("/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"Lamp\"}")
                .exchange()
                .expectStatus()
                .isCreated();

        then(createItem).should().execute(new CreateItemRequest("Lamp", null));
    }

    @Test
    void getIs200WithEtagAndAnswersIfNoneMatchWith304() {
        given(getItem.execute(new GetItemRequest(ID))).willReturn(Optional.of(LAMP));

        client
                .get()
                .uri("/items/{id}", ID)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("ETag", "\"4\"")
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("Lamp");

        // HTTP caching, REST only: gRPC has no 304, and GetItem takes no version.
        client
                .get()
                .uri("/items/{id}", ID)
                .header("If-None-Match", "\"4\"")
                .exchange()
                .expectStatus()
                .isNotModified()
                .expectHeader()
                .valueEquals("ETag", "\"4\"")
                .expectBody()
                .isEmpty();
    }

    @Test
    void anUnknownItemIs404ItemNotFound() {
        given(getItem.execute(new GetItemRequest(ID))).willReturn(Optional.empty());

        client
                .get()
                .uri("/items/{id}", ID)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ITEM_NOT_FOUND");
    }

    @Test
    void anIdThatIsNotAUuidIsAViolation() {
        client
                .get()
                .uri("/items/not-a-uuid")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.violations[0].field")
                .isEqualTo("id");
        then(getItem).should(never()).execute(any());
    }

    @Test
    void noStoreStarts() {
        assertThat(context.containsBean("${serviceName}DataSource")).isFalse();
    }
}
