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
  -- update metadata on an upsert
  ON CONFLICT (key)
  DO UPDATE SET metadata = EXCLUDED.metadata;

-- :name hug:delete-feature :! :n
DELETE FROM :i:features-table-name WHERE key = :key;

-- :name hug:select-features :?
SELECT key, metadata FROM :i:features-table-name;

-- :name hug:insert-gate :! :n
INSERT INTO :i:gates-table-name
  (feature_key, key, value)
  VALUES (:fkey, :gate-type, :gate-value)
  -- make idempotent by ignoring insert, we have all possible data already
  ON CONFLICT (feature_key, key, value)
  DO NOTHING;

-- :name hug:delete-gates-for-fkey :! :n
DELETE FROM :i:gates-table-name WHERE feature_key = :fkey;

-- :name hug:delete-gate :! :n
-- :doc Delete one specific gate
DELETE FROM :i:gates-table-name WHERE
      feature_key = :fkey
  AND key         = :gate-type
  AND value       = :gate-value;

-- :name hug:select-gates-for-fkey :?
SELECT key, value from :i:gates-table-name WHERE feature_key = :fkey;
