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
package io.americanexpress.synapse.data.jpa.internal;

import java.util.HashMap;
import java.util.Map;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.SchemaValidationException;

import org.hibernate.cfg.SchemaToolingSettings;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.tool.schema.Action;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.data.internal.store.StoreLifecycle;
import io.americanexpress.synapse.data.internal.store.StoreStartStep;

/**
 * Runs a JPA store's schema action ({@code ddl-auto}) when the store starts, after its migrations. Hibernate itself
 * never runs one: the EntityManagerFactory is built with every action set to none, so building it opens no connection.
 * {@code validate}, {@code create} and {@code create-drop} run through JPA's {@link jakarta.persistence.SchemaManager};
 * {@code update} runs through Hibernate's {@link SchemaManagementToolCoordinator}, as the SchemaManager's own actions
 * do, with the mapping model the store's {@link MetadataKeeper} kept. As in Boot, {@code create} and
 * {@code create-drop} drop the store's tables and create them again, {@code create-drop} drops them again when the
 * store stops, and no action creates or drops a database schema. With {@code hibernate.hbm2ddl.halt_on_error}, which
 * {@link JpaStoreFactory} sets for these actions, a failed statement fails startup.
 *
 * <p>
 * The step has no order, so it runs after any ordered step of the store (see {@link StoreLifecycle}).
 */
public final class JpaSchemaAction implements StoreStartStep {

    private final String store;

    private final String action;

    private final EntityManagerFactory entityManagerFactory;

    private final MetadataKeeper metadata;

    private final String locations;

    /**
     * Creates the step.
     *
     * @param store the store name
     * @param action the schema action: {@code none}, {@code validate}, {@code update}, {@code create} or
     *        {@code create-drop}
     * @param entityManagerFactory the store's EntityManagerFactory
     * @param metadata the keeper of the mapping model of the store's EntityManagerFactory
     * @param locations where the store's migrations are, for the failure of a validation
     */
    public JpaSchemaAction(String store, String action, EntityManagerFactory entityManagerFactory,
            MetadataKeeper metadata, String locations) {
        this.store = store;
        this.action = action;
        this.entityManagerFactory = entityManagerFactory;
        this.metadata = metadata;
        this.locations = locations;
    }

    @Override
    public String store() {
        return store;
    }

    /**
     * Returns the schema action.
     *
     * @return the action, one of {@code none}, {@code validate}, {@code update}, {@code create} and
     *         {@code create-drop}
     */
    public String action() {
        return action;
    }

    /**
     * Runs the schema action.
     *
     * @throws SynapseConfigurationException when the schema doesn't match the entities ({@code validate})
     */
    @Override
    public void start() {
        switch (action) {
            case "validate" -> validate();
            case "create", "create-drop" -> {
                // As Boot's create: the previous tables and data go. Database schemas are never created or dropped.
                entityManagerFactory.getSchemaManager().drop(false);
                entityManagerFactory.getSchemaManager().create(false);
            }
            case "update" -> update();
            default -> {
                // none
            }
        }
    }

    /**
     * Drops the store's tables for {@code create-drop}, leaving database schemas alone.
     */
    @Override
    public void stop() {
        if ("create-drop".equals(action)) {
            entityManagerFactory.getSchemaManager().drop(false);
        }
    }

    private void validate() {
        try {
            entityManagerFactory.getSchemaManager().validate();
        } catch (SchemaValidationException ex) {
            // Hibernate's message names the table and column; it goes to the server log only. It is the whole of the
            // failure, so the exception isn't kept as the cause.
            throw new SynapseConfigurationException(
                    "The schema of the store '" + store + "' does not match its entities: " + ex.getMessage(),
                    "Add a migration in " + locations + " that brings the schema in line, or fix the mapping.");
        }
    }

    /**
     * Updates the schema as Hibernate's {@code SchemaManagerImpl} runs its actions: the SessionFactory's settings, with
     * the database action replaced.
     */
    private void update() {
        SessionFactoryImplementor sessionFactory = metadata.sessionFactory();
        Map<String, Object> settings = new HashMap<>(sessionFactory.getProperties());
        settings.put(SchemaToolingSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, Action.UPDATE);
        settings.put(SchemaToolingSettings.JAKARTA_HBM2DDL_SCRIPTS_ACTION, Action.NONE);
        SchemaManagementToolCoordinator
                .process(metadata.metadata(), sessionFactory.getServiceRegistry(), settings, dropAction -> {
                });
    }
}
