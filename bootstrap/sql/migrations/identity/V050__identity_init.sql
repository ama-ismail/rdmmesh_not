-- V050: identity schema — Keycloak ↔ OpenMetadata user identity mapping.

CREATE SCHEMA IF NOT EXISTS identity;

-- ── rdm_user_mapping ──────────────────────────────────────────────────────────
-- Populated lazily on first login: when a JWT arrives, identity module looks up
-- by keycloak_sub; on miss it queries the OM API by username and inserts the row.

CREATE TABLE identity.rdm_user_mapping (
    om_user_id      uuid        PRIMARY KEY,
    keycloak_sub    uuid        NOT NULL UNIQUE,
    username        text        NOT NULL UNIQUE,
    email           text,
    display_name    text,
    first_seen_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX rdm_user_mapping_username_lower_ix
    ON identity.rdm_user_mapping (lower(username));

-- ── grants ────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA identity TO rdmmesh_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA identity TO rdmmesh_app';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA identity GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rdmmesh_app';
    END IF;
END$$;
