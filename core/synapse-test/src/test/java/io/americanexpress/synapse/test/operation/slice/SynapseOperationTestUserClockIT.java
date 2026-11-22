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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.americanexpress.synapse.test.operation.SynapseOperationTest;
import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

@SynapseOperationTest
class SynapseOperationTestUserClockIT {

    static final Clock USER_CLOCK = Clock.fixed(Instant.parse("2030-05-05T10:00:00Z"), ZoneOffset.UTC);

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        Clock userClock() {
            return USER_CLOCK;
        }
    }

    @MockitoBean
    BookRepositoryPort repository;

    @Autowired
    Clock clock;

    @Autowired
    ApplicationContext context;

    @Test
    void userClockWins() {
        assertThat(clock).isSameAs(USER_CLOCK);
        assertThat(context.getBeansOfType(Clock.class)).hasSize(1);
        assertThat(context.getBeansOfType(MutableClock.class)).isEmpty();
    }
}
