-- The books store's first migration, run by the store's own Flyway at startup. The table matches StoredBook exactly,
-- since the store validates its entities against the schema when it starts. The audit columns are timestamp(6), the
-- precision Synapse truncates its stamps to.
--
-- The unique constraint is what keeps ISBNs unique: a second book with one is ALREADY_EXISTS, also when two requests
-- race. Schema validation doesn't check constraints, so only BookRepositoryIT notices if it goes missing.
--
-- There is no index on author: the search is a contains search (upper(author) like '%...%'), which no B-tree index
-- serves, so it reads the whole table. BookRepository says what to add for a large catalog (a pg_trgm index).
CREATE TABLE book (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    modified_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    isbn VARCHAR(17) NOT NULL,
    title VARCHAR(255) NOT NULL,
    author VARCHAR(255) NOT NULL,
    published_year INTEGER,
    CONSTRAINT book_isbn_key UNIQUE (isbn)
);
