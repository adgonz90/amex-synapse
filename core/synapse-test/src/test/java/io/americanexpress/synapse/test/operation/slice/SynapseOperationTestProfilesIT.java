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

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.test.operation.SynapseOperationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How {@code @ActiveProfiles} on a test class combines with the {@code test} profile that the slice activates, as the
 * environments page documents. Each case loads the fixture class's context the way the Spring extension does.
 */
class SynapseOperationTestProfilesIT {

    @Test
    void activeProfilesOnTheAnnotatedClassReplacesTest() throws Exception {
        TestContextManager manager = new TestContextManager(OwnEnvironment.class);
        try {
            ApplicationContext context = manager.getTestContext().getApplicationContext();
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("e2");
            assertThat(context.getEnvironment().getProperty("fixture.environment")).isEqualTo("e2");
        } finally {
            manager.getTestContext().markApplicationContextDirty(null);
        }
    }

    @Test
    void activeProfilesOnASubclassAddsToTheInheritedTestAndFailsStartup() {
        TestContextManager manager = new TestContextManager(InheritingSubclass.class);
        assertThatThrownBy(() -> manager.getTestContext().getApplicationContext())
                .rootCause()
                .isInstanceOfSatisfying(SynapseConfigurationException.class, ex -> assertThat(ex.description())
                        .isEqualTo("Exactly one environment must be active, but there are 2: test, e2."));
    }

    @Test
    void inheritProfilesFalseOnASubclassReplacesTest() throws Exception {
        TestContextManager manager = new TestContextManager(ReplacingSubclass.class);
        try {
            ApplicationContext context = manager.getTestContext().getApplicationContext();
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("e2");
            assertThat(context.getEnvironment().getProperty("fixture.environment")).isEqualTo("e2");
        } finally {
            manager.getTestContext().markApplicationContextDirty(null);
        }
    }

    @SynapseOperationTest
    @ActiveProfiles("e2")
    static class OwnEnvironment {

        @MockitoBean
        BookRepositoryPort repository;
    }

    @SynapseOperationTest
    static class SliceTest {

        @MockitoBean
        BookRepositoryPort repository;
    }

    @ActiveProfiles("e2")
    static class InheritingSubclass extends SliceTest {
    }

    @ActiveProfiles(value = "e2", inheritProfiles = false)
    static class ReplacingSubclass extends SliceTest {
    }
}
