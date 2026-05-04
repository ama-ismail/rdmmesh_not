-- V010: catalog schema — domain mirrors, CodeSet metadata, CodeSetSchema (JSON Schema docs).
--
-- Owner module: rdmmesh-catalog. No other module writes here (enforced by ArchUnit + by
-- granting only catalog the appropriate role at runtime).

CREATE SCHEMA IF NOT EXISTS catalog;

-- ── domain ────────────────────────────────────────────────────────────────────
-- Mirrored from OpenMetadata (om_domain_id is the system of record for identity).
-- The MVP read path joins by id; webhook ownership upserts by om_domain_id.

CREATE TABLE catalog.domain (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    om_domain_id    uuid        NOT NULL UNIQUE,
    name            text        NOT NULL UNIQUE
        CHECK (name ~ '^[a-z][a-z0-9_]{0,63}$'),
    display_name    text,
    description     text,
    label_ru        text,
    label_en        text,
    tags            text[]      NOT NULL DEFAULT '{}',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX domain_tags_gin_ix ON catalog.domain USING gin (tags);

-- ── code_set ──────────────────────────────────────────────────────────────────

CREATE TABLE catalog.code_set (
    id                          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id                   uuid        NOT NULL REFERENCES catalog.domain (id) ON DELETE RESTRICT,
    name                        text        NOT NULL
        CHECK (name ~ '^[a-z][a-z0-9_]{0,63}$'),
    display_name                text,
    description                 text,
    label_ru                    text,
    label_en                    text,
    tags                        text[]      NOT NULL DEFAULT '{}',
    -- KeySpec is a small structured doc. Stored as JSONB so the spec can evolve
    -- without DDL while still being query-shaped (jsonb_path_ops GIN if it ever matters).
    key_spec                    jsonb       NOT NULL,
    hierarchy_mode              text        NOT NULL DEFAULT 'NONE'
        CHECK (hierarchy_mode IN ('NONE', 'INTRA_CODESET', 'CROSS_CODESET')),
    release_channels            text[]      NOT NULL DEFAULT ARRAY['PROD'],
    schema_version              integer     NOT NULL DEFAULT 1,
    current_published_version   text,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    created_by                  uuid        NOT NULL,
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    -- Soft delete, see SPEC §5.3 (ingestion may otherwise miss CodeSet removals).
    deleted_at                  timestamptz,
    UNIQUE (domain_id, name)
);

CREATE INDEX code_set_domain_id_ix          ON catalog.code_set (domain_id);
CREATE INDEX code_set_tags_gin_ix           ON catalog.code_set USING gin (tags);
CREATE INDEX code_set_label_ru_trgm_ix      ON catalog.code_set USING gin (label_ru gin_trgm_ops) WHERE label_ru IS NOT NULL;
CREATE INDEX code_set_label_en_trgm_ix      ON catalog.code_set USING gin (label_en gin_trgm_ops) WHERE label_en IS NOT NULL;
CREATE INDEX code_set_description_fts_ix    ON catalog.code_set
    USING gin (to_tsvector('simple', coalesce(description, '')));

-- ── code_set_schema ───────────────────────────────────────────────────────────
-- Each CodeSet has a monotonically growing schema revision. The active revision is
-- catalog.code_set.schema_version. History is kept for audit + reproducibility.

CREATE TABLE catalog.code_set_schema (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    codeset_id      uuid        NOT NULL REFERENCES catalog.code_set (id) ON DELETE CASCADE,
    version         integer     NOT NULL,
    json_schema     jsonb       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid        NOT NULL,
    UNIQUE (codeset_id, version)
);

-- ── grants ────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA catalog TO rdmmesh_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA catalog TO rdmmesh_app';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA catalog GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rdmmesh_app';
    END IF;
END$$;
