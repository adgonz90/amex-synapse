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

import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * The kind of a service operation, used to tag telemetry.
 */
public enum OperationKind {

    /** A {@link QueryOperation}. */
    QUERY("query"),

    /** A {@link CommandOperation}. */
    COMMAND("command");

    private final String tagValue;

    OperationKind(String tagValue) {
        this.tagValue = tagValue;
    }

    /**
     * Returns the value used for the {@code kind} tag.
     *
     * @return {@code "query"} or {@code "command"}
     */
    public String tagValue() {
        return tagValue;
    }

    /**
     * Returns the kind of an operation class.
     *
     * @param operationClass the operation class, or a proxy subclass of it
     * @return the kind
     * @throws SynapseConfigurationException if the class implements both or neither kind
     */
    public static OperationKind of(Class<?> operationClass) {
        Class<?> type = ClassUtils.getUserClass(operationClass);
        boolean query = QueryOperation.class.isAssignableFrom(type);
        boolean command = CommandOperation.class.isAssignableFrom(type);
        if (query && command) {
            throw new SynapseConfigurationException(
                    type.getName() + " implements both QueryOperation and CommandOperation.",
                    "Implement exactly one of them.");
        }
        if (!query && !command) {
            throw new SynapseConfigurationException(
                    type.getName() + " implements neither QueryOperation nor CommandOperation.",
                    "Implement exactly one of them.");
        }
        return query ? QUERY : COMMAND;
    }
}
