-- The legacy-orders store's first migration (JpaTransactionsIT): the table of LegacyOrder.
CREATE TABLE legacy_order (id BIGINT PRIMARY KEY, reference VARCHAR(255) NOT NULL);
