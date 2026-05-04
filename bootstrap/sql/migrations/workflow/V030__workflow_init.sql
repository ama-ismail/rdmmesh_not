-- V030: workflow schema — append-only state-transition trail + open approval tasks.

CREATE SCHEMA IF NOT EXISTS workflow;

-- ── workflow_transition (append-only) ─────────────────────────────────────────
-- Every state machine step writes one row here. Used by workflow.history endpoint
-- and by the audit module subscriber.

CREATE TABLE workflow.workflow_transition (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id      uuid        NOT NULL,
    codeset_id      uuid        NOT NULL,
    domain_id       uuid        NOT NULL,
    from_status     text
        CHECK (from_status IS NULL OR from_status IN
            ('DRAFT', 'IN_REVIEW', 'STEWARD_APPROVED', 'OWNER_APPROVED',
             'PUBLISHED', 'DEPRECATED', 'REJECTED')),
    to_status       text        NOT NULL
        CHECK (to_status IN
            ('DRAFT', 'IN_REVIEW', 'STEWARD_APPROVED', 'OWNER_APPROVED',
             'PUBLISHED', 'DEPRECATED', 'REJECTED')),
    action          text        NOT NULL
        CHECK (action IN ('submit', 'steward_approve', 'steward_reject',
                          'owner_approve', 'owner_reject', 'publish', 'deprecate')),
    actor           uuid        NOT NULL,
    comment         text,
    occurred_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX workflow_transition_version_ix
    ON workflow.workflow_transition (version_id, occurred_at DESC);
CREATE INDEX workflow_transition_actor_ix
    ON workflow.workflow_transition (actor, occurred_at DESC);

-- ── approval_task ─────────────────────────────────────────────────────────────
-- Materialised view of "what's waiting for me?". Cleaned on transition.
-- This keeps the My Tasks endpoint a single index lookup.

CREATE TABLE workflow.approval_task (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id      uuid        NOT NULL,
    codeset_id      uuid        NOT NULL,
    domain_id       uuid        NOT NULL,
    required_role   text        NOT NULL
        CHECK (required_role IN ('STEWARD', 'OWNER')),
    -- Resolved candidate users from rdm_asset_ownership at task-creation time.
    -- Permission cache invalidation re-materialises the row.
    candidate_users uuid[]      NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    closed_at       timestamptz,
    closed_by       uuid,
    UNIQUE (version_id, required_role)
);

CREATE INDEX approval_task_open_ix
    ON workflow.approval_task USING gin (candidate_users)
    WHERE closed_at IS NULL;

-- ── grants ────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA workflow TO rdmmesh_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA workflow TO rdmmesh_app';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA workflow GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rdmmesh_app';
    END IF;
END$$;
