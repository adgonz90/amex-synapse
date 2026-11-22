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

import java.util.List;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the boot-time mapping model and the SessionFactory of one store's EntityManagerFactory, from Hibernate's
 * {@link Integrator#integrate} callback. {@code ddl-auto: update} needs the mapping model, which neither JPA nor the
 * SessionFactory exposes. {@link JpaStoreFactory} passes it to Hibernate as {@code hibernate.integrator_provider}.
 */
public final class MetadataKeeper implements IntegratorProvider {

    private final String store;

    private volatile @Nullable Metadata metadata;

    private volatile @Nullable SessionFactoryImplementor sessionFactory;

    /**
     * Creates the keeper of a store.
     *
     * @param store the store name
     */
    public MetadataKeeper(String store) {
        this.store = store;
    }

    @Override
    public List<Integrator> getIntegrators() {
        return List.of(new Integrator() {

            @Override
            public void integrate(Metadata metadata, BootstrapContext bootstrapContext,
                    SessionFactoryImplementor sessionFactory) {
                MetadataKeeper.this.metadata = metadata;
                MetadataKeeper.this.sessionFactory = sessionFactory;
            }

            @Override
            public void disintegrate(SessionFactoryImplementor sessionFactory,
                    SessionFactoryServiceRegistry serviceRegistry) {
                MetadataKeeper.this.metadata = null;
                MetadataKeeper.this.sessionFactory = null;
            }
        });
    }

    /**
     * Returns the mapping model of the store's EntityManagerFactory.
     *
     * @return the mapping model
     * @throws IllegalStateException when nothing is kept: Hibernate hasn't built the store's EntityManagerFactory, or
     *         has closed it
     */
    public Metadata metadata() {
        Metadata metadata = this.metadata;
        if (metadata == null) {
            throw notKept();
        }
        return metadata;
    }

    /**
     * Returns the SessionFactory of the store's EntityManagerFactory.
     *
     * @return the SessionFactory
     * @throws IllegalStateException when nothing is kept: Hibernate hasn't built the store's EntityManagerFactory, or
     *         has closed it
     */
    public SessionFactoryImplementor sessionFactory() {
        SessionFactoryImplementor sessionFactory = this.sessionFactory;
        if (sessionFactory == null) {
            throw notKept();
        }
        return sessionFactory;
    }

    private IllegalStateException notKept() {
        return new IllegalStateException("No mapping model is kept for the store '" + store + "'");
    }
}
