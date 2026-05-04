-- V020: authoring schema — versions (drafts and published snapshots) + items + closure.
--
-- This is where reference data physically lives. Once a version reaches PUBLISHED,
-- application code must not UPDATE/DELETE its rows — enforced in the service layer
-- and asserted by integration tests.

CREATE SCHEMA IF NOT EXISTS authoring;

-- ── code_set_version ──────────────────────────────────────────────────────────

CREATE TABLE authoring.code_set_version (
    id                      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    codeset_id              uuid        NOT NULL,
    version                 text        NOT NULL,
    status                  text        NOT NULL
        CHECK (status IN ('DRAFT', 'IN_REVIEW', 'STEWARD_APPROVED',
                          'OWNER_APPROVED', 'PUBLISHED', 'DEPRECATED', 'REJECTED')),
    schema_version          integer     NOT NULL,
    release_channel         text        NOT NULL DEFAULT 'PROD'
        CHECK (release_channel IN ('PROD', 'SANDBOX')),

    -- Bitemporal envelope (SPEC §2.3). NULL effective_to == open-ended.
    effective_from          date,
    effective_to            date,
    system_from             timestamptz NOT NULL DEFAULT now(),
    system_to               timestamptz,

    created_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid        NOT NULL,
    approved_by             uuid,
    published_by            uuid,
    published_at            timestamptz,
    deprecated_at           timestamptz,

    -- SPEC §3.8 — content_hash is the SHA-256 of a deterministic JSON serialisation
    -- of all CodeItems in this version; approval_signature is HMAC over content_hash.
    content_hash            text
        CHECK (content_hash IS NULL OR content_hash ~ '^[a-f0-9]{64}$'),
    approval_signature      text
        CHECK (approval_signature IS NULL OR approval_signature ~ '^[a-f0-9]{64}$'),
    owner_was_provisional   boolean     NOT NULL DEFAULT false,

    item_count              integer     NOT NULL DEFAULT 0
        CHECK (item_count >= 0),

    UNIQUE (codeset_id, version),
    -- A published version must carry a hash + signature.
    CHECK (status <> 'PUBLISHED' OR (content_hash IS NOT NULL AND approval_signature IS NOT NULL))
);

CREATE INDEX code_set_version_codeset_status_ix
    ON authoring.code_set_version (codeset_id, status);
CREATE INDEX code_set_version_published_at_ix
    ON authoring.code_set_version (published_at DESC) WHERE status = 'PUBLISHED';
-- Bitemporal range queries on effective time use a GiST envelope.
CREATE INDEX code_set_version_effective_envelope_ix
    ON authoring.code_set_version
    USING gist (daterange(effective_from, effective_to, '[)'));
CREATE INDEX code_set_version_system_envelope_ix
    ON authoring.code_set_version
    USING gist (tstzrange(system_from, system_to, '[)'));

-- Steward approval list (1+ stewards may approve before owner).
CREATE TABLE authoring.code_set_version_reviewer (
    version_id      uuid        NOT NULL REFERENCES authoring.code_set_version (id) ON DELETE CASCADE,
    om_user_id      uuid        NOT NULL,
    reviewed_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (version_id, om_user_id)
);

-- ── code_item ─────────────────────────────────────────────────────────────────

CREATE TABLE authoring.code_item (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id          uuid        NOT NULL REFERENCES authoring.code_set_version (id) ON DELETE CASCADE,
    -- Composite key parts as a JSONB array of strings. ['KZ'] for single-keyed,
    -- ['RETAIL','BB','12M'] for matrix codesets. Indexed below.
    key_parts           jsonb       NOT NULL
        CHECK (jsonb_typeof(key_parts) = 'array' AND jsonb_array_length(key_parts) BETWEEN 1 AND 8),
    parent_key          jsonb,
    parent_ref          jsonb,
    label_ru            text,
    label_en            text,
    description_ru      text,
    description_en      text,
    attributes          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    order_index         integer     NOT NULL DEFAULT 0,
    status              text        NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    effective_from      date,
    effective_to        date,
    system_from         timestamptz NOT NULL DEFAULT now(),
    system_to           timestamptz,
    row_version         integer     NOT NULL DEFAULT 0,
    UNIQUE (version_id, key_parts)
);

-- Primary lookup index. SPEC §3.4 calls for (version_id, key_parts).
CREATE INDEX code_item_version_keyparts_ix
    ON authoring.code_item (version_id, (key_parts::text));

-- JSONB attribute search.
CREATE INDEX code_item_attributes_gin_ix
    ON authoring.code_item USING gin (attributes jsonb_path_ops);

-- Hierarchy lookup by parent_key.
CREATE INDEX code_item_parent_key_gin_ix
    ON authoring.code_item USING gin (parent_key jsonb_path_ops)
    WHERE parent_key IS NOT NULL;

-- Bitemporal envelopes.
CREATE INDEX code_item_effective_envelope_ix
    ON authoring.code_item
    USING gist (daterange(effective_from, effective_to, '[)'));
CREATE INDEX code_item_system_envelope_ix
    ON authoring.code_item
    USING gist (tstzrange(system_from, system_to, '[)'));

-- ru/en label fuzzy search (pg_trgm + tsvector live side-by-side; pick per query).
CREATE INDEX code_item_label_ru_trgm_ix
    ON authoring.code_item USING gin (label_ru gin_trgm_ops) WHERE label_ru IS NOT NULL;
CREATE INDEX code_item_label_en_trgm_ix
    ON authoring.code_item USING gin (label_en gin_trgm_ops) WHERE label_en IS NOT NULL;
CREATE INDEX code_item_label_ru_fts_ix
    ON authoring.code_item USING gin (to_tsvector('russian', coalesce(label_ru, '')));
CREATE INDEX code_item_label_en_fts_ix
    ON authoring.code_item USING gin (to_tsvector('english', coalesce(label_en, '')));

-- ── code_item_closure ─────────────────────────────────────────────────────────
-- SPEC §3.4 — closure table for fast subtree / ancestor traversal. Maintained by
-- the authoring module on every CodeItem write within a DRAFT version, and
-- materialised once at publish time.

CREATE TABLE authoring.code_item_closure (
    version_id          uuid        NOT NULL REFERENCES authoring.code_set_version (id) ON DELETE CASCADE,
    ancestor_key        jsonb       NOT NULL,
    descendant_key      jsonb       NOT NULL,
    depth               integer     NOT NULL CHECK (depth >= 0),
    PRIMARY KEY (version_id, ancestor_key, descendant_key)
);

CREATE INDEX code_item_closure_descendant_ix
    ON authoring.code_item_closure USING gin (descendant_key jsonb_path_ops);

-- ── grants ────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA authoring TO rdmmesh_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA authoring TO rdmmesh_app';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA authoring GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rdmmesh_app';
    END IF;
END$$;
