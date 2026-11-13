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
/**
 * The service's configuration that belongs to no protocol and no operation, such as beans for the platform it runs
 * on. Spring Boot and Synapse configure everything else the service needs from its {@code application*.yaml} files
 * and the data module's settings. {@code OpenTelemetryAgentConfiguration} hands Boot's tracing bridge the
 * OpenTelemetry Java agent's SDK when the service runs under the agent, and {@code OpenTelemetryAgentAotConfiguration}
 * fails an AOT-mode start under the agent whose generated code lacks that bean. A protocol's configuration goes in its
 * {@code api.<protocol>} package, never here.
 */
@NullMarked
package ${package}.config;

import org.jspecify.annotations.NullMarked;
