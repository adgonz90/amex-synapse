-- The legacy store's shelves (JdbcStoresIT): ids from a sequence, and slots as a relation of the shelf aggregate.
CREATE SEQUENCE shelf_seq START WITH 100 INCREMENT BY 1;
CREATE TABLE shelf (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255)
);
CREATE TABLE slot (
    shelf BIGINT NOT NULL REFERENCES shelf (id),
    label VARCHAR(255)
);
