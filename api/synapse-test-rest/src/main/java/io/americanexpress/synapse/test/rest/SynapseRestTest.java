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
package io.americanexpress.synapse.test.rest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test slice for the REST API: starts the Synapse REST plugin (routes, edge filters, problem responses) with the
 * application's {@code RestBindings} and {@code RestConventions} configurations, controllers, controller advice, MVC
 * configurers, converters and servlet filters, on {@code MockMvc} with no web server. Operations, business beans and
 * every other component are left out.
 *
 * <p>
 * The test declares a {@code @MockitoBean} (or a {@code @TestBean}) for each operation the
 * bindings use; the slice fails before any bean is created and names each operation that has none. Calls are made
 * with the {@code RestTestClient}, which is bound to {@code MockMvc} (the same client works against a running
 * server). The slice registers a {@link io.americanexpress.synapse.test.time.MutableClock} fixed at
 * {@code 2026-01-01T00:00:00Z} unless the test defines its own {@link java.time.Clock}, and activates the {@code test}
 * profile; test settings such as {@code synapse.errors.include-detail} go in the module's
 * {@code application-test.yaml}.
 *
 * @see AutoConfigureSynapseRest
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@BootstrapWith(SynapseRestTestContextBootstrapper.class)
@ExtendWith(SpringExtension.class)
@OverrideAutoConfiguration(enabled = false)
@TypeExcludeFilters(SynapseRestTypeExcludeFilter.class)
@AutoConfigureSynapseRest
@AutoConfigureMockMvc
@AutoConfigureRestTestClient
@ActiveProfiles("test")
public @interface SynapseRestTest {
}
