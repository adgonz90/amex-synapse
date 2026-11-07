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
package io.americanexpress.synapse.api.grpc.internal.service;

import java.util.Objects;

import io.grpc.ServerServiceDefinition;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.internal.binding.ServiceDeclaration;

/**
 * Synapse's access to the members of {@link GrpcBindings} that are not public API: the declaration the bindings were
 * built from, and attaching the service compiled from it. {@code GrpcBindings} registers the access when its class is
 * initialized, which is before any instance exists, so every call that passes bindings finds it.
 */
public final class GrpcBindingsAccess {

    private static volatile @Nullable Access access;

    private GrpcBindingsAccess() {
    }

    /**
     * Registers the access. {@code GrpcBindings} calls it once, when its class is initialized.
     *
     * @param granted the access
     * @throws IllegalStateException when the access is already registered
     */
    public static synchronized void register(Access granted) {
        if (access != null) {
            throw new IllegalStateException("The GrpcBindings access is already registered");
        }
        access = granted;
    }

    /**
     * Returns the bindings as declared.
     *
     * @param bindings the bindings
     * @return the declaration
     */
    public static ServiceDeclaration declaration(GrpcBindings bindings) {
        return access().declaration(bindings);
    }

    /**
     * Attaches the service compiled from the bindings; {@link GrpcBindings#bindService()} returns it from then on.
     *
     * @param bindings the bindings
     * @param compiled the service definition
     * @throws IllegalStateException when the bindings were already compiled
     */
    public static void compiled(GrpcBindings bindings, ServerServiceDefinition compiled) {
        access().compiled(bindings, compiled);
    }

    private static Access access() {
        return Objects.requireNonNull(access, "GrpcBindings registers its access when its class is initialized");
    }

    /** The members of {@link GrpcBindings} that Synapse reaches through this class. */
    public interface Access {

        /**
         * Returns the bindings as declared.
         *
         * @param bindings the bindings
         * @return the declaration
         */
        ServiceDeclaration declaration(GrpcBindings bindings);

        /**
         * Attaches the service compiled from the bindings.
         *
         * @param bindings the bindings
         * @param compiled the service definition
         */
        void compiled(GrpcBindings bindings, ServerServiceDefinition compiled);
    }
}
