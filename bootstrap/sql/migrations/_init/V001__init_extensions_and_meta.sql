-- V001: cluster-wide extensions + the meta schema that owns flyway_schema_history.
--
-- Idempotent: every CREATE here uses IF NOT EXISTS so the migration is safe to re-run
-- against a database where extensions were created out-of-band by an operator.

CREATE SCHEMA IF NOT EXISTS rdmmesh_meta;

CREATE EXTENSION IF NOT EXISTS pgcrypto      WITH SCHEMA public;  -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS pg_trgm       WITH SCHEMA public;  -- typo-tolerant FTS
CREATE EXTENSION IF NOT EXISTS btree_gin     WITH SCHEMA public;  -- composite GIN indexes
CREATE EXTENSION IF NOT EXISTS btree_gist    WITH SCHEMA public;  -- bitemporal GiST envelopes

-- The application talks to the DB through the 'rdmmesh_app' role (created out-of-band
-- by the platform team / docker entrypoint). All schema-level GRANTs in subsequent
-- migrations target that role.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        RAISE NOTICE 'Role rdmmesh_app is missing — production deploys must create it before migrations run.';
    END IF;
END$$;
