-- V070: audit schema — append-only journal.
--
-- Compliance-critical: SPEC §3.2 #6 mandates that the application role hold
-- INSERT-only privileges on this table. UPDATE / DELETE / TRUNCATE are revoked.
-- Retention: 7 years (SPEC §3.7).

CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE audit.audit_log (
    id              bigserial   PRIMARY KEY,
    -- Logical event id (matches in-process event bus). Used to dedupe replays.
    event_id        uuid        NOT NULL,
    event_type      text        NOT NULL,
    aggregate_type  text,
    aggregate_id    uuid,
    actor           uuid,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    payload         jsonb       NOT NULL,
    -- Optional cryptographic chain — V14 feature. NULL today, populated later.
    -- Storing the column from day one keeps backfill cheap.
    prev_hash       text        CHECK (prev_hash IS NULL OR prev_hash ~ '^[a-f0-9]{64}$'),
    entry_hash      text        CHECK (entry_hash IS NULL OR entry_hash ~ '^[a-f0-9]{64}$')
);

CREATE INDEX audit_log_aggregate_ix
    ON audit.audit_log (aggregate_type, aggregate_id, occurred_at DESC);
CREATE INDEX audit_log_actor_ix
    ON audit.audit_log (actor, occurred_at DESC);
CREATE INDEX audit_log_event_type_ix
    ON audit.audit_log (event_type, occurred_at DESC);
-- pg_trgm on payload->>'comment' for free-text search across audit comments.
CREATE INDEX audit_log_payload_gin_ix
    ON audit.audit_log USING gin (payload jsonb_path_ops);

-- ── append-only enforcement ───────────────────────────────────────────────────
-- A defence-in-depth trigger raises if anyone bypasses the role-level grant.
CREATE OR REPLACE FUNCTION audit.audit_log_no_modify() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit.audit_log is append-only (tg_op=%)', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update
    BEFORE UPDATE ON audit.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit.audit_log_no_modify();

CREATE TRIGGER audit_log_no_delete
    BEFORE DELETE ON audit.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit.audit_log_no_modify();

CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit.audit_log_no_modify();

-- ── grants ────────────────────────────────────────────────────────────────────
-- The application role gets INSERT and SELECT only. UPDATE / DELETE remain owner-only.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA audit TO rdmmesh_app';
        EXECUTE 'GRANT SELECT, INSERT ON audit.audit_log TO rdmmesh_app';
        EXECUTE 'GRANT USAGE ON SEQUENCE audit.audit_log_id_seq TO rdmmesh_app';
        EXECUTE 'REVOKE UPDATE, DELETE, TRUNCATE ON audit.audit_log FROM rdmmesh_app';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA audit GRANT SELECT, INSERT ON TABLES TO rdmmesh_app';
    END IF;
END$$;
