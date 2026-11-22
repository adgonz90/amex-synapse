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
package io.americanexpress.synapse.core.autoconfigure;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-operation settings, under {@code synapse.operations.<operation-name>}.
 *
 * @param operations Settings of each operation, keyed by operation name (for example get-book).
 */
@ConfigurationProperties("synapse")
public record SynapseOperationsProperties(@DefaultValue Map<String, Operation> operations) {

    /**
     * Settings of one operation.
     *
     * @param logging Logging settings of the operation.
     */
    public record Operation(@DefaultValue Logging logging) {
    }

    /**
     * Logging settings of one operation.
     *
     * @param payloads Whether the operation's input and output are logged. Fields annotated with @Masked are masked.
     */
    public record Logging(@DefaultValue("false") boolean payloads) {
    }
}
