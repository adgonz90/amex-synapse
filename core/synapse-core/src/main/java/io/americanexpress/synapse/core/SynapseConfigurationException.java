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
package io.americanexpress.synapse.core;

/**
 * Thrown when the application is configured in a way Synapse cannot run. It carries a description of the problem and
 * the action that fixes it, so a failure analyzer can print both.
 */
public class SynapseConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String description;

    private final String action;

    /**
     * Creates the exception.
     *
     * @param description what is wrong
     * @param action what to do about it
     */
    public SynapseConfigurationException(String description, String action) {
        super(description);
        this.description = description;
        this.action = action;
    }

    /**
     * Creates the exception with the failure that caused it, such as a file's parser failure. The cause is for
     * diagnosis only: it is never shown to API callers, and Boot's failure reporter logs it only at {@code DEBUG}, while
     * the startup message prints the description and action. The exception's message is the description alone.
     *
     * @param description what is wrong
     * @param action what to do about it
     * @param cause the failure that caused it
     */
    public SynapseConfigurationException(String description, String action, Throwable cause) {
        super(description, cause);
        this.description = description;
        this.action = action;
    }

    /**
     * Returns what is wrong.
     *
     * @return the description
     */
    public String description() {
        return description;
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
