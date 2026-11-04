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
package io.americanexpress.synapse.test.operation.slice;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.americanexpress.synapse.core.environment.SynapseEnvironment;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.test.operation.RequestContexts;
import io.americanexpress.synapse.test.operation.SynapseOperationTest;
import io.americanexpress.synapse.test.time.MutableClock;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@SynapseOperationTest
@Tag("smoke")
class SynapseOperationTestIT {

    @Autowired
    GetBookOperation getBook;

    @Autowired
    TitleRule titleRule;

    @MockitoBean
    BookRepositoryPort repository;

    @Autowired
    MutableClock clock;

    @Autowired
    ApplicationContext context;

    @Autowired
    Environment environment;

    @Autowired
    SynapseEnvironment synapseEnvironment;

    @Test
    void bootsOperationsAndBusinessBeansWithThePipeline() {
        given(repository.findTitle("1")).willReturn(Optional.of(" dune "));
        given(repository.findTitle("2")).willReturn(Optional.empty());

        assertThat(AopUtils.isCglibProxy(getBook)).isTrue();
        assertThat(AopUtils.isCglibProxy(titleRule)).isTrue();
        assertThat(getBook.execute("1")).isEqualTo("DUNE");
        assertThatSynapseException(() -> getBook.execute("2")).hasCode(SynapseErrors.NOT_FOUND);
    }

    @Test
    void excludesOtherComponents() {
        assertThat(context.getBeansOfType(UnrelatedService.class)).isEmpty();
        assertThat(context.getBeansOfType(UnrelatedController.class)).isEmpty();
    }

    @Test
    void activatesTestProfile() {
        assertThat(environment.getActiveProfiles()).contains("test");
        assertThat(synapseEnvironment.isProduction()).isFalse();
    }

    @Test
    void providesMutableClock() {
        given(repository.findTitle("1")).willReturn(Optional.of("dune"));
        Instant deadline = Instant.parse("2026-01-01T00:00:01Z");

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(RequestContexts.with().deadline(deadline).call(() -> getBook.execute("1"))).isEqualTo("DUNE");

        clock.advance(Duration.ofSeconds(2));

        assertThatSynapseException(() -> RequestContexts.with().deadline(deadline).run(() -> getBook.execute("1")))
                .hasCode(SynapseErrors.DEADLINE_EXCEEDED);
    }
}
