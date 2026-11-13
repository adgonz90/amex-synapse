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

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;

import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.internal.paging.AesGcmCursorCodec;
import io.americanexpress.synapse.core.internal.paging.CursorKeys;
import io.americanexpress.synapse.core.internal.paging.InputDigest;
import io.americanexpress.synapse.core.internal.paging.PageCursors;

/**
 * Service compilers and page cursors for the tests, as core's auto-configuration builds them: core's default page
 * sizes and offset cap, and a fixed cursor key.
 */
public final class Compilers {

    /** A base64 256-bit cursor key (the bytes 0 to 31). */
    public static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    /** Core's default paging properties, with {@link #KEY}. */
    public static final SynapsePagingProperties PAGING = new SynapsePagingProperties(20, 100, 1000,
            new SynapsePagingProperties.Cursor(KEY, List.of(), null));

    private Compilers() {
    }

    /**
     * Returns page cursors on {@link #KEY}.
     *
     * @return the cursors
     */
    public static PageCursors cursors() {
        SecureRandom random = new SecureRandom();
        return new PageCursors(
                new AesGcmCursorCodec(CursorKeys.from(PAGING.cursor(), random), Clock.systemUTC(), null, random),
                new InputDigest());
    }

    /**
     * Returns a compiler with {@link #cursors()} and {@link #PAGING}.
     *
     * @return the compiler
     */
    public static ServiceCompiler compiler() {
        return new ServiceCompiler(cursors(), PAGING);
    }

    /**
     * Returns a compiler with a hint for an unbound method.
     *
     * @param unboundHint the hint
     * @return the compiler
     */
    public static ServiceCompiler compiler(String unboundHint) {
        return new ServiceCompiler(cursors(), PAGING, unboundHint);
    }
}
