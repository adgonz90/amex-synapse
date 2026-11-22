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

import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.GeneratedFile;
import com.google.protobuf.TimestampProto;

/**
 * Stand-in for a protoc outer class with java_multiple_files=true: it contains no message, only the file descriptor.
 */
public final class BookCatalogProto extends GeneratedFile {

    private BookCatalogProto() {
    }

    public static FileDescriptor getDescriptor() {
        return TimestampProto.getDescriptor();
    }
}
