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
package io.americanexpress.synapse.test.rest.slice;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.americanexpress.synapse.core.environment.SynapseEnvironment;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.test.rest.SynapseRestTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mockingDetails;

@SynapseRestTest
@Tag("smoke")
class SynapseRestTestIT {

    @Autowired
    RestTestClient client;

    @Autowired
    ApplicationContext context;

    @Autowired
    Environment environment;

    @Autowired
    SynapseEnvironment synapseEnvironment;

    @MockitoBean
    GetBookOperation getBook;

    @MockitoBean
    SearchBooksOperation searchBooks;

    @Test
    void slicesWithMockedOperationsStart() {
        assertThat(mockingDetails(getBook).isMock()).isTrue();
        assertThat(context.getBean(GetBookOperation.class)).isSameAs(getBook);
        assertThat(AopUtils.isAopProxy(context.getBean(GetBookOperation.class))).isFalse();
        assertThat(context.getBeansOfType(MockMvc.class)).hasSize(1);
    }

    @Test
    void servesBindingsThroughRestTestClient() {
        given(getBook.execute(any())).willReturn(Optional.of(new Book(1, "Dune")));
        given(searchBooks.execute(new BookSearch("Dune"))).willReturn(List.of(new Book(1, "Dune")));

        client
                .get()
                .uri("/books/1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(1)
                .jsonPath("$.title")
                .isEqualTo("Dune");
        client
                .get()
                .uri("/books?title=Dune")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].title")
                .isEqualTo("Dune");

        then(getBook).should().execute(new BookId(1));
    }

    @Test
    void errorsAreProblemJson() {
        willThrow(new SynapseException(BookErrors.BOOK_NOT_FOUND, 1)).given(getBook).execute(any());

        client
                .get()
                .uri("/books/1")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("BOOK_NOT_FOUND");
    }

    @Test
    void edgeFiltersRun() {
        given(getBook.execute(any())).willReturn(Optional.of(new Book(1, "Dune")));

        client
                .get()
                .uri("/books/1")
                .header("X-Correlation-Id", "c-1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Correlation-Id", "c-1");
    }

    @Test
    void excludesOtherComponents() {
        assertThat(context.getBeansOfType(UnrelatedService.class)).isEmpty();
        assertThat(context.getBeansOfType(GetBookOperation.class).values()).containsExactly(getBook);
        assertThat(context.containsBeanDefinition("getBookOperation")).isFalse();
        assertThat(context.containsBeanDefinition("searchBooksOperation")).isFalse();
    }

    @Test
    void servesTheApplicationsControllers() {
        client
                .get()
                .uri("/ping")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("pong");
    }

    @Test
    void activatesTestProfile() {
        willThrow(new SynapseException(BookErrors.BOOK_NOT_FOUND, 1)).given(getBook).execute(any());

        assertThat(environment.getActiveProfiles()).contains("test");
        assertThat(synapseEnvironment.isProduction()).isFalse();
        client
                .get()
                .uri("/books/1")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("Book 1 was not found");
    }
}
