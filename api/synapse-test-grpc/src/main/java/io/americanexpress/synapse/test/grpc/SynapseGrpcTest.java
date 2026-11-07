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
package io.americanexpress.synapse.test.grpc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTestContextBootstrapper;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test slice for the gRPC API: starts the Synapse gRPC plugin (compiled bindings, edge interceptors, error details)
 * with the application's {@code GrpcBindings} configurations, hand-written services ({@code BindableService}
 * implementations), {@code @GrpcAdvice} classes, {@code GrpcExceptionHandler}s and {@code ServerInterceptor}s, on
 * Spring Boot's in-process test transport with no network server. Operations, business beans and every other
 * component are left out. A configuration class that declares an interceptor, exception handler or service as a
 * {@code @Bean} is left out too; the test brings it in with {@code @Import}.
 *
 * <p>
 * The test declares a {@code @MockitoBean} (or a {@code @TestBean}) for each operation the bindings use; the slice
 * fails before any bean is created and names each operation that has none. Operations that {@code .bindAllByName()}
 * finds are not parameters of the bindings' {@code @Bean} method, so the slice cannot name them in advance: a method
 * whose operation has no test double fails startup with the method named. Calls are made with client stubs, which
 * {@code @ImportGrpcClients} registers on the in-process channel. The slice registers a
 * {@link io.americanexpress.synapse.test.time.MutableClock} fixed at {@code 2026-01-01T00:00:00Z} unless the test
 * defines its own {@link java.time.Clock}, and activates the {@code test} profile; test settings such as
 * {@code synapse.errors.include-detail} go in the module's {@code application-test.yaml}.
 *
 * <p>
 * Boot's in-process test server applies no server builder customizers, so the slice runs without what they set up
 * on the application's server:
 * <ul>
 * <li>the inbound message and metadata size limits ({@code spring.grpc.server.inbound.*}), so an oversized message
 * or metadata is accepted;</li>
 * <li>keepalive;</li>
 * <li>Synapse's fallback handlers, so a call to a method that no service declares is answered with grpc-java's own
 * {@code UNIMPLEMENTED}, not with Synapse's error details;</li>
 * <li>the virtual-thread executor of {@code spring.threads.virtual.enabled}.</li>
 * </ul>
 * Test those against the application's server, in a {@code @SpringBootTest} on a random port.
 *
 * @see AutoConfigureSynapseGrpc
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@BootstrapWith(SpringBootTestContextBootstrapper.class)
@ExtendWith(SpringExtension.class)
@OverrideAutoConfiguration(enabled = false)
@TypeExcludeFilters(SynapseGrpcTypeExcludeFilter.class)
@AutoConfigureSynapseGrpc
@AutoConfigureTestGrpcTransport
@ActiveProfiles("test")
public @interface SynapseGrpcTest {
}
