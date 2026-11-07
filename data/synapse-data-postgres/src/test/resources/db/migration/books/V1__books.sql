-- The books store's first migration. Each table matches its entity exactly, since the store validates its entities
-- against the schema when it starts.
-- Book extends AuditedEntity: UUID id, version, audit columns; the ISBN is unique.
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
-- Review extends LongAuditedEntity: its ids come from one sequence per entity, which Hibernate allocates 50 at a time.
CREATE TABLE review (
    id BIGINT PRIMARY KEY,
    version BIGINT,
    created_at TIMESTAMP(6) WITH TIME ZONE,
    created_by VARCHAR(255),
    modified_at TIMESTAMP(6) WITH TIME ZONE,
    modified_by VARCHAR(255),
    book_id UUID NOT NULL REFERENCES book (id),
    stars INTEGER NOT NULL
);
CREATE SEQUENCE review_seq START WITH 1 INCREMENT BY 50;
