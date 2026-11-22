-- The books store's first migration (JpaTransactionsIT): the table of Book.
CREATE TABLE book (id BIGINT PRIMARY KEY, title VARCHAR(255) NOT NULL);
