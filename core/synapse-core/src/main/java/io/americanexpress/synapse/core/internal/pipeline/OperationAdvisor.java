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
package io.americanexpress.synapse.core.internal.pipeline;

import java.util.function.Supplier;

import org.springframework.core.Ordered;

import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Applies the operation pipeline to every {@link ServiceOperation} bean. It is an infrastructure-role advisor picked up
 * by Spring's auto-proxy creator, ordered before (outside) the transaction advisor, so a {@code @Transactional}
 * boundary runs inside the pipeline (BUILD.md §5.2 step 5).
 */
public final class OperationAdvisor extends PipelineAdvisor {

    /** The advisor order: before the transaction advisor, whose default order is the lowest precedence. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;

    private static final long serialVersionUID = 1L;

    /**
     * Creates the advisor.
     *
     * @param pipeline supplies the pipeline on the first operation call
     */
    public OperationAdvisor(Supplier<OperationPipeline> pipeline) {
        super(ServiceOperation.class, () -> pipeline.get().operationInterceptor(), ORDER);
    }
}
