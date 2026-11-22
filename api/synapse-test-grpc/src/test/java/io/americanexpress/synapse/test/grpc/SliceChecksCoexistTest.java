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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.support.StaticMessageSource;

import io.americanexpress.synapse.api.grpc.autoconfigure.SynapseGrpcAutoConfiguration;
import io.americanexpress.synapse.api.grpc.autoconfigure.SynapseGrpcErrorAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.test.rest.SynapseRestTestAutoConfiguration;
import io.americanexpress.synapse.test.slice.BoundOperationMocksCheck;

import static org.assertj.core.api.Assertions.assertThat;

/** The REST and gRPC slices' checks share a class, so each is backed off by its own bean name, not by type. */
class SliceChecksCoexistTest {

    @Test
    void bothSlicesChecksExistSideBySideUnderTheirOwnNames() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(SynapseGrpcAutoConfiguration.class, SynapseCoreAutoConfiguration.class,
                                SynapseContextAutoConfiguration.class, SynapseGrpcErrorAutoConfiguration.class,
                                SynapseGrpcTestAutoConfiguration.class, SynapseRestTestAutoConfiguration.class))
                .withBean(ErrorMessages.class, () -> new ErrorMessages(new StaticMessageSource()))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasBean("synapseRestBoundOperationMocksCheck")
                        .hasBean("synapseGrpcBoundOperationMocksCheck")
                        .getBeans(BoundOperationMocksCheck.class)
                        .hasSize(2));
    }
}
