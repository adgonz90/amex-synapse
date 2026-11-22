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
package io.americanexpress.synapse.data;

/**
 * How a store's entities are accessed: the Synapse data module that builds the store's persistence beans.
 */
public enum DataAccess {

    /** The one access module on the class path: synapse-data-jpa or synapse-data-jdbc. */
    AUTO,

    /** JPA (Hibernate) and Spring Data JPA repositories, from synapse-data-jpa. */
    JPA,

    /** Spring Data JDBC aggregates and repositories, from synapse-data-jdbc. */
    JDBC
}
