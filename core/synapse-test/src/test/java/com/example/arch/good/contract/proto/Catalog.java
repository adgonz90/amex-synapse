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
package com.example.arch.good.contract.proto;

import com.google.protobuf.Internal;
import com.google.protobuf.MessageLite;
import com.google.protobuf.MessageLiteOrBuilder;

/**
 * Stand-in for a top-level generated message (java_multiple_files=true) with a nested oneof case enum.
 */
public final class Catalog implements MessageLiteOrBuilder {

    @Override
    public MessageLite getDefaultInstanceForType() {
        throw new UnsupportedOperationException("fixture");
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    /**
     * Stand-in for a generated oneof case enum, which implements {@link Internal.EnumLite} only.
     */
    public enum EntryCase implements Internal.EnumLite {
        ENTRY_NOT_SET;

        @Override
        public int getNumber() {
            return 0;
        }
    }
}
