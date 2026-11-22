-- The audited store's first migration (JpaAuditingIT). Each table matches its entity exactly, since the store
-- validates its entities against the schema when it starts.
-- Book extends AuditedEntity: UUID id, version, audit columns.
CREATE TABLE book (
    id UUID PRIMARY KEY,
    version BIGINT,
    created_at TIMESTAMP(6) WITH TIME ZONE,
    created_by VARCHAR(255),
    modified_at TIMESTAMP(6) WITH TIME ZONE,
    modified_by VARCHAR(255),
    title VARCHAR(255),
    isbn VARCHAR(255) UNIQUE
);
-- LedgerEntry extends LongAuditedEntity: its ids come from one sequence per entity, which Hibernate allocates 50 at
-- a time.
CREATE TABLE ledger_entry (
    id BIGINT PRIMARY KEY,
    version BIGINT,
    created_at TIMESTAMP(6) WITH TIME ZONE,
    created_by VARCHAR(255),
    modified_at TIMESTAMP(6) WITH TIME ZONE,
    modified_by VARCHAR(255),
    amount BIGINT NOT NULL
);
CREATE SEQUENCE ledger_entry_seq START WITH 1 INCREMENT BY 50;
-- LegacyNote implements Auditable on its own columns.
CREATE TABLE legacy_note (
    id BIGINT PRIMARY KEY,
    body VARCHAR(255),
    note_created TIMESTAMP(6) WITH TIME ZONE,
    note_created_by VARCHAR(255),
    note_modified TIMESTAMP(6) WITH TIME ZONE,
    note_modified_by VARCHAR(255)
);
