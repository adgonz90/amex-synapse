-- The books store's first migration (StoreMigrationsIT).
CREATE TABLE book (id INT AUTO_INCREMENT PRIMARY KEY, isbn VARCHAR(20) NOT NULL UNIQUE);
