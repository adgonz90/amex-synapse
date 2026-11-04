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
package io.americanexpress.synapse.api.rest.paging;

import io.americanexpress.synapse.core.Incubating;

/**
 * Turns an operation's raw cursor into the opaque, URL-safe token a caller receives, and a token the caller sends back
 * into the raw cursor. A token is bound to its {@link CursorScope}: it decodes only for the same operation, sort and
 * input digest. The default codec uses AES-256-GCM authenticated encryption; define a bean of this type to replace
 * it.
 *
 * <p>Implementations must never log or return a token's contents, a raw cursor or key material.
 */
@Incubating
public interface CursorCodec {

    /**
     * Wraps a raw cursor in a token.
     *
     * @param cursor the operation's raw cursor
     * @param scope the request the token is bound to
     * @return the token: not empty, and only the URL-safe characters {@code A-Z}, {@code a-z}, {@code 0-9},
     *     {@code -} and {@code _} (base64url without padding qualifies; standard base64, with {@code +}, {@code /}
     *     or {@code =}, doesn't). Synapse checks every token and answers a request whose token breaks this rule with
     *     a 500, so the token never reaches a {@code Link} header or a query string unescaped
     */
    String encode(String cursor, CursorScope scope);

    /**
     * Unwraps a token into the raw cursor it was made from.
     *
     * @param token the token the caller sent
     * @param scope the request the token must be bound to
     * @return the raw cursor
     * @throws InvalidCursorException for every token it rejects: malformed, tampered, issued for another scope or by
     *     an unknown key, or expired
     */
    String decode(String token, CursorScope scope);
}
