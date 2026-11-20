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
package io.americanexpress.synapse.core.internal.masking;

import java.util.UUID;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.PropertyName;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.BeanPropertyWriter;

import io.americanexpress.synapse.core.masking.MaskType;

/**
 * Writes a {@code @Masked} property as its mask, whatever serializer the property has: it replaces the property's
 * writer, because Jackson doesn't let a module replace a serializer that an annotation such as
 * {@code @JsonSerialize(using = ...)} already assigned. A scalar is masked by its {@link MaskType}, anything else is
 * {@value #FIXED_MASK}, and {@code null} is written as Jackson writes it for the property.
 */
final class MaskedPropertyWriter extends BeanPropertyWriter {

    private static final long serialVersionUID = 1L;

    private static final String FIXED_MASK = "***";

    private final MaskType type;

    /**
     * Creates the writer of a masked property.
     *
     * @param base the property's writer
     * @param type how the property is masked
     */
    MaskedPropertyWriter(BeanPropertyWriter base, MaskType type) {
        super(base);
        this.type = type;
    }

    private MaskedPropertyWriter(MaskedPropertyWriter base, PropertyName name) {
        super(base, name);
        this.type = base.type;
    }

    /** Keeps the mask when the property is renamed, as an unwrapping prefix renames it. */
    @Override
    protected BeanPropertyWriter _new(PropertyName newName) {
        return new MaskedPropertyWriter(this, newName);
    }

    @Override
    public void serializeAsProperty(Object bean, JsonGenerator g, SerializationContext ctxt) throws Exception {
        Object value = get(bean);
        if (value == null) {
            super.serializeAsProperty(bean, g, ctxt);
            return;
        }
        g.writeName(getName());
        g.writeString(mask(value));
    }

    @Override
    public void serializeAsElement(Object bean, JsonGenerator g, SerializationContext ctxt) throws Exception {
        Object value = get(bean);
        if (value == null) {
            super.serializeAsElement(bean, g, ctxt);
            return;
        }
        g.writeString(mask(value));
    }

    private String mask(Object value) {
        return isScalar(value) ? type.apply(value.toString()) : FIXED_MASK;
    }

    private static boolean isScalar(Object value) {
        return value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?> || value instanceof UUID
                || value.getClass().getName().startsWith("java.time.");
    }
}
