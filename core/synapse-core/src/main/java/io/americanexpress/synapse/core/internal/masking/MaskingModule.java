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

import java.util.List;
import java.util.UUID;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.ser.std.StdSerializer;

import io.americanexpress.synapse.core.masking.MaskType;
import io.americanexpress.synapse.core.masking.Masked;

/**
 * Jackson module that masks members annotated with {@link Masked}. Register it only on the mapper used for logs.
 */
public final class MaskingModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    private static final String FIXED_MASK = "***";

    /** Creates the module. */
    public MaskingModule() {
        super("synapse-masking");
        setSerializerModifier(new Modifier());
    }

    private static final class Modifier extends ValueSerializerModifier {

        private static final long serialVersionUID = 1L;

        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription.Supplier beanDesc,
                List<BeanPropertyWriter> beanProperties) {
            for (BeanPropertyWriter writer : beanProperties) {
                Masked masked = writer.getAnnotation(Masked.class);
                if (masked != null) {
                    writer.assignSerializer(new MaskingSerializer(masked.value()));
                }
            }
            return beanProperties;
        }
    }

    private static final class MaskingSerializer extends StdSerializer<Object> {

        private final MaskType type;

        MaskingSerializer(MaskType type) {
            super(Object.class);
            this.type = type;
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializationContext context) {
            gen.writeString(isScalar(value) ? type.apply(value.toString()) : FIXED_MASK);
        }

        private static boolean isScalar(Object value) {
            return value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                    || value instanceof Character || value instanceof Enum<?> || value instanceof UUID
                    || value.getClass().getName().startsWith("java.time.");
        }
    }
}
