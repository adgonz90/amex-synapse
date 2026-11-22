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

import java.util.Objects;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.internal.pipeline.OperationPipeline;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Invokes operations through a real Synapse pipeline: context, deadline, validation and exception translation apply
 * as in a running service. Operation classes must not be final.
 */
public final class OperationInvoker {

    private final OperationPipeline pipeline;

    OperationInvoker(OperationPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    /**
     * Invokes a service operation through the pipeline.
     *
     * @param <I> the input type
     * @param <O> the output type
     * @param operation the operation
     * @param input the input
     * @return the operation's output
     * @throws io.americanexpress.synapse.core.error.SynapseException if the call fails
     * @throws IllegalArgumentException if the operation class is not valid (a lambda, anonymous, record or final
     *     class), with the reason Synapse gives at startup
     */
    public <I, O> O invoke(ServiceOperation<I, O> operation, I input) {
        ServiceOperation<I, O> proxy;
        try {
            proxy = pipeline.wrapOperation(operation);
        } catch (SynapseConfigurationException e) {
            throw new IllegalArgumentException(e.getMessage() + " " + e.action(), e);
        }
        return proxy.execute(input);
    }

    /**
     * Invokes a business operation through the reduced pipeline (validation and observation).
     *
     * @param <I> the input type
     * @param <O> the output type
     * @param operation the business operation
     * @param input the input
     * @return the operation's output
     * @throws io.americanexpress.synapse.core.error.SynapseException if the call fails
     * @throws IllegalArgumentException if the operation class is not valid (a lambda, anonymous, record or final
     *     class), with the reason Synapse gives at startup
     */
    public <I, O> O invoke(BusinessOperation<I, O> operation, I input) {
        BusinessOperation<I, O> proxy;
        try {
            proxy = pipeline.wrapBusiness(operation);
        } catch (SynapseConfigurationException e) {
            throw new IllegalArgumentException(e.getMessage() + " " + e.action(), e);
        }
        return proxy.execute(input);
    }
}
