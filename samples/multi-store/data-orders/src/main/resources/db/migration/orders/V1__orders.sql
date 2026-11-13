-- The orders store's first migration, run by the store's own Flyway at startup. The table matches StoredOrder exactly,
-- since the store validates its entities against the schema when it starts. The audit columns are timestamp(6), the
-- precision Synapse truncates its stamps to.
CREATE TABLE orders (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    modified_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    customer VARCHAR(255) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL
);
