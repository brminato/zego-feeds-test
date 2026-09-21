ALTER TABLE app_user ADD COLUMN role VARCHAR(10) NOT NULL DEFAULT 'READER'
    CHECK (role IN ('READER', 'WRITER'));
-- Preserve publishing permissions for existing authors.
UPDATE app_user SET role='WRITER' WHERE id IN (SELECT DISTINCT author_id FROM article);
