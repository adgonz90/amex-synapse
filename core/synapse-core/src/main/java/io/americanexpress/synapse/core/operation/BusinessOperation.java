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
package io.americanexpress.synapse.core.operation;

/**
 * A unit of business logic called by service operations. It never calls operations itself.
 *
 * @param <I> the input type
 * @param <O> the output type
 */
public interface BusinessOperation<I, O> {

    /**
     * Runs the business logic.
     *
     * @param input the input
     * @return the output
     */
    O execute(I input);
}
