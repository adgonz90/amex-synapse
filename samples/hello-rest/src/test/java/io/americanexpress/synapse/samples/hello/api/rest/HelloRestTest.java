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
package io.americanexpress.synapse.samples.hello.api.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.americanexpress.synapse.samples.hello.model.GetGreetingRequest;
import io.americanexpress.synapse.samples.hello.model.Greeting;
import io.americanexpress.synapse.samples.hello.operation.GetGreetingOperation;
import io.americanexpress.synapse.test.rest.SynapseRestTest;

import static org.mockito.BDDMockito.given;

/**
 * The REST slice: the route and the edge on MockMvc, with the operation mocked. It checks how the request binds to
 * the operation's input and how the output becomes the response; the operation itself is tested in
 * {@code GetGreetingOperationTest}.
 */
@SynapseRestTest
class HelloRestTest {

    @Autowired
    RestTestClient client;

    @MockitoBean
    GetGreetingOperation getGreeting;

    @Test
    void routeBindsThePathVariable() {
        given(getGreeting.execute(new GetGreetingRequest("Ada"))).willReturn(new Greeting("Hello, Ada!"));

        client
                .get()
                .uri("/greetings/{name}", "Ada")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("Hello, Ada!");
    }
}
