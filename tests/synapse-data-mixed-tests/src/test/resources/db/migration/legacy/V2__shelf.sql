-- The legacy store's shelves (StoreAotProcessingTest): ids from a sequence.
CREATE SEQUENCE shelf_seq START WITH 100 INCREMENT BY 1;
CREATE TABLE shelf (id BIGINT PRIMARY KEY, name VARCHAR(255));
