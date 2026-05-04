-- V060: ownership schema — asset-level role assignments mirrored from OpenMetadata.
--
-- (asset_id, role, om_user_id) is unique. Provisional rows (is_provisional=true)
-- are RDM-side bootstrap entries that get superseded once the OM webhook arrives.

CREATE SCHEMA IF NOT EXISTS ownership;

CREATE TABLE ownership.rdm_asset_ownership (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id            uuid        NOT NULL,
    asset_type          text        NOT NULL CHECK (asset_type IN ('DOMAIN', 'CODESET')),
    om_user_id          uuid        NOT NULL,
    role                text        NOT NULL
        CHECK (role IN ('OWNER', 'STEWARD', 'EXPERT', 'APPROVER')),
    is_provisional      boolean     NOT NULL DEFAULT false,
    assigned_at         timestamptz NOT NULL DEFAULT now(),
    assigned_by         uuid,
    -- OM ChangeEvent id — used for idempotent webhook handling.
    source_event_id     text,
    UNIQUE (asset_id, asset_type, om_user_id, role)
);

CREATE INDEX rdm_asset_ownership_asset_ix
    ON ownership.rdm_asset_ownership (asset_id, asset_type);
CREATE INDEX rdm_asset_ownership_user_ix
    ON ownership.rdm_asset_ownership (om_user_id);
CREATE INDEX rdm_asset_ownership_provisional_ix
    ON ownership.rdm_asset_ownership (assigned_at) WHERE is_provisional = true;

-- ── grants ────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA ownership TO rdmmesh_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ownership TO rdmmesh_app';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA ownership GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rdmmesh_app';
    END IF;
END$$;
