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
package io.americanexpress.synapse.data.internal.audit;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.AuditorAware;

import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AuditStampsTest {

    private static final AuditorAware<String> SYNAPSE = () -> Optional.of("synapse");

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00.123456789Z");

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    @Test
    void nowIsTheClocksInstantTruncatedToMicroseconds() {
        AuditStamps stamps = stamps();

        assertThat(stamps.now()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
    }

    @Test
    void truncatesAndNeverRounds() {
        assertThat(AuditStamps.truncate(Instant.parse("2026-01-01T00:00:00.999999999Z")))
                .isEqualTo(Instant.parse("2026-01-01T00:00:00.999999Z"));
        assertThat(AuditStamps.truncate(Instant.parse("2026-01-01T00:00:00.123456400Z")))
                .isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
        assertThat(AuditStamps.truncate(Instant.parse("2026-01-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void withoutAnApplicationAuditorTheUserIsSynapses() {
        assertThat(stamps().user()).isEqualTo("synapse");
    }

    @Test
    void theApplicationsStringAuditorWins() {
        register("userAuditor", ResolvableType.forClassWithGenerics(AuditorAware.class, String.class),
                () -> Optional.of("ana"));

        assertThat(stamps().user()).isEqualTo("ana");
    }

    @Test
    void anAuditorOfAnotherTypeIsIgnored() {
        register("userIdAuditor", ResolvableType.forClassWithGenerics(AuditorAware.class, Long.class),
                () -> Optional.of(42L));

        assertThat(stamps().user()).isEqualTo("synapse");
    }

    @Test
    void aRawAuditorIsUsed() {
        beanFactory
                .registerBeanDefinition("rawAuditor",
                        new RootBeanDefinition(AuditorAware.class,
                                () -> (AuditorAware<String>) () -> Optional.of("raw")));

        assertThat(stamps().user()).isEqualTo("raw");
    }

    @Test
    void anEmptyAuditorMeansTheSystemUser() {
        register("userAuditor", ResolvableType.forClassWithGenerics(AuditorAware.class, String.class),
                Optional::empty);

        assertThat(stamps().user()).isEqualTo("batch");
    }

    /** The application's auditor may depend on the store (a user repository), so it is looked up on first use. */
    @Test
    void theApplicationsAuditorIsLookedUpWhenFirstUsed() {
        AuditStamps stamps = stamps();
        register("userAuditor", ResolvableType.forClassWithGenerics(AuditorAware.class, String.class),
                () -> Optional.of("ana"));

        assertThat(stamps.user()).isEqualTo("ana");
    }

    @Test
    void theAuditorIsResolvedOnceAndAskedEveryTime() {
        AtomicInteger asked = new AtomicInteger();
        register("userAuditor", ResolvableType.forClassWithGenerics(AuditorAware.class, String.class), () -> {
            asked.incrementAndGet();
            return Optional.of("ana");
        });
        AuditStamps stamps = stamps();
        stamps.afterSingletonsInstantiated();
        beanFactory.removeBeanDefinition("userAuditor");

        assertThat(stamps.user()).isEqualTo("ana");
        assertThat(stamps.user()).isEqualTo("ana");
        assertThat(asked).hasValue(2);
    }

    @Test
    void twoApplicationStringAuditorsFailWhenTheContextHasStarted() {
        ResolvableType type = ResolvableType.forClassWithGenerics(AuditorAware.class, String.class);
        register("first", type, () -> Optional.of("first"));
        register("second", type, () -> Optional.of("second"));

        assertThatExceptionOfType(NoUniqueBeanDefinitionException.class)
                .isThrownBy(() -> stamps().afterSingletonsInstantiated());
    }

    /**
     * Builds the stamps on the provider a bean gets injected, with the resolver of an application context (generic
     * types and qualifiers), beside Synapse's own auditor, which is not a default candidate.
     */
    private AuditStamps stamps() {
        beanFactory.setAutowireCandidateResolver(new ContextAnnotationAutowireCandidateResolver());
        if (!beanFactory.containsBeanDefinition(AuditStamps.SYNAPSE_AUDITOR)) {
            RootBeanDefinition synapse = new RootBeanDefinition();
            synapse.setTargetType(ResolvableType.forClassWithGenerics(AuditorAware.class, String.class));
            synapse.setInstanceSupplier(() -> SYNAPSE);
            synapse.setDefaultCandidate(false);
            beanFactory.registerBeanDefinition(AuditStamps.SYNAPSE_AUDITOR, synapse);
        }
        RootBeanDefinition holder = new RootBeanDefinition(Injected.class);
        holder.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
        holder.setScope(BeanDefinition.SCOPE_PROTOTYPE);
        beanFactory.registerBeanDefinition("injected", holder);
        return new AuditStamps(clock, beanFactory.getBean(Injected.class).auditors, SYNAPSE, "batch");
    }

    /** Receives the provider as Synapse's auto-configuration does. */
    static final class Injected {

        final ObjectProvider<AuditorAware<String>> auditors;

        Injected(ObjectProvider<AuditorAware<String>> auditors) {
            this.auditors = auditors;
        }
    }

    private void register(String name, ResolvableType type, AuditorAware<?> auditor) {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setTargetType(type);
        definition.setInstanceSupplier(() -> auditor);
        beanFactory.registerBeanDefinition(name, definition);
    }
}
