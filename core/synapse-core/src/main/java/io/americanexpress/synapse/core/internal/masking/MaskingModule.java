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

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

import io.americanexpress.synapse.core.masking.Masked;

/**
 * Jackson module that masks members annotated with {@link Masked}. Register it only on the mapper used for logs. Each
 * masked member's writer is replaced by one that writes the mask, so a member with a serializer of its own
 * ({@code @JsonSerialize(using = ...)}) is masked too.
 */
public final class MaskingModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

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
            List<BeanPropertyWriter> writers = new ArrayList<>(beanProperties.size());
            for (BeanPropertyWriter writer : beanProperties) {
                Masked masked = writer.getAnnotation(Masked.class);
                writers.add(masked == null ? writer : new MaskedPropertyWriter(writer, masked.value()));
            }
            return writers;
        }
    }
}
