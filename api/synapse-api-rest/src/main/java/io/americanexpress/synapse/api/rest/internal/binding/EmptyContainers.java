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
package io.americanexpress.synapse.api.rest.internal.binding;

import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.ValueDeserializerModifier;
import tools.jackson.databind.deser.std.DelegatingDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.CollectionType;
import tools.jackson.databind.type.MapType;
import tools.jackson.databind.util.AccessPattern;

/**
 * Makes the binder's mapper read an absent or {@code null} collection or map as an empty one, in every record of the
 * body, as a gRPC {@code repeated} or {@code map} field is never missing. Jackson asks for an absent value only for a
 * creator property (a record component), so a bean's field the body leaves out keeps its initializer; a {@code null}
 * sent to its setter reads empty.
 *
 * <p>
 * Each collection and map deserializer Jackson builds (any declared type: {@code List}, {@code Set}, {@code Map},
 * their sorted forms, a concrete type such as {@code ArrayList}, or a module's type) answers its null and absent value
 * with its empty value. That is a limit too: a deserializer without an empty value of its own (a module's, for example)
 * answers {@code null} for it, so its type keeps {@code null}, and a collection-like or map-like type (Jackson's
 * {@code CollectionLikeType} and {@code MapLikeType}, not a {@code Collection} or {@code Map}) isn't wrapped. A
 * property's null provider is its deserializer unless the property declares other null handling, so a component
 * with {@code @JsonSetter(nulls = ...)} keeps its own rule, and one with its own {@code @JsonDeserialize(using = ...)}
 * keeps what that deserializer answers. A per-type config override would not do: Jackson finds one by the property's
 * exact raw type, so an override on {@code List} misses an {@code ArrayList} component. Arrays are not collections and
 * keep {@code null}.
 *
 * <p>
 * A deserializer is also the null provider of the items or values it holds, so a {@code null} item that is itself a
 * collection or map reads empty too ({@code [null]} is {@code [[]]} for a {@code List<List<String>>}), as a gRPC slot
 * that holds one is never {@code null}.
 */
final class EmptyContainers extends ValueDeserializerModifier {

    private static final long serialVersionUID = 1L;

    private EmptyContainers() {
    }

    /**
     * Returns the module that installs the rule.
     *
     * @return the module
     */
    static JacksonModule module() {
        return new SimpleModule("synapse-rest-empty-containers").setDeserializerModifier(new EmptyContainers());
    }

    @Override
    public ValueDeserializer<?> modifyCollectionDeserializer(DeserializationConfig config, CollectionType type,
            BeanDescription.Supplier beanDescRef, ValueDeserializer<?> deserializer) {
        return new EmptyWhenNull(deserializer);
    }

    @Override
    public ValueDeserializer<?> modifyMapDeserializer(DeserializationConfig config, MapType type,
            BeanDescription.Supplier beanDescRef, ValueDeserializer<?> deserializer) {
        return new EmptyWhenNull(deserializer);
    }

    /** A collection or map deserializer whose null and absent values are its empty value. */
    private static final class EmptyWhenNull extends DelegatingDeserializer {

        EmptyWhenNull(ValueDeserializer<?> delegatee) {
            super(delegatee);
        }

        @Override
        protected ValueDeserializer<?> newDelegatingInstance(ValueDeserializer<?> newDelegatee) {
            return new EmptyWhenNull(newDelegatee);
        }

        @Override
        public AccessPattern getNullAccessPattern() {
            // A new empty value each time: never one shared, mutable instance.
            return AccessPattern.DYNAMIC;
        }

        @Override
        public Object getNullValue(DeserializationContext ctxt) {
            return getDelegatee().getEmptyValue(ctxt);
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return getDelegatee().getEmptyValue(ctxt);
        }
    }
}
