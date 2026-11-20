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

import java.util.List;
import java.util.Set;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;

import ch.qos.logback.classic.Level;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.GlobalObservationConvention;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.scope.ScopedObject;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.CustomScopeConfigurer;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.binding.BindingDeclaration;
import io.americanexpress.synapse.core.internal.binding.BindingDeclarations;
import io.americanexpress.synapse.core.internal.binding.BindingParityCheck;
import io.americanexpress.synapse.core.internal.pipeline.BusinessAdvisor;
import io.americanexpress.synapse.core.internal.pipeline.ConstraintViolationTranslator;
import io.americanexpress.synapse.core.internal.pipeline.InflightTracker;
import io.americanexpress.synapse.core.internal.pipeline.OperationAdvisor;
import io.americanexpress.synapse.core.internal.pipeline.OperationDrainLifecycle;
import io.americanexpress.synapse.core.internal.pipeline.OperationPipeline;
import io.americanexpress.synapse.core.internal.pipeline.OperationSettingsPrefix;
import io.americanexpress.synapse.core.lifecycle.SynapseLifecyclePhases;
import io.americanexpress.synapse.core.observability.DefaultOperationObservationConvention;
import io.americanexpress.synapse.core.observability.OperationObservationContext;
import io.americanexpress.synapse.core.observability.OperationObservationConvention;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;

import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.Book;
import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.FailBookOperation;
import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.GetBookOperation;
import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.GetBookRequest;
import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.PriceBookBusiness;
import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.PriceRequest;
import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.SaveBookOperation;
import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.TestErrors;
import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.runner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class SynapseOperationAutoConfigurationTest {

    private @Nullable Level previousPayloadLevel;

    @BeforeEach
    void payloadLevel() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(OperationFixtures.PAYLOAD_LOGGER);
        previousPayloadLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void restoreLevel() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OperationFixtures.PAYLOAD_LOGGER))
                .setLevel(previousPayloadLevel);
    }

    @Test
    void operationBeansAreClassProxiesWithThePipeline() {
        runner().withUserConfiguration(Operations.class).run(context -> {
            assertThat(context).hasSingleBean(OperationPipeline.class);
            assertThat(context).hasSingleBean(InflightTracker.class);
            assertThat(context).hasSingleBean(ErrorMessages.class);
            assertThat(context).hasSingleBean(ConstraintViolationTranslator.class);
            assertThat(context).hasSingleBean(DefaultOperationObservationConvention.class);

            GetBookOperation operation = context.getBean(GetBookOperation.class);
            assertThat(AopUtils.isCglibProxy(operation)).isTrue();
            assertThat(operation.execute(new GetBookRequest("1"))).isEqualTo(new OperationFixtures.Book("1"));
            assertThatThrownBy(() -> operation.execute(new GetBookRequest(" ")))
                    .isInstanceOfSatisfying(SynapseException.class, failure -> {
                        assertThat(failure.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                        assertThat(failure.violations()).extracting(Violation::field).containsExactly("id");
                    });
        });
    }

    @Test
    void advisorsAreOrderedInfrastructureBeans() {
        runner().withUserConfiguration(Operations.class).run(context -> {
            OperationAdvisor operationAdvisor = context.getBean(OperationAdvisor.class);
            BusinessAdvisor businessAdvisor = context.getBean(BusinessAdvisor.class);
            assertThat(operationAdvisor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1000);
            assertThat(businessAdvisor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1001);
            assertThat(context.getBeanFactory().getBeanDefinition("synapseOperationAdvisor").getRole())
                    .isEqualTo(BeanDefinition.ROLE_INFRASTRUCTURE);
            assertThat(context.getBeanFactory().getBeanDefinition("synapseBusinessAdvisor").getRole())
                    .isEqualTo(BeanDefinition.ROLE_INFRASTRUCTURE);
            Advisor[] advisors = ((Advised) context.getBean(GetBookOperation.class)).getAdvisors();
            assertThat(advisors).containsExactly(operationAdvisor);
            assertThat(((Advised) context.getBean(PriceBookBusiness.class)).getAdvisors())
                    .containsExactly(businessAdvisor);
        });
    }

    @Test
    void operationsAreClassProxiesEvenWhenTheProxyCreatorDefaultsToInterfaces() {
        runner()
                .withPropertyValues("spring.aop.auto=false")
                .withUserConfiguration(InterfaceProxying.class, Operations.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(AopUtils.isCglibProxy(context.getBean(GetBookOperation.class))).isTrue();
                    assertThat(AopUtils.isCglibProxy(context.getBean(PriceBookBusiness.class))).isTrue();
                });
    }

    @Test
    void businessBeansAreProxied() {
        runner().withUserConfiguration(Operations.class).run(context -> {
            PriceBookBusiness business = context.getBean(PriceBookBusiness.class);
            assertThat(AopUtils.isCglibProxy(business)).isTrue();
            assertThat(business.execute(new PriceRequest(2))).isEqualTo(20);
            // A business input is built by the service: its violations are INTERNAL, kept in the cause.
            assertThatThrownBy(() -> business.execute(new PriceRequest(0)))
                    .isInstanceOfSatisfying(SynapseException.class, failure -> {
                        assertThat(failure.details()).isEqualTo(SynapseErrors.INTERNAL);
                        assertThat(failure.violations()).isEmpty();
                        assertThat(failure.getCause())
                                .isInstanceOfSatisfying(SynapseException.class, cause -> assertThat(cause.violations())
                                        .extracting(Violation::field)
                                        .containsExactly("quantity"));
                    });
        });
    }

    @Test
    void operationCallsAreObserved() {
        runner().withUserConfiguration(Operations.class, TestObservations.class).run(context -> {
            context.getBean(GetBookOperation.class).execute(new GetBookRequest("1"));
            context.getBean(PriceBookBusiness.class).execute(new PriceRequest(1));
            TestObservationRegistry registry = context.getBean(TestObservationRegistry.class);
            TestObservationRegistryAssert
                    .assertThat(registry)
                    .hasObservationWithNameEqualTo("synapse.operation")
                    .that()
                    .hasLowCardinalityKeyValue("operation", "get-book");
            TestObservationRegistryAssert
                    .assertThat(registry)
                    .hasObservationWithNameEqualTo("synapse.business")
                    .that()
                    .hasLowCardinalityKeyValue("business", "price-book-business");
        });
    }

    @Test
    void registersTheDrainLifecycleOverTheSharedTracker() {
        runner().withUserConfiguration(Operations.class).run(context -> {
            OperationDrainLifecycle lifecycle = context.getBean(OperationDrainLifecycle.class);
            assertThat(lifecycle.getPhase()).isEqualTo(SynapseLifecyclePhases.DRAIN);

            lifecycle.stop();

            assertThatThrownBy(() -> context.getBean(GetBookOperation.class).execute(new GetBookRequest("1")))
                    .isInstanceOfSatisfying(SynapseException.class,
                            failure -> assertThat(failure.details()).isEqualTo(SynapseErrors.UNAVAILABLE));
        });
    }

    @Test
    void admitsCallsWithoutAContextAgainAfterPauseAndRestart() {
        // Spring's test context cache pauses a context when a test class switches to another one, and CRaC stops and
        // restarts it: the drain must not reject calls for the rest of the context's life.
        runner().withUserConfiguration(Operations.class).run(context -> {
            GetBookOperation operation = context.getBean(GetBookOperation.class);

            context.pause();
            context.restart();

            assertThat(context.getBean(OperationDrainLifecycle.class).isRunning()).isTrue();
            assertThat(operation.execute(new GetBookRequest("1"))).isEqualTo(new Book("1"));
        });
    }

    @Test
    void admitsCallsWithoutAContextAgainAfterStopAndStart() {
        runner().withUserConfiguration(Operations.class).run(context -> {
            GetBookOperation operation = context.getBean(GetBookOperation.class);

            context.stop();
            assertThatThrownBy(() -> operation.execute(new GetBookRequest("1")))
                    .isInstanceOfSatisfying(SynapseException.class,
                            failure -> assertThat(failure.details()).isEqualTo(SynapseErrors.UNAVAILABLE));
            context.start();

            assertThat(operation.execute(new GetBookRequest("1"))).isEqualTo(new Book("1"));
        });
    }

    @Test
    void drainLifecycleBacksOffToAUserBean() {
        OperationDrainLifecycle user = new OperationDrainLifecycle(new InflightTracker());
        runner()
                .withUserConfiguration(Operations.class)
                .withBean("userDrain", OperationDrainLifecycle.class, () -> user)
                .run(context -> assertThat(context.getBeansOfType(OperationDrainLifecycle.class))
                        .containsOnlyKeys("userDrain"));
    }

    @Test
    void backsOffToUserPipelineBeans() {
        runner()
                .withUserConfiguration(Operations.class, UserPipelineBeans.class, TestObservations.class)
                .run(context -> {
                    assertThat(context.getBean(ErrorMessages.class)).isSameAs(UserPipelineBeans.MESSAGES);
                    assertThat(context.getBean(InflightTracker.class)).isSameAs(UserPipelineBeans.INFLIGHT);
                    assertThat(context.getBean(ConstraintViolationTranslator.class))
                            .isSameAs(UserPipelineBeans.TRANSLATOR);
                    assertThat(context).doesNotHaveBean("defaultOperationObservationConvention");
                    assertThat(context.getBean(OperationObservationConvention.class))
                            .isSameAs(UserPipelineBeans.CONVENTION);
                    context.getBean(SaveBookOperation.class).execute(new GetBookRequest("1"));
                    TestObservationRegistryAssert
                            .assertThat(context.getBean(TestObservationRegistry.class))
                            .hasObservationWithNameEqualTo("user.operation");

                    // The pipeline counts calls with the user's tracker: once it drains, calls without a context are refused.
                    UserPipelineBeans.INFLIGHT.startDraining();
                    assertThatThrownBy(() -> context.getBean(GetBookOperation.class).execute(new GetBookRequest("1")))
                            .isInstanceOfSatisfying(SynapseException.class,
                                    failure -> assertThat(failure.details()).isEqualTo(SynapseErrors.UNAVAILABLE));
                });
    }

    @Test
    void backsOffToUserPipeline() {
        runner().withUserConfiguration(FailingOperation.class, UserPipeline.class).run(context -> {
            assertThat(context.getBean(OperationPipeline.class)).isSameAs(UserPipeline.PIPELINE);
            assertThatThrownBy(() -> context.getBean(FailBookOperation.class).execute("x"))
                    .isInstanceOfSatisfying(SynapseException.class,
                            failure -> assertThat(failure.details()).isEqualTo(TestErrors.SECOND));
        });
    }

    @Test
    void translatorBeansAreUsedInOrder() {
        runner().withUserConfiguration(FailingOperation.class, OrderedTranslators.class).run(context -> {
            assertThatThrownBy(() -> context.getBean(FailBookOperation.class).execute("x"))
                    .isInstanceOfSatisfying(SynapseException.class,
                            failure -> assertThat(failure.details()).isEqualTo(TestErrors.FIRST));
        });
    }

    @Test
    void constraintViolationExceptionOfAnotherObjectIsInternal() {
        runner().withUserConfiguration(ConstraintViolatingOperation.class).run(context -> {
            assertThatThrownBy(() -> context.getBean(FailBookOperation.class).execute("x"))
                    .isInstanceOfSatisfying(SynapseException.class, failure -> {
                        assertThat(failure.details()).isEqualTo(SynapseErrors.INTERNAL);
                        assertThat(failure.violations()).isEmpty();
                        assertThat(failure.getCause())
                                .isNotInstanceOf(ConstraintViolationException.class)
                                .hasMessageContaining("id: {jakarta.validation.constraints.NotBlank.message}");
                    });
        });
    }

    @Test
    void constraintViolationExceptionOfTheInputIsTranslated() {
        runner().withUserConfiguration(HandValidatingOperation.class).run(context -> {
            assertThatThrownBy(() -> context.getBean(CheckCodeOperation.class).execute(new CodeRequest("abcd")))
                    .isInstanceOfSatisfying(SynapseException.class, failure -> {
                        assertThat(failure.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                        assertThat(failure.violations()).extracting(Violation::field).containsExactly("code");
                        assertThat(failure.violations()).extracting(Violation::message).doesNotContainNull();
                    });
        });
    }

    @Test
    void bindsPayloadLoggingPerOperation(CapturedOutput output) {
        runner()
                .withUserConfiguration(Operations.class)
                .withPropertyValues("synapse.operations.get-book.logging.payloads=true")
                .run(context -> {
                    context.getBean(GetBookOperation.class).execute(new GetBookRequest("b-1"));
                    context.getBean(SaveBookOperation.class).execute(new GetBookRequest("b-2"));
                });
        assertThat(output.getOut()).contains("Operation get-book input").contains("Operation get-book output");
        assertThat(output.getOut()).doesNotContain("Operation save-book input");
    }

    @Test
    void bindsPagingAndBusinessObservation() {
        runner()
                .withUserConfiguration(Operations.class, Metrics.class)
                .withPropertyValues("synapse.paging.default-size=5", "synapse.paging.max-size=5",
                        "synapse.observability.business=metrics")
                .run(context -> {
                    ListBooksOperation list = context.getBean(ListBooksOperation.class);
                    assertThat(list.execute(PageQuery.first(5))).isEqualTo("5");
                    assertThatThrownBy(() -> list.execute(PageQuery.first(6)))
                            .isInstanceOfSatisfying(SynapseException.class,
                                    failure -> assertThat(failure.details())
                                            .isEqualTo(SynapseErrors.VALIDATION_FAILED));

                    context.getBean(PriceBookBusiness.class).execute(new PriceRequest(1));
                    assertThat(context
                            .getBean(MeterRegistry.class)
                            .find("synapse.business")
                            .tag("business", "price-book-business")
                            .timer()).isNotNull();
                });
    }

    @Test
    void warnsOnUnknownOperationKeys(CapturedOutput output) {
        runner()
                .withUserConfiguration(Operations.class)
                .withPropertyValues("synapse.operations.get-book.logging.payloads=true",
                        "synapse.operations.get-bok.logging.payloads=true",
                        "synapse.operations.[get-boook].logging.payloads=true",
                        "synapse.operations[get-bk].logging.payloads=true", "synapse.operationsx.a=b")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output.getOut().lines().filter(line -> line.contains("synapse.operations")))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("WARN").contains("[get-bk, get-bok, get-boook]"));
    }

    @Test
    void doesNotWarnWhenEveryOperationKeyIsKnown(CapturedOutput output) {
        runner()
                .withUserConfiguration(Operations.class)
                .withPropertyValues("synapse.operations.get-book.logging.payloads=true",
                        "synapse.operations.[save-book].logging.payloads=false",
                        "synapse.operations[list-books].logging.payloads=false")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output.getOut()).doesNotContain("synapse.operations");
    }

    @Test
    void unknownRestOperationSettingsWarn(CapturedOutput output) {
        runner()
                .withUserConfiguration(Operations.class)
                .withBean("synapseRestOperationSettingsPrefix", OperationSettingsPrefix.class,
                        () -> new OperationSettingsPrefix("synapse.api.rest.operations"))
                .withPropertyValues("synapse.api.rest.operations.no-such-op.errors.status.[X]=410",
                        "synapse.api.rest.operations.get-book.errors.status.[X]=410",
                        // No plugin contributes the gRPC prefix here, so its keys are not read.
                        "synapse.api.grpc.operations.other-op.errors.status.[X]=NOT_FOUND")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output.getOut().lines().filter(line -> line.contains("so their settings have no effect")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains("WARN")
                        .contains("under synapse.api.rest.operations")
                        .contains(": [no-such-op]."));
    }

    @Test
    void unknownGrpcOperationSettingsWarn(CapturedOutput output) {
        runner()
                .withUserConfiguration(Operations.class)
                .withBean("synapseRestOperationSettingsPrefix", OperationSettingsPrefix.class,
                        () -> new OperationSettingsPrefix("synapse.api.rest.operations"))
                .withBean("synapseGrpcOperationSettingsPrefix", OperationSettingsPrefix.class,
                        () -> new OperationSettingsPrefix("synapse.api.grpc.operations"))
                .withPropertyValues("synapse.api.grpc.operations.no-such-op.errors.status.[X]=NOT_FOUND",
                        "synapse.api.grpc.operations.[get-bok].errors.status.[X]=NOT_FOUND",
                        "synapse.api.grpc.operations.save-book.errors.status.[X]=NOT_FOUND",
                        "synapse.operations.get-bk.logging.payloads=true")
                .run(context -> assertThat(context).hasNotFailed());
        List<String> warnings = output
                .getOut()
                .lines()
                .filter(line -> line.contains("so their settings have no effect"))
                .toList();
        // One WARN per prefix that has unknown names, in the core message's form; the REST prefix has none.
        assertThat(warnings).hasSize(2);
        assertThat(warnings)
                .filteredOn(line -> line.contains("under synapse.api.grpc.operations"))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("WARN").contains(": [get-bok, no-such-op]."));
        assertThat(warnings)
                .filteredOn(line -> line.contains("under synapse.operations,"))
                .singleElement()
                .satisfies(line -> assertThat(line).contains(": [get-bk]."));
    }

    @Test
    void bindingParityBeansArePresent() {
        runner().withUserConfiguration(Operations.class).run(context -> {
            assertThat(context).hasSingleBean(BindingDeclarations.class);
            assertThat(context).hasBean("synapseBindingDeclarations");
            assertThat(context).hasSingleBean(BindingParityCheck.class);
            assertThat(context).hasBean("synapseBindingParityCheck");
        });
    }

    @Test
    void bindingParityBeansBackOffByName() {
        BindingDeclarations user = new BindingDeclarations();
        runner()
                .withUserConfiguration(Operations.class)
                .withBean("synapseBindingDeclarations", BindingDeclarations.class, () -> user)
                .withBean("synapseBindingParityCheck", Object.class, Object::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(BindingDeclarations.class)).isSameAs(user);
                    assertThat(context).doesNotHaveBean(BindingParityCheck.class);
                });
    }

    @Test
    void bindingParityCheckFailsStartupOnAMismatch() {
        runner()
                .withUserConfiguration(Operations.class, MismatchedBindings.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .isNotNull()
                        .isInstanceOf(SynapseConfigurationException.class)
                        .hasMessageContaining("Operation 'get-book' is bound differently over REST"));
    }

    @Test
    void scopedOperationRunsThePipeline() {
        runner().withUserConfiguration(ScopedOperation.class).run(context -> {
            assertThat(context).hasNotFailed();
            GetBookOperation operation = context.getBean(GetBookOperation.class);
            assertThat(operation).isInstanceOf(ScopedObject.class);
            assertThat(operation.execute(new GetBookRequest("1"))).isEqualTo(new OperationFixtures.Book("1"));
            assertThatThrownBy(() -> operation.execute(new GetBookRequest(" ")))
                    .isInstanceOfSatisfying(SynapseException.class,
                            failure -> assertThat(failure.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED));
        });
    }

    @Test
    void lazyOperationIsNotCreatedAtStartup() {
        runner()
                .withBean("lazyGetBook", GetBookOperation.class, GetBookOperation::new,
                        definition -> definition.setLazyInit(true))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanFactory().containsSingleton("lazyGetBook")).isFalse();
                    GetBookOperation operation = context.getBean(GetBookOperation.class);
                    assertThat(AopUtils.isCglibProxy(operation)).isTrue();
                    assertThatThrownBy(() -> operation.execute(new GetBookRequest(" ")))
                            .isInstanceOfSatisfying(SynapseException.class,
                                    failure -> assertThat(failure.details())
                                            .isEqualTo(SynapseErrors.VALIDATION_FAILED));
                });
    }

    @Test
    void lazyInterfaceTypedOperationRunsThePipeline() {
        runner().withUserConfiguration(LazyInterfaceTypedOperation.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanFactory().containsSingleton("lazyLookup")).isFalse();
            @SuppressWarnings("unchecked")
            QueryOperation<GetBookRequest, OperationFixtures.Book> operation = context
                    .getBean("lazyLookup", QueryOperation.class);
            assertThat(AopUtils.isCglibProxy(operation)).isTrue();
            assertThat(operation.execute(new GetBookRequest("1"))).isEqualTo(new OperationFixtures.Book("1"));
            assertThatThrownBy(() -> operation.execute(new GetBookRequest(" ")))
                    .isInstanceOfSatisfying(SynapseException.class,
                            failure -> assertThat(failure.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED));
        });
    }

    /** Registers two bindings of one operation that differ, as the plugins' compilers do while their beans are created. */
    @Configuration(proxyBeanMethods = false)
    static class MismatchedBindings {

        @Bean
        String compiledBindings(BindingDeclarations declarations) {
            declarations
                    .add(new BindingDeclaration("REST", "get-book", "GET /books/{id}", "BOOK_NOT_FOUND", null,
                            null));
            declarations
                    .add(new BindingDeclaration("gRPC", "get-book", "bookstore.v1.Bookstore/GetBook", "NOT_FOUND",
                            null, null));
            return "compiled";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyInterfaceTypedOperation {

        @Bean
        @Lazy
        QueryOperation<GetBookRequest, OperationFixtures.Book> lazyLookup() {
            return new GetBookOperation();
        }
    }

    @Test
    void lazyInitializationOfTheWholeApplicationKeepsWorking() {
        runner()
                .withInitializer(context -> context
                        .addBeanFactoryPostProcessor(
                                new LazyInitializationBeanFactoryPostProcessor()))
                .withUserConfiguration(Operations.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanFactory().containsSingleton("getBookOperation")).isFalse();
                    assertThat(AopUtils.isCglibProxy(context.getBean(GetBookOperation.class))).isTrue();
                });
    }

    @Test
    void factoryBeanOperationRunsThePipeline() {
        runner().withBean("factoryGetBook", GetBookOperationFactory.class).run(context -> {
            assertThat(context).hasNotFailed();
            GetBookOperation operation = context.getBean(GetBookOperation.class);
            assertThat(AopUtils.isCglibProxy(operation)).isTrue();
            assertThatThrownBy(() -> operation.execute(new GetBookRequest(" ")))
                    .isInstanceOfSatisfying(SynapseException.class,
                            failure -> assertThat(failure.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED));
        });
    }

    /** A factory bean that produces the {@code get-book} operation. */
    static class GetBookOperationFactory implements FactoryBean<GetBookOperation> {

        @Override
        public GetBookOperation getObject() {
            return new GetBookOperation();
        }

        @Override
        public Class<?> getObjectType() {
            return GetBookOperation.class;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ScopedOperation {

        @Bean
        static CustomScopeConfigurer threadScope() {
            CustomScopeConfigurer configurer = new CustomScopeConfigurer();
            configurer.addScope("thread", new SimpleThreadScope());
            return configurer;
        }

        @Bean
        @Scope(scopeName = "thread", proxyMode = ScopedProxyMode.TARGET_CLASS)
        GetBookOperation getBookOperation() {
            return new GetBookOperation();
        }
    }

    @Test
    void otherBeansAreNotProxied() {
        runner().withUserConfiguration(Operations.class).run(context -> {
            assertThat(AopUtils.isAopProxy(context.getBean(PlainService.class))).isFalse();
            assertThat(AopUtils.isAopProxy(context.getBean(GetBookOperation.class))).isTrue();
        });
    }

    @Test
    void startsWithoutOperations() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OperationAdvisor.class);
            assertThat(context).hasSingleBean(BusinessAdvisor.class);
        });
    }

    @Test
    void globalConventionAppliesWithoutAUserConvention() {
        runner()
                .withUserConfiguration(Operations.class, TestObservations.class, GlobalConvention.class)
                .run(context -> {
                    context.getBean(SaveBookOperation.class).execute(new GetBookRequest("1"));
                    TestObservationRegistryAssert
                            .assertThat(context.getBean(TestObservationRegistry.class))
                            .hasObservationWithNameEqualTo("global.operation");
                });
    }

    /** A convention that renames the operation observation. */
    static class UserConvention extends DefaultOperationObservationConvention {

        @Override
        public String getName() {
            return "user.operation";
        }
    }

    /** A global convention for operation observations, registered with the observation registry. */
    static class GlobalOperationConvention implements GlobalObservationConvention<OperationObservationContext> {

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof OperationObservationContext;
        }

        @Override
        public String getName() {
            return "global.operation";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class GlobalConvention {

        @Bean
        GlobalOperationConvention globalOperationConvention() {
            return new GlobalOperationConvention();
        }
    }

    /** A query named {@code list-books} that answers the requested page size. */
    static class ListBooksOperation implements QueryOperation<PageQuery, String> {

        @Override
        public String execute(PageQuery input) {
            return Integer.toString(input.size());
        }
    }

    /** A bean that is neither an operation nor a business operation. */
    static class PlainService {
    }

    @Configuration(proxyBeanMethods = false)
    static class Operations {

        @Bean
        GetBookOperation getBookOperation() {
            return new GetBookOperation();
        }

        @Bean
        SaveBookOperation saveBookOperation() {
            return new SaveBookOperation();
        }

        @Bean
        ListBooksOperation listBooksOperation() {
            return new ListBooksOperation();
        }

        @Bean
        PriceBookBusiness priceBookBusiness() {
            return new PriceBookBusiness();
        }

        @Bean
        PlainService plainService() {
            return new PlainService();
        }
    }

    /** An auto-proxy creator that makes interface-based proxies unless a bean asks for its class to be kept. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = false)
    static class InterfaceProxying {
    }

    @Configuration(proxyBeanMethods = false)
    static class TestObservations {

        @Bean
        TestObservationRegistry observationRegistry() {
            return TestObservationRegistry.create();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Metrics {

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingOperation {

        @Bean
        FailBookOperation failBookOperation() {
            return new FailBookOperation(() -> new IllegalStateException("broken"));
        }
    }

    /** A group the pipeline never validates: the operation checks it by hand. */
    interface Strict {
    }

    record CodeRequest(@Size(max = 3, groups = Strict.class) String code) {
    }

    /** Validates its own input by hand, as an operation may for a rule the pipeline doesn't run. */
    static class CheckCodeOperation implements CommandOperation<CodeRequest, String> {

        private final Validator validator;

        CheckCodeOperation(Validator validator) {
            this.validator = validator;
        }

        @Override
        public String execute(CodeRequest input) {
            Set<ConstraintViolation<CodeRequest>> violations = validator.validate(input, Strict.class);
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException(violations);
            }
            return input.code();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HandValidatingOperation {

        @Bean
        CheckCodeOperation checkCodeOperation(Validator validator) {
            return new CheckCodeOperation(validator);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConstraintViolatingOperation {

        @Bean
        FailBookOperation failBookOperation(Validator validator) {
            return new FailBookOperation(() -> {
                Set<ConstraintViolation<GetBookRequest>> violations = validator.validate(new GetBookRequest(""));
                return new ConstraintViolationException(violations);
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderedTranslators {

        @Bean
        @Order(2)
        ExceptionTranslator secondTranslator() {
            return exception -> exception instanceof IllegalStateException
                    ? new SynapseException(TestErrors.SECOND)
                    : null;
        }

        @Bean
        @Order(1)
        ExceptionTranslator firstTranslator() {
            return exception -> exception instanceof IllegalStateException
                    ? new SynapseException(TestErrors.FIRST)
                    : null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserPipelineBeans {

        static final ErrorMessages MESSAGES = new ErrorMessages(new StaticMessageSource());

        static final InflightTracker INFLIGHT = new InflightTracker();

        static final ConstraintViolationTranslator TRANSLATOR = new ConstraintViolationTranslator();

        static final OperationObservationConvention CONVENTION = new UserConvention();

        @Bean
        ErrorMessages errorMessages() {
            return MESSAGES;
        }

        @Bean
        InflightTracker inflightTracker() {
            return INFLIGHT;
        }

        @Bean
        ConstraintViolationTranslator constraintViolationTranslator() {
            return TRANSLATOR;
        }

        @Bean
        OperationObservationConvention userConvention() {
            return CONVENTION;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserPipeline {

        static final OperationPipeline PIPELINE = OperationPipeline
                .builder()
                .translators(List.of(exception -> new SynapseException(TestErrors.SECOND)))
                .build();

        @Bean
        OperationPipeline operationPipeline() {
            return PIPELINE;
        }
    }
}
