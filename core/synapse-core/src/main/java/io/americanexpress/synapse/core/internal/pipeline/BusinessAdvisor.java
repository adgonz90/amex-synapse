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

import io.americanexpress.synapse.core.operation.BusinessOperation;

/**
 * Applies the reduced pipeline to every {@link BusinessOperation} bean. Ordered just after the
 * {@link OperationAdvisor} and before the transaction advisor.
 */
public final class BusinessAdvisor extends PipelineAdvisor {

    /** The advisor order. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1001;

    private static final long serialVersionUID = 1L;

    /**
     * Creates the advisor.
     *
     * @param pipeline supplies the pipeline on the first business call
     */
    public BusinessAdvisor(Supplier<OperationPipeline> pipeline) {
        super(BusinessOperation.class, () -> pipeline.get().businessInterceptor(), ORDER);
    }
}
