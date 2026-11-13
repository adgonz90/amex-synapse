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
package io.americanexpress.synapse.samples.multistore.api.rest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.samples.multistore.model.DeleteOrderRequest;
import io.americanexpress.synapse.samples.multistore.model.GetOrderRequest;
import io.americanexpress.synapse.samples.multistore.model.Order;
import io.americanexpress.synapse.samples.multistore.model.PlaceOrderRequest;
import io.americanexpress.synapse.samples.multistore.operation.CancelOrderOperation;
import io.americanexpress.synapse.samples.multistore.operation.GetOrderOperation;
import io.americanexpress.synapse.samples.multistore.operation.PlaceOrderOperation;
import io.americanexpress.synapse.test.rest.SynapseRestTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * The REST slice: the bindings, the edge filters and the problem responses on MockMvc, with every operation mocked.
 * The mocks run without the pipeline, so the inputs' Jakarta Validation constraints are checked in
 * {@code OrdersApplicationIT}; a missing component is still a violation here, since binding finds it. No store starts and nothing connects to a database; the stores are tested in the data modules and in
 * {@code OrdersApplicationIT}.
 */
@SynapseRestTest
class OrdersRestTest {

    private static final UUID ID = UUID.fromString("019a5e3c-4f00-7000-8000-000000000001");

    private static final Order ORDER = new Order(ID, "Ada", new BigDecimal("12.50"),
            Instant.parse("2026-11-07T10:15:30Z"), "system", 0);

    @Autowired
    RestTestClient client;

    @Autowired
    ApplicationContext context;

    @MockitoBean
    PlaceOrderOperation placeOrder;

    @MockitoBean
    GetOrderOperation getOrder;

    @MockitoBean
    CancelOrderOperation cancelOrder;

    @Test
    void placeIs201WithLocationAndEtag() {
        given(placeOrder.execute(any())).willReturn(ORDER);

        client
                .post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"customer\": \"Ada\", \"amount\": 12.50}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectHeader()
                .valueEquals("Location", "/orders/" + ID)
                .expectHeader()
                .valueEquals("ETag", "\"0\"")
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(ID.toString())
                .jsonPath("$.amount")
                .isEqualTo(12.50)
                .jsonPath("$.createdBy")
                .isEqualTo("system");
        ArgumentCaptor<PlaceOrderRequest> request = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        then(placeOrder).should().execute(request.capture());
        assertThat(request.getValue().customer()).isEqualTo("Ada");
        assertThat(request.getValue().amount()).isEqualByComparingTo("12.50");
    }

    @Test
    void aMissingAmountIsAViolationBeforeTheOperationRuns() {
        client
                .post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"customer\": \"Ada\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.violations[0].field")
                .isEqualTo("amount");
        then(placeOrder).should(never()).execute(any());
    }

    @Test
    void getIs200WithEtag() {
        given(getOrder.execute(new GetOrderRequest(ID))).willReturn(Optional.of(ORDER));

        client
                .get()
                .uri("/orders/{id}", ID)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("ETag", "\"0\"")
                .expectBody()
                .jsonPath("$.customer")
                .isEqualTo("Ada");
    }

    @Test
    void anUnknownOrderIs404() {
        given(getOrder.execute(new GetOrderRequest(ID))).willReturn(Optional.empty());

        client
                .get()
                .uri("/orders/{id}", ID)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void cancelIs204() {
        client
                .delete()
                .uri("/orders/{id}", ID)
                .header("If-Match", "\"0\"")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .isEmpty();
        then(cancelOrder).should().execute(new DeleteOrderRequest(ID, VersionCondition.of(0L)));
    }

    @Test
    void cancelWithoutIfMatchIs428() {
        client
                .delete()
                .uri("/orders/{id}", ID)
                .exchange()
                .expectStatus()
                .isEqualTo(428)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("PRECONDITION_REQUIRED")
                .jsonPath("$.violations[0].field")
                .isEqualTo("If-Match");
        then(cancelOrder).should(never()).execute(any());
    }

    @Test
    void noStoreStarts() {
        assertThat(context.containsBean("ordersDataSource")).isFalse();
        assertThat(context.containsBean("ledgerDataSource")).isFalse();
    }
}
