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
package io.americanexpress.synapse.api.grpc.internal.mapping;

/**
 * A record and a message that can't be mapped to each other. The binding that asked for the mapping wraps the
 * problem in its startup failure, which names the method and the operation.
 */
public final class MappingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String problem;

    private final String action;

    /**
     * Creates the exception.
     *
     * @param problem what is wrong, without the method's name
     * @param action what to do about it
     */
    public MappingException(String problem, String action) {
        super(problem);
        this.problem = problem;
        this.action = action;
    }

    /**
     * Returns what is wrong.
     *
     * @return the problem
     */
    public String problem() {
        return problem;
    }

    /**
     * Returns what to do about it.
     *
     * @return the action
     */
    public String action() {
        return action;
    }
}
