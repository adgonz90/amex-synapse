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
package io.americanexpress.synapse.core.autoconfigure;

import java.net.URI;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Error-response settings shared by every Synapse API plugin.
 *
 * @param includeDetail Whether error responses include the formatted detail message. Off by default in every
 *     environment; turn it on in an environment file such as application-local.yaml. Detail messages can contain
 *     sensitive data; the full detail is always logged server-side, and stack traces are never returned.
 * @param typeBaseUri Base URI that error type URIs are built from. Unset by default.
 */
@ConfigurationProperties("synapse.errors")
public record SynapseErrorProperties(@DefaultValue("false") boolean includeDetail, @Nullable URI typeBaseUri) {
}
