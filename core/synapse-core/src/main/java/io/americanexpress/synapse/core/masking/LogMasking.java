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
package io.americanexpress.synapse.core.masking;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.core.internal.masking.MaskingModule;

/**
 * Writes values as JSON for log lines, masking members annotated with {@link Masked}. It uses its own mapper, built
 * from a base mapper, so masking never reaches API responses or any other use of the base mapper.
 */
public final class LogMasking {

    private final JsonMapper mapper;

    /**
     * Creates a log masker that follows the base mapper's configuration.
     *
     * @param base the mapper to copy settings from; it is not modified
     */
    public LogMasking(JsonMapper base) {
        this.mapper = base.rebuild().addModule(new MaskingModule()).build();
    }

    /**
     * Creates a log masker from a plain mapper with no Spring Boot customizations.
     *
     * @return a log masker
     */
    public static LogMasking standard() {
        return new LogMasking(JsonMapper.builder().build());
    }

    /**
     * Writes a value as JSON with {@link Masked} members masked. Never throws.
     *
     * @param value the value, possibly {@code null}
     * @return the JSON, or {@code "<unserializable ClassName>"} when the value cannot be serialized (no message or
     * value is included, because either may contain unmasked data)
     */
    public String toJson(@Nullable Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (RuntimeException | StackOverflowError e) {
            return "<unserializable " + (value == null ? "null" : value.getClass().getSimpleName()) + ">";
        }
    }
}
