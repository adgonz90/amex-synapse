-- The ${serviceName} store's first migration, run by the store's own Flyway at startup. The table matches StoredItem
-- exactly, since the store validates its entities against the schema when it starts. The audit columns are
-- timestamp(6), the precision Synapse truncates its stamps to.
--
-- The unique constraint is what keeps names unique: a second item with one is ALREADY_EXISTS, also when two requests
-- race. Schema validation doesn't check constraints, so only ItemRepositoryIT notices if it goes missing.
CREATE TABLE item (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    modified_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    CONSTRAINT item_name_key UNIQUE (name)
);
