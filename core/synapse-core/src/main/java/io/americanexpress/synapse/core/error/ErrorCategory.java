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
package io.americanexpress.synapse.core.error;

/**
 * Protocol-agnostic classification of a failure. API plugins map each category to a protocol status.
 */
public enum ErrorCategory {

    /** The caller sent input that is malformed or invalid. */
    INVALID_INPUT,

    /** The caller is not authenticated. */
    UNAUTHENTICATED,

    /** The caller is authenticated but not allowed. */
    FORBIDDEN,

    /** The requested resource does not exist. */
    NOT_FOUND,

    /** The request conflicts with the current state of the resource. */
    CONFLICT,

    /** A precondition of the request was not met. */
    PRECONDITION_FAILED,

    /** The caller sent too many requests. */
    RATE_LIMITED,

    /** The service or a dependency is temporarily unavailable. */
    UNAVAILABLE,

    /** A deadline expired. */
    TIMEOUT,

    /** An unexpected failure inside the service. */
    INTERNAL
}
