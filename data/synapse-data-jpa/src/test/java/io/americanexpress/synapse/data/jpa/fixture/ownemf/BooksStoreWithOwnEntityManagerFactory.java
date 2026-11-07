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
package io.americanexpress.synapse.data.jpa.fixture.ownemf;

import java.util.Map;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import io.americanexpress.synapse.data.SynapseDataStore;

/**
 * Declares the books store and replaces its EntityManagerFactory with one of its own.
 */
@Configuration(proxyBeanMethods = false)
@SynapseDataStore("books")
public class BooksStoreWithOwnEntityManagerFactory {

    /** The persistence unit name of the replacement. */
    public static final String UNIT = "own-books";

    @Bean
    LocalContainerEntityManagerFactoryBean booksEntityManagerFactory(@Qualifier("books") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setPersistenceUnitName(UNIT);
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan(BooksStoreWithOwnEntityManagerFactory.class.getPackageName());
        factory
                .setJpaPropertyMap(Map
                        .of("jakarta.persistence.database-product-name", "H2",
                                "jakarta.persistence.database-product-version", "2.4",
                                "jakarta.persistence.database-major-version", "2",
                                "jakarta.persistence.database-minor-version", "4",
                                "hibernate.boot.allow_jdbc_metadata_access", "false"));
        return factory;
    }
}
