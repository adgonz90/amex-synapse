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
package ${package}.data.${serviceName};

import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.data.SynapseDataStore;

/**
 * Declares the ${serviceName} store. Synapse builds it from {@code synapse.data.stores.${serviceName}.*} (this module's
 * {@code META-INF/synapse/config*.yaml}): a Hikari pool named {@code ${serviceName}}, Flyway migrations from
 * {@code db/migration/${serviceName}}, a JPA EntityManagerFactory for the entities of this package, the transaction
 * manager {@code @Transactional("${serviceName}")} names, and the repositories. Every bean carries the qualifier
 * {@code ${serviceName}}.
 *
 * <p>
 * It is the service's only store, so it is the primary one: an unqualified {@code @Transactional} uses it. Only JPA
 * is on the class path, so the store's access is JPA without naming it.
 */
@Configuration(proxyBeanMethods = false)
@SynapseDataStore("${serviceName}")
public class ServiceStore {
}
