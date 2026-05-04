-- V040: publishing schema — outbound webhook subscriptions and the durable outbox
-- used to deliver them with retry/backoff (transactional outbox pattern).

CREATE SCHEMA IF NOT EXISTS publishing;

-- ── webhook_subscription ──────────────────────────────────────────────────────

CREATE TABLE publishing.webhook_subscription (
    id                      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    url                     text        NOT NULL,
    -- Pointer to a Vault/SOPS key. The HMAC secret value never lives in this DB.
    secret_id               text        NOT NULL,
    filter                  jsonb       NOT NULL DEFAULT '{}'::jsonb,
    active                  boolean     NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid        NOT NULL,
    last_delivery_at        timestamptz,
    last_delivery_status    text
        CHECK (last_delivery_status IS NULL OR last_delivery_status IN ('OK', 'FAILED', 'RETRYING'))
);

-- ── webhook_outbox (transactional outbox) ─────────────────────────────────────
-- Insert into this table happens in the same transaction as the publish; a
-- background worker drains it with at-least-once + idempotent semantics.

CREATE TABLE publishing.webhook_outbox (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id     uuid        NOT NULL REFERENCES publishing.webhook_subscription (id) ON DELETE CASCADE,
    event_id            uuid        NOT NULL,
    event_type          text        NOT NULL,
    payload             jsonb       NOT NULL,
    -- Detached signature of the payload (HMAC-SHA256 with subscription secret).
    -- Stored so that re-delivery does not have to reach Vault again.
    signature           text        NOT NULL CHECK (signature ~ '^[a-f0-9]{64}$'),
    attempts            integer     NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz NOT NULL DEFAULT now(),
    delivered_at        timestamptz,
    last_error          text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (subscription_id, event_id)
);

CREATE INDEX webhook_outbox_due_ix
    ON publishing.webhook_outbox (next_attempt_at)
    WHERE delivered_at IS NULL;

-- ── grants ────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA publishing TO rdmmesh_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA publishing TO rdmmesh_app';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA publishing GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rdmmesh_app';
    END IF;
END$$;
