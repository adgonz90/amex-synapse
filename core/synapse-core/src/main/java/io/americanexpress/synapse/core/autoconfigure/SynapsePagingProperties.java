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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Paging settings applied by every API plugin.
 *
 * @param defaultSize Page size used when the caller does not ask for one. Must be at least 1 and at most max-size.
 * @param maxSize Largest page size a caller may ask for.
 */
@ConfigurationProperties("synapse.paging")
public record SynapsePagingProperties(@DefaultValue("20") int defaultSize, @DefaultValue("100") int maxSize) {

    /**
     * Validates the properties.
     *
     * @throws IllegalArgumentException unless {@code 1 <= defaultSize <= maxSize}
     */
    public SynapsePagingProperties {
        if (defaultSize < 1 || defaultSize > maxSize) {
            throw new IllegalArgumentException("synapse.paging.default-size (" + defaultSize
                    + ") must be at least 1 and at most synapse.paging.max-size (" + maxSize + ")");
        }
    }
}
