-- :name hug:create-features-table :!
CREATE TABLE IF NOT EXISTS :i:features-table-name (
    key        TEXT        NOT NULL
  , metadata   JSONB DEFAULT '{}'::jsonb
  , created_at TIMESTAMPTZ NOT NULL DEFAULT now()
  , updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- :name hug:create-features-index :!
CREATE UNIQUE INDEX IF NOT EXISTS :i:features-index-name
  ON :i:features-table-name (key ASC);

-- :name hug:drop-features-index :!
DROP INDEX IF EXISTS :i:features-index-name;

-- :name hug:drop-features-table :!
DROP TABLE IF EXISTS :i:features-table-name;

-- :name hug:create-gates-table :!
CREATE TABLE IF NOT EXISTS :i:gates-table-name (
    feature_key TEXT        NOT NULL
  , key         TEXT        NOT NULL
  , value       TEXT        NOT NULL
  , created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
  , updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- :name hug:create-gates-index :!
CREATE UNIQUE INDEX IF NOT EXISTS :i:gates-index-name
  ON :i:gates-table-name (feature_key ASC, key ASC, value ASC);

-- :name hug:drop-gates-index :!
DROP INDEX IF EXISTS :i:gates-index-name;

-- :name hug:drop-gates-table :!
DROP TABLE IF EXISTS :i:gates-table-name;

-- :name hug:clean-features :!
DELETE FROM :i:features-table-name;

-- :name hug:clean-gates :!
DELETE FROM :i:gates-table-name;

-- :name hug:upsert-feature :! :n
INSERT INTO :i:features-table-name
  (key, metadata)
  VALUES (:key, :metadata::jsonb)
  ON CONFLICT (key) DO NOTHING; -- make idempotent

-- :name hug:delete-feature :! :n
DELETE FROM :i:features-table-name WHERE key = :key;

-- :name hug:select-features :?
SELECT key FROM :i:features-table-name;
