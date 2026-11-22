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
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.pipeline.OperationAdvisor;
import io.americanexpress.synapse.core.operation.CommandOperation;

import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.TestErrors;
import static io.americanexpress.synapse.core.autoconfigure.OperationFixtures.runner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BUILD.md §5.2 step 5: a {@code @Transactional} boundary sits inside the Synapse pipeline. */
class TransactionOrderingTest {

    /** The Synapse runner plus Boot's transaction auto-configuration, which enables transaction management. */
    private static ApplicationContextRunner transactionRunner() {
        return runner()
                .withConfiguration(AutoConfigurations.of(TransactionAutoConfiguration.class))
                .withUserConfiguration(Transactions.class);
    }

    @Test
    void transactionIsInsideThePipeline() {
        transactionRunner().run(context -> {
            SaveOperation operation = context.getBean(SaveOperation.class);
            RecordingTransactionManager manager = context.getBean(RecordingTransactionManager.class);

            assertThatThrownBy(() -> operation.execute("fail"))
                    .isInstanceOfSatisfying(SynapseException.class,
                            failure -> assertThat(failure.details()).isEqualTo(TestErrors.FIRST));
            assertThat(manager.events)
                    .containsExactly("begin", "execute in transaction: true", "rollback",
                            "translate in transaction: false");

            manager.events.clear();
            assertThat(operation.execute("ok")).isEqualTo("ok");
            assertThat(manager.events).containsExactly("begin", "execute in transaction: true", "commit");
        });
    }

    @Test
    void pipelineAdvisorComesBeforeTheTransactionAdvisor() {
        transactionRunner().run(context -> {
            List<Advisor> advisors = List.of(((Advised) context.getBean(SaveOperation.class)).getAdvisors());
            assertThat(advisors).hasSize(2);
            assertThat(advisors.get(0)).isInstanceOf(OperationAdvisor.class);
            assertThat(advisors.get(1)).isInstanceOf(BeanFactoryTransactionAttributeSourceAdvisor.class);
        });
    }

    /** A transaction manager that records what it is asked to do. */
    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }

    /** A command named {@code save} that runs in a transaction and fails on {@code "fail"}. */
    static class SaveOperation implements CommandOperation<String, String> {

        private final RecordingTransactionManager manager;

        SaveOperation(RecordingTransactionManager manager) {
            this.manager = manager;
        }

        @Override
        @Transactional
        public String execute(String input) {
            manager.events
                    .add("execute in transaction: " + TransactionSynchronizationManager.isActualTransactionActive());
            if ("fail".equals(input)) {
                throw new IllegalStateException("broken");
            }
            return input;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Transactions {

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        SaveOperation saveOperation(RecordingTransactionManager manager) {
            return new SaveOperation(manager);
        }

        @Bean
        ExceptionTranslator recordingTranslator(RecordingTransactionManager manager) {
            return exception -> {
                manager.events
                        .add("translate in transaction: "
                                + TransactionSynchronizationManager.isActualTransactionActive());
                return new SynapseException(TestErrors.FIRST, exception);
            };
        }
    }
}
