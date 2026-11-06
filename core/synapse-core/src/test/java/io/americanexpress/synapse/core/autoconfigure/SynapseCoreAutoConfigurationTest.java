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
package io.americanexpress.synapse.core.autoconfigure;

import java.net.URI;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.core.masking.LogMasking;
import io.americanexpress.synapse.core.masking.Masked;
import io.americanexpress.synapse.core.observability.BusinessObservation;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseCoreAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SynapseCoreAutoConfiguration.class));

    @ParameterizedTest
    @CsvSource({"'',,false", "dev,,false", "test,,false", "local,,false", "prod,true,true", "dev,false,false"})
    void errorDetailIsHiddenUnlessIncludeDetailIsTrue(String profiles, @Nullable Boolean explicit,
            boolean included) {
        ApplicationContextRunner runner = contextRunner.withPropertyValues("spring.profiles.active=" + profiles);
        if (explicit != null) {
            runner = runner.withPropertyValues("synapse.errors.include-detail=" + explicit);
        }
        runner
                .run(context -> assertThat(context.getBean(SynapseErrorProperties.class).includeDetail())
                        .isEqualTo(included));
    }

    @Test
    void bindsDefaults() {
        contextRunner.run(context -> {
            assertThat(context.getBean(SynapseErrorProperties.class).typeBaseUri()).isNull();
            SynapsePagingProperties paging = context.getBean(SynapsePagingProperties.class);
            assertThat(paging.defaultSize()).isEqualTo(20);
            assertThat(paging.maxSize()).isEqualTo(100);
            assertThat(context.getBean(SynapseObservabilityProperties.class).business())
                    .isEqualTo(BusinessObservation.SPANS);
            assertThat(context.getBean(SynapseObservabilityProperties.class).requireTracing()).isTrue();
            assertThat(context.getBean(SynapseOperationsProperties.class).operations()).isEmpty();
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        });
    }

    @Test
    void pagingPropertiesRejectDefaultAboveMax() {
        contextRunner.withPropertyValues("synapse.paging.default-size=200").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("synapse.paging.default-size")
                    .hasMessageContaining("synapse.paging.max-size");
        });
    }

    @Test
    void pagingPropertiesRejectDefaultBelowOne() {
        contextRunner
                .withPropertyValues("synapse.paging.default-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void pagingPropertiesAcceptDefaultEqualToMax() {
        contextRunner
                .withPropertyValues("synapse.paging.default-size=50", "synapse.paging.max-size=50")
                .run(context -> {
                    SynapsePagingProperties paging = context.getBean(SynapsePagingProperties.class);
                    assertThat(paging.defaultSize()).isEqualTo(50);
                    assertThat(paging.maxSize()).isEqualTo(50);
                });
        contextRunner
                .withPropertyValues("synapse.paging.default-size=1", "synapse.paging.max-size=1")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void bindsTypeBaseUri() {
        contextRunner
                .withPropertyValues("synapse.errors.type-base-uri=https://errors.example.com/")
                .run(context -> assertThat(context.getBean(SynapseErrorProperties.class).typeBaseUri())
                        .isEqualTo(URI.create("https://errors.example.com/")));
    }

    @Test
    void bindsObservabilityAndOperations() {
        contextRunner
                .withPropertyValues("synapse.observability.business=metrics",
                        "synapse.observability.require-tracing=false",
                        "synapse.operations.get-book.logging.payloads=true",
                        "synapse.operations.list-books.logging.payloads=false")
                .run(context -> {
                    assertThat(context.getBean(SynapseObservabilityProperties.class).business())
                            .isEqualTo(BusinessObservation.METRICS);
                    assertThat(context.getBean(SynapseObservabilityProperties.class).requireTracing()).isFalse();
                    Map<String, SynapseOperationsProperties.Operation> operations = context
                            .getBean(SynapseOperationsProperties.class)
                            .operations();
                    assertThat(operations).containsOnlyKeys("get-book", "list-books");
                    assertThat(operations)
                            .extractingByKeys("get-book", "list-books")
                            .extracting(operation -> operation.logging().payloads())
                            .containsExactly(true, false);
                });
    }

    @Test
    void backsOffToUserClock() {
        Clock clock = Clock.system(ZoneId.of("Europe/Paris"));
        contextRunner
                .withBean(Clock.class, () -> clock)
                .run(context -> assertThat(context.getBean(Clock.class)).isSameAs(clock));
    }

    record Person(String firstName, @Masked String ssn) {
    }

    @Test
    void logMaskingIsPresentWithoutAJsonMapper() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LogMasking.class).doesNotHaveBean(JsonMapper.class);
            assertThat(context.getBean(LogMasking.class).toJson(new Person("Ann", "123")))
                    .isEqualTo("{\"firstName\":\"Ann\",\"ssn\":\"***\"}");
        });
    }

    @Test
    void logMaskingBacksOffToUserBean() {
        LogMasking mine = LogMasking.standard();
        contextRunner
                .withBean(LogMasking.class, () -> mine)
                .run(context -> assertThat(context.getBean(LogMasking.class)).isSameAs(mine));
    }

    @Test
    void logMaskingUsesTheStandardMapperWhenTwoMappersHaveNoPrimary() {
        contextRunner
                .withBean("firstMapper", JsonMapper.class, () -> JsonMapper.builder().build())
                .withBean("secondMapper", JsonMapper.class, () -> JsonMapper.builder().build())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LogMasking.class).toJson(new Person("Ann", "123")))
                            .isEqualTo("{\"firstName\":\"Ann\",\"ssn\":\"***\"}");
                });
    }

    @Test
    void logMaskingUsesBootsMapperCustomizationsWithoutChangingIt() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withPropertyValues("spring.jackson.property-naming-strategy=SNAKE_CASE")
                .run(context -> {
                    assertThat(context.getBean(LogMasking.class).toJson(new Person("Ann", "123")))
                            .isEqualTo("{\"first_name\":\"Ann\",\"ssn\":\"***\"}");
                    assertThat(context.getBean(JsonMapper.class).writeValueAsString(new Person("Ann", "123")))
                            .isEqualTo("{\"first_name\":\"Ann\",\"ssn\":\"123\"}");
                });
    }
}
