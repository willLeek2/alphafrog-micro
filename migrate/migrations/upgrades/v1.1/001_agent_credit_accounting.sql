-- v1.1 agent credit accounting

ALTER TABLE alphafrog_user
    ALTER COLUMN credit TYPE NUMERIC(20, 6) USING credit::numeric;

ALTER TABLE alphafrog_agent_credit_ledger
    ADD COLUMN IF NOT EXISTS reason TEXT NOT NULL DEFAULT '';

ALTER TABLE alphafrog_agent_credit_ledger
    ALTER COLUMN delta TYPE NUMERIC(20, 6) USING delta::numeric,
    ALTER COLUMN balance_before TYPE NUMERIC(20, 6) USING balance_before::numeric,
    ALTER COLUMN balance_after TYPE NUMERIC(20, 6) USING balance_after::numeric;

DROP INDEX IF EXISTS uniq_ledger_biz_source;
CREATE UNIQUE INDEX IF NOT EXISTS uniq_credit_ledger_biz_source_idem
    ON alphafrog_agent_credit_ledger(biz_type, source_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_credit_ledger_user_biz_created
    ON alphafrog_agent_credit_ledger(user_id, biz_type, created_at DESC);

CREATE TABLE IF NOT EXISTS alphafrog_agent_credit_recharge (
    id BIGSERIAL PRIMARY KEY,
    recharge_id VARCHAR(64) NOT NULL UNIQUE,
    ledger_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    username VARCHAR(128),
    operator_id VARCHAR(64) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    original_amount NUMERIC(20, 6) NOT NULL,
    exchange_rate_to_usd NUMERIC(20, 8) NOT NULL,
    credit_amount NUMERIC(20, 6) NOT NULL,
    reason TEXT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_credit_recharge_operator_idem
    ON alphafrog_agent_credit_recharge(operator_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_credit_recharge_user_created
    ON alphafrog_agent_credit_recharge(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS alphafrog_agent_run_credit_summary (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64) NOT NULL,
    total_credit_consumed NUMERIC(20, 6) NOT NULL DEFAULT 0,
    immediate_credit_consumed NUMERIC(20, 6) NOT NULL DEFAULT 0,
    delayed_credit_consumed NUMERIC(20, 6) NOT NULL DEFAULT 0,
    currency VARCHAR(16) NOT NULL DEFAULT 'USD',
    settlement_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(128),
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_settlement_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_run_credit_summary_user_created
    ON alphafrog_agent_run_credit_summary(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_run_credit_summary_status
    ON alphafrog_agent_run_credit_summary(settlement_status, updated_at DESC);

CREATE TABLE IF NOT EXISTS alphafrog_agent_run_llm_call_credit (
    id BIGSERIAL PRIMARY KEY,
    record_id VARCHAR(64) NOT NULL UNIQUE,
    run_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    llm_call_id VARCHAR(128) NOT NULL,
    endpoint_name VARCHAR(64),
    model_name VARCHAR(255),
    cost_source VARCHAR(64) NOT NULL,
    currency VARCHAR(16) NOT NULL DEFAULT 'USD',
    cost_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    credit_delta NUMERIC(20, 6) NOT NULL DEFAULT 0,
    settlement_status VARCHAR(32) NOT NULL,
    settlement_attempt INT NOT NULL DEFAULT 1,
    reason TEXT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '30 days')
);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_llm_call_credit_idem
    ON alphafrog_agent_run_llm_call_credit(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_llm_call_credit_run_created
    ON alphafrog_agent_run_llm_call_credit(run_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_llm_call_credit_expires
    ON alphafrog_agent_run_llm_call_credit(expires_at);
