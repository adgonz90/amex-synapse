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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitch;
import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitchCheck;
import io.americanexpress.synapse.core.internal.paging.AesGcmCursorCodec;
import io.americanexpress.synapse.core.internal.paging.InputDigest;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.masking.LogMasking;
import io.americanexpress.synapse.core.masking.Masked;
import io.americanexpress.synapse.core.observability.BusinessObservation;
import io.americanexpress.synapse.core.paging.CursorCodec;
import io.americanexpress.synapse.core.paging.CursorScope;
import io.americanexpress.synapse.core.paging.InvalidCursorException;
import io.americanexpress.synapse.core.paging.PageQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SynapseCoreAutoConfigurationTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static final String GENERATED_KEY_TEXT = "synapse.paging.cursor.key is not set, so paging cursors are"
            + " encrypted with a key generated at startup: a cursor is rejected after a restart or on another"
            + " instance. Set the same base64 256-bit key on every instance (for example: openssl rand -base64 32).";

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

    @Test
    void cursorBeansActivateWithoutAnyPlugin() {
        contextRunner.run(context -> {
            assertThat(context)
                    .hasSingleBean(CursorCodec.class)
                    .hasBean("synapseCursorCodec")
                    .hasSingleBean(InputDigest.class)
                    .hasBean("synapseInputDigest")
                    .hasSingleBean(PageCursors.class)
                    .hasBean("synapsePageCursors");
            CursorCodec codec = context.getBean(CursorCodec.class);
            assertThat(codec).isInstanceOf(AesGcmCursorCodec.class);
            assertThat(((AesGcmCursorCodec) codec).keyGenerated()).isTrue();
            CursorScope scope = new CursorScope("search-books", List.of(), "digest");
            assertThat(codec.decode(codec.encode("raw-7", scope), scope)).isEqualTo("raw-7");
            PageCursors cursors = context.getBean(PageCursors.class);
            String token = cursors.encode("search-books", List.of(), null, Map.of(), "raw-8");
            assertThat(cursors.decode("search-books", List.of(), null, Map.of(), token, "page_token"))
                    .isEqualTo("raw-8");
        });
    }

    @Test
    void cursorBeansBackOffToUserBeans() {
        CursorCodec user = new CursorCodec() {
            @Override
            public String encode(String cursor, CursorScope scope) {
                return "token-for-" + scope.operation();
            }

            @Override
            public String decode(String token, CursorScope scope) {
                throw new InvalidCursorException("test");
            }
        };
        contextRunner.withBean(CursorCodec.class, () -> user).run(context -> {
            assertThat(context).hasSingleBean(CursorCodec.class).doesNotHaveBean("synapseCursorCodec");
            assertThat(context.getBean(CursorCodec.class)).isSameAs(user);
            // The page cursors use the user's codec.
            PageCursors cursors = context.getBean(PageCursors.class);
            assertThat(cursors.wrap("search-books", PageQuery.first(2), Map.of(), "raw"))
                    .isEqualTo(
                            "token-for-search-books");
            assertThatExceptionOfType(SynapseException.class)
                    .isThrownBy(() -> cursors
                            .unwrap("search-books", PageQuery.ofCursor("t", 2, List.of()), Map.of(), "after", 1000))
                    .satisfies(e -> assertThat(e.violations())
                            .containsExactly(new Violation("after", "is not valid for this request")));
        });
        InputDigest digest = new InputDigest();
        PageCursors cursors = new PageCursors(user, digest);
        contextRunner
                .withBean(CursorCodec.class, () -> user)
                .withBean(InputDigest.class, () -> digest)
                .withBean(PageCursors.class, () -> cursors)
                .run(context -> {
                    assertThat(context)
                            .doesNotHaveBean("synapseCursorCodec")
                            .doesNotHaveBean("synapseInputDigest")
                            .doesNotHaveBean("synapsePageCursors");
                    assertThat(context.getBean(InputDigest.class)).isSameAs(digest);
                    assertThat(context.getBean(PageCursors.class)).isSameAs(cursors);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "dev", "test", "local", "prod"})
    void cursorCodecBindsCoreKeys(String profile) {
        ApplicationContextRunner runner = contextRunner.withPropertyValues("spring.profiles.active=" + profile);
        // Set: the codec uses the key, and nothing is reported.
        List<ILoggingEvent> configured = coreLines(() -> runner
                .withPropertyValues("synapse.paging.cursor.key=" + KEY)
                .run(context -> {
                    assertThat(((AesGcmCursorCodec) context.getBean(CursorCodec.class)).keyGenerated()).isFalse();
                    context.getBean(PageCursors.class).reportGeneratedKey();
                }));
        assertThat(configured).noneMatch(line -> line.getFormattedMessage().contains("cursor.key"));
        // Unset: one WARN once a plugin reports it, however many plugins do, in every environment.
        // Core alone says nothing at startup: a plugin reports the key once it has a paged route or method.
        List<ILoggingEvent> unreported = coreLines(() -> runner.run(context -> assertThat(context).hasNotFailed()));
        assertThat(unreported).noneMatch(line -> line.getFormattedMessage().contains("cursor.key"));
        List<ILoggingEvent> generated = coreLines(() -> runner.run(context -> {
            assertThat(((AesGcmCursorCodec) context.getBean(CursorCodec.class)).keyGenerated()).isTrue();
            context.getBean(PageCursors.class).reportGeneratedKey();
            context.getBean(PageCursors.class).reportGeneratedKey();
        }));
        assertThat(generated)
                .filteredOn(line -> line.getFormattedMessage().contains("cursor.key"))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.getLevel()).isEqualTo(Level.WARN);
                    assertThat(line.getLoggerName()).isEqualTo("io.americanexpress.synapse.core");
                    assertThat(line.getFormattedMessage()).isEqualTo(GENERATED_KEY_TEXT);
                });
    }

    @Test
    void cursorCodecBindsPreviousKeysAndMaxAge() {
        String oldKey = KEY;
        byte[] newBytes = new byte[32];
        Arrays.fill(newBytes, (byte) 5);
        String newKey = Base64.getUrlEncoder().withoutPadding().encodeToString(newBytes);
        Instant issued = Instant.parse("2026-11-04T00:00:00Z");
        CursorScope scope = new CursorScope("search-books", List.of(), "digest");
        AtomicReference<String> token = new AtomicReference<>();
        contextRunner
                .withBean(Clock.class, () -> Clock.fixed(issued, ZoneOffset.UTC))
                .withPropertyValues("synapse.paging.cursor.key=" + oldKey)
                .run(context -> token.set(context.getBean(CursorCodec.class).encode("raw-7", scope)));
        for (Duration age : List.of(Duration.ofMinutes(59), Duration.ofHours(2))) {
            contextRunner
                    .withBean(Clock.class, () -> Clock.fixed(issued.plus(age), ZoneOffset.UTC))
                    .withPropertyValues("synapse.paging.cursor.key=" + newKey,
                            "synapse.paging.cursor.previous-keys[0]=" + oldKey, "synapse.paging.cursor.max-age=1h")
                    .run(context -> {
                        CursorCodec codec = context.getBean(CursorCodec.class);
                        String issuedToken = Objects.requireNonNull(token.get());
                        if (age.compareTo(Duration.ofHours(1)) < 0) {
                            // The previous key still reads the token, until it is older than the maximum age.
                            assertThat(codec.decode(issuedToken, scope)).isEqualTo("raw-7");
                        } else {
                            assertThatExceptionOfType(InvalidCursorException.class)
                                    .isThrownBy(() -> codec.decode(issuedToken, scope));
                        }
                    });
        }
    }

    @Test
    void maxOffsetBindsFromCore() {
        contextRunner
                .run(context -> assertThat(context.getBean(SynapsePagingProperties.class).maxOffset())
                        .isEqualTo(1000));
        contextRunner
                .withPropertyValues("synapse.paging.max-offset=500")
                .run(context -> assertThat(context.getBean(SynapsePagingProperties.class).maxOffset())
                        .isEqualTo(500));
        contextRunner.withPropertyValues("synapse.paging.max-offset=-1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("synapse.paging.max-offset");
        });
    }

    @Test
    void invalidCursorKeyFailsStartup() {
        contextRunner.withPropertyValues("synapse.paging.cursor.key=short").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                        // The failure analyzer prints the description and the action.
                        assertThat(failure.description())
                                .contains("synapse.paging.cursor.key")
                                .doesNotContain("short");
                        assertThat(failure.action()).contains("synapse.paging.cursor.key").doesNotContain("short");
                    });
        });
    }

    /** Runs the action and returns what it logged on the core logger. */
    private static List<ILoggingEvent> coreLines(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.core");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return List.copyOf(appender.list);
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

    @Test
    void buildTimeSwitchCheckIsDefinedAndBacksOffToAUserBean() {
        String name = "synapseBuildTimeSwitchCheck";
        contextRunner
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .getBean(name)
                        .isInstanceOf(BuildTimeSwitchCheck.class));
        SmartInitializingSingleton user = () -> {
        };
        contextRunner.withBean(name, SmartInitializingSingleton.class, () -> user).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(name)).isSameAs(user);
        });
    }

    @Test
    void buildTimeSwitchesAreNotAskedOutsideAotMode() {
        // Outside AOT mode (these tests run on the JVM) a switch that differs from the build is never asked.
        contextRunner
                .withBean("switch", BuildTimeSwitch.class, () -> () -> {
                    throw new AssertionError("asked outside AOT mode");
                })
                .run(context -> assertThat(context).hasNotFailed());
    }
}
