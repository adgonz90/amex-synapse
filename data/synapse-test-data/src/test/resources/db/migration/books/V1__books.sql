-- The books store's first migration (SynapseDataTestIT). The table matches Book exactly, since the store validates
-- its entities against the schema when it starts. Book extends AuditedEntity: UUID id, version, audit columns.
CREATE TABLE book (
    id UUID PRIMARY KEY,
    version BIGINT,
    created_at TIMESTAMP(6) WITH TIME ZONE,
    created_by VARCHAR(255),
    modified_at TIMESTAMP(6) WITH TIME ZONE,
    modified_by VARCHAR(255),
    title VARCHAR(255)
);
