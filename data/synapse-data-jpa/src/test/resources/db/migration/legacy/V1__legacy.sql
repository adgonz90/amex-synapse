-- The legacy store's first migration (JpaTransactionsIT): the table of LegacyItem.
CREATE TABLE legacy_item (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL);
