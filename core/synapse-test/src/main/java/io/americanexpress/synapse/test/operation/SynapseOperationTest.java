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
package io.americanexpress.synapse.test.operation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTestContextBootstrapper;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test slice for operations: starts only the {@code ServiceOperation} and {@code BusinessOperation} beans of the
 * application, with the real Synapse pipeline (validation, observation, tracing, error translation, context) but no web
 * server, protocol binding, data store or other component.
 *
 * <p>
 * Collaborators of an operation (ports, clients, repositories) are replaced with
 * {@code @MockitoBean} or supplied by a {@code @TestConfiguration}. The slice registers a
 * {@link io.americanexpress.synapse.test.time.MutableClock} fixed at {@code 2026-01-01T00:00:00Z} unless the test
 * defines its own {@link java.time.Clock}, and activates the {@code test} profile.
 *
 * @see AutoConfigureSynapseOperation
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@BootstrapWith(SpringBootTestContextBootstrapper.class)
@ExtendWith(SpringExtension.class)
@OverrideAutoConfiguration(enabled = false)
@TypeExcludeFilters(SynapseOperationTypeExcludeFilter.class)
@AutoConfigureSynapseOperation
@ActiveProfiles("test")
public @interface SynapseOperationTest {
}
