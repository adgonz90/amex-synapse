-- The archive store's first migration (StoreAotProcessingTest): Note extends AuditedEntity.
CREATE TABLE note (
    id UUID PRIMARY KEY,
    version BIGINT,
    created_at TIMESTAMP(6) WITH TIME ZONE,
    created_by VARCHAR(255),
    modified_at TIMESTAMP(6) WITH TIME ZONE,
    modified_by VARCHAR(255),
    body VARCHAR(255)
);
