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

import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.CustomScopeConfigurer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.core.NestedExceptionUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.OperationName;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.GetBookOperation;
import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.PriceBookBusiness;
import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.runner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review Focus 1: every way an operation or business bean could skip the pipeline fails startup with a
 * {@link SynapseConfigurationException} whose description names the bean.
 */
class OperationStartupChecksTest {

    /** The name of the registry spring-test registers when a test declares bean overrides. */
    private static final String BEAN_OVERRIDE_REGISTRY = "org.springframework.test.context.bean.override."
            + "internalBeanOverrideRegistry";

    @Test
    void finalOperationClassFails() {
        assertStartupFails(runner().withBean("bookLookup", FinalOperation.class), "bookLookup", "is a final class");
    }

    @Test
    void finalBusinessClassFails() {
        assertStartupFails(runner().withBean("bookPricer", FinalBusiness.class), "bookPricer", "is a final class");
    }

    @Test
    void recordOperationFails() {
        assertStartupFails(runner().withUserConfiguration(RecordOperationConfig.class), "bookEcho", "is a record");
    }

    @Test
    void lambdaOperationBeanFails() {
        assertStartupFails(runner().withUserConfiguration(LambdaOperationConfig.class), "inlineLookup", "is a lambda");
    }

    @Test
    void anonymousOperationBeanFails() {
        assertStartupFails(runner().withUserConfiguration(AnonymousOperationConfig.class), "inlineCommand",
                "is an anonymous class");
    }

    @Test
    void finalExecuteMethodFails() {
        assertStartupFails(runner().withBean("bookReader", FinalExecuteOperation.class), "bookReader",
                "has a final execute method");
    }

    @Test
    void inheritedFinalExecuteMethodFails() {
        assertStartupFails(runner().withBean("templateOperation", TemplateOperation.class), "templateOperation",
                "has a final execute method");
    }

    @Test
    void bothQueryAndCommandFails() {
        assertStartupFails(runner().withBean("queryAndCommand", QueryAndCommandOperation.class), "queryAndCommand",
                "implements both QueryOperation and CommandOperation");
    }

    @Test
    void bothServiceAndBusinessFails() {
        assertStartupFails(runner().withBean("serviceAndBusiness", ServiceAndBusinessOperation.class),
                "serviceAndBusiness", "implements both ServiceOperation and BusinessOperation");
    }

    @Test
    void unproxiedOperationFails() {
        assertStartupFails(runner()
                .withPropertyValues("spring.aop.auto=false")
                .withBean("getBook",
                        GetBookOperation.class),
                "getBook", "auto-proxy creator is not registered (spring.aop.auto=false");
    }

    @Test
    void lazyOperationWithInvalidNameFailsWithoutBeingCreated() {
        assertStartupFails(runner()
                .withBean("lazyInvalid", InvalidNameOperation.class, InvalidNameOperation::new,
                        definition -> definition.setLazyInit(true)),
                "lazyInvalid", "The operation name 'Get_Book'");
    }

    @Test
    void lazyLambdaOperationFailsOnItsFirstCall() {
        runner().withUserConfiguration(LazyInlineOperationsConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            @SuppressWarnings("unchecked")
            QueryOperation<String, String> operation = context.getBean("lazyInlineLookup", QueryOperation.class);
            assertThatThrownBy(() -> operation.execute("x"))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class,
                            failure -> assertThat(failure.description()).contains("is a lambda"));
        });
    }

    @Test
    void lazyAnonymousOperationFailsOnItsFirstCall() {
        runner().withUserConfiguration(LazyInlineOperationsConfig.class).run(context -> {
            @SuppressWarnings("unchecked")
            CommandOperation<String, String> operation = context.getBean("lazyInlineCommand", CommandOperation.class);
            assertThatThrownBy(() -> operation.execute("x"))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class,
                            failure -> assertThat(failure.description()).contains("is an anonymous class"));
        });
    }

    @Test
    void prototypeOperationWithInvalidNameFails() {
        assertStartupFails(runner()
                .withBean("prototypeInvalid", InvalidNameOperation.class, InvalidNameOperation::new,
                        definition -> definition.setScope(BeanDefinition.SCOPE_PROTOTYPE)),
                "prototypeInvalid", "The operation name 'Get_Book'");
    }

    @Test
    void scopedOperationWithInvalidNameFailsUnderItsOwnName() {
        assertStartupFails(runner().withUserConfiguration(ThreadScope.class, ScopedInvalidOperationConfig.class),
                "scopedInvalid", "The operation name 'Get_Book'");
    }

    @Test
    void registeredOperationInstanceFails() {
        assertStartupFails(runner()
                .withInitializer(context -> context
                        .getBeanFactory()
                        .registerSingleton("registeredGetBook", new GetBookOperation())),
                "registeredGetBook", "It was registered as an instance");
    }

    @Test
    void registeredInstanceUnderADefinitionFailsWithoutBeanOverrides() {
        assertStartupFails(runner()
                .withBean("getBook", GetBookOperation.class)
                .withInitializer(context -> context
                        .addBeanFactoryPostProcessor(
                                beanFactory -> beanFactory.registerSingleton("getBook", new GetBookOperation()))),
                "getBook", "It was registered as an instance");
    }

    @Test
    void registeredInstanceWithoutADefinitionFailsEvenWithBeanOverrides() {
        assertStartupFails(runner().withInitializer(context -> {
            context.getBeanFactory().registerSingleton(BEAN_OVERRIDE_REGISTRY, new Object());
            context.getBeanFactory().registerSingleton("registeredGetBook", new GetBookOperation());
        }), "registeredGetBook", "It was registered as an instance");
    }

    @Test
    void testDoubleOfABeanOverrideSkipsThePipeline() {
        // What spring-test's bean override post-processor does for @MockitoBean and @TestBean: with its registry
        // present, it registers the double as a singleton under the bean's definition before any bean is created.
        GetBookOperation testDouble = new GetBookOperation();
        runner()
                .withBean("getBook", GetBookOperation.class)
                .withInitializer(context -> {
                    context.getBeanFactory().registerSingleton(BEAN_OVERRIDE_REGISTRY, new Object());
                    context
                            .addBeanFactoryPostProcessor(
                                    beanFactory -> beanFactory.registerSingleton("getBook", testDouble));
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("getBook")).isSameAs(testDouble);
                });
    }

    @Test
    void unproxiedBusinessFails() {
        assertStartupFails(runner()
                .withPropertyValues("spring.aop.auto=false")
                .withBean("priceBook",
                        PriceBookBusiness.class),
                "priceBook", "auto-proxy creator is not registered (spring.aop.auto=false");
    }

    @Test
    void duplicateOperationNameFails() {
        assertStartupFails(runner()
                .withBean("firstGetBook", AnnotatedGetBookOperation.class)
                .withBean("secondGetBook", OtherAnnotatedGetBookOperation.class), "secondGetBook",
                "has the name 'get-book', which operation bean 'firstGetBook' ("
                        + AnnotatedGetBookOperation.class.getName() + ") already has. Operation names must be unique.");
    }

    @Test
    void duplicateBusinessNameFails() {
        assertStartupFails(runner()
                .withBean("firstPricing", PricingBusiness.class)
                .withBean("secondPricing", OtherPricingBusiness.class), "secondPricing",
                "has the name 'pricing-business', which business operation bean 'firstPricing'");
    }

    @Test
    void duplicateBusinessNameNamesBothBeansAndClasses() {
        runner()
                .withBean("firstPricing", PricingBusiness.class)
                .withBean("secondPricing", OtherPricingBusiness.class)
                .run(context -> {
                    Throwable root = NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
                    assertThat(root).isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                        assertThat(failure.description())
                                .isEqualTo("Business operation bean 'secondPricing' ("
                                        + OtherPricingBusiness.class.getName()
                                        + ") has the name 'pricing-business', which business operation bean "
                                        + "'firstPricing' (" + PricingBusiness.class.getName()
                                        + ") already has. Business names must be unique.");
                        assertThat(failure.action())
                                .isEqualTo("Give one of them a different name with @OperationName, or rename its "
                                        + "class.");
                    });
                });
    }

    @Test
    void invalidOperationNameFails() {
        assertStartupFails(runner().withBean("invalidName", InvalidNameOperation.class), "invalidName",
                "The operation name 'Get_Book'");
    }

    @Test
    void invalidDerivedNameFails() {
        assertStartupFails(runner().withBean("snakeCase", Snake_CaseOperation.class), "snakeCase",
                "has the invalid name 'snake_case'");
    }

    @Test
    void failureIsReportedByTheFailureAnalyzer() {
        runner().withBean("bookLookup", FinalOperation.class).run(context -> {
            Throwable failure = Objects.requireNonNull(context.getStartupFailure());
            assertThat(new SynapseConfigurationFailureAnalyzer().analyze(failure)).satisfies(analysis -> {
                assertThat(analysis.getDescription()).contains("'bookLookup'").contains("is a final class");
                assertThat(analysis.getAction()).isNotBlank();
            });
        });
    }

    private static void assertStartupFails(ApplicationContextRunner runner, String beanName, String reason) {
        runner.run((AssertableApplicationContext context) -> {
            assertThat(context).hasFailed();
            Throwable root = NestedExceptionUtils
                    .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
            assertThat(root).isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                // The reason must be found outside the bean name, so a name that contains it cannot satisfy the check.
                assertThat(failure.description()).contains("'" + beanName + "'");
                assertThat(failure.description().replace("'" + beanName + "'", "")).contains(reason);
                assertThat(failure.action()).isNotBlank();
            });
        });
    }

    static final class FinalOperation implements QueryOperation<String, String> {

        @Override
        public String execute(String input) {
            return input;
        }
    }

    static final class FinalBusiness implements BusinessOperation<String, String> {

        @Override
        public String execute(String input) {
            return input;
        }
    }

    record RecordOperation(String prefix) implements QueryOperation<String, String> {

        @Override
        public String execute(String input) {
            return prefix + input;
        }
    }

    static class FinalExecuteOperation implements QueryOperation<String, String> {

        @Override
        public final String execute(String input) {
            return input;
        }
    }

    abstract static class TemplateBase implements CommandOperation<String, String> {

        @Override
        public final String execute(String input) {
            return run(input);
        }

        abstract String run(String input);
    }

    static class TemplateOperation extends TemplateBase {

        @Override
        String run(String input) {
            return input;
        }
    }

    static class QueryAndCommandOperation implements QueryOperation<String, String>, CommandOperation<String, String> {

        @Override
        public String execute(String input) {
            return input;
        }
    }

    static class ServiceAndBusinessOperation
            implements QueryOperation<String, String>, BusinessOperation<String, String> {

        @Override
        public String execute(String input) {
            return input;
        }
    }

    @OperationName("get-book")
    static class AnnotatedGetBookOperation implements QueryOperation<String, String> {

        @Override
        public String execute(String input) {
            return input;
        }
    }

    @OperationName("get-book")
    static class OtherAnnotatedGetBookOperation implements QueryOperation<String, String> {

        @Override
        public String execute(String input) {
            return input;
        }
    }

    static class PricingBusiness implements BusinessOperation<String, String> {

        @Override
        public String execute(String input) {
            return input;
        }
    }

    @OperationName("pricing-business")
    static class OtherPricingBusiness implements BusinessOperation<String, String> {

        @Override
        public String execute(String input) {
            return input;
        }
    }

    @OperationName("Get_Book")
    static class InvalidNameOperation implements QueryOperation<String, String> {

        @Override
        public String execute(String input) {
            return input;
        }
    }

    static class Snake_CaseOperation implements QueryOperation<String, String> {

        @Override
        public String execute(String input) {
            return input;
        }
    }

    /** Registers Spring's {@code thread} scope. */
    @Configuration(proxyBeanMethods = false)
    static class ThreadScope {

        @Bean
        static CustomScopeConfigurer threadScope() {
            CustomScopeConfigurer configurer = new CustomScopeConfigurer();
            configurer.addScope("thread", new SimpleThreadScope());
            return configurer;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ScopedInvalidOperationConfig {

        @Bean
        @Scope(scopeName = "thread", proxyMode = ScopedProxyMode.TARGET_CLASS)
        InvalidNameOperation scopedInvalid() {
            return new InvalidNameOperation();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordOperationConfig {

        @Bean
        RecordOperation bookEcho() {
            return new RecordOperation("book-");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyInlineOperationsConfig {

        @Bean
        @Lazy
        QueryOperation<String, String> lazyInlineLookup() {
            return input -> input;
        }

        @Bean
        @Lazy
        CommandOperation<String, String> lazyInlineCommand() {
            return new CommandOperation<>() {

                @Override
                public String execute(String input) {
                    return input;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LambdaOperationConfig {

        @Bean
        QueryOperation<String, String> inlineLookup() {
            return input -> input;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AnonymousOperationConfig {

        @Bean
        CommandOperation<String, String> inlineCommand() {
            return new CommandOperation<>() {

                @Override
                public String execute(String input) {
                    return input;
                }
            };
        }
    }

}
