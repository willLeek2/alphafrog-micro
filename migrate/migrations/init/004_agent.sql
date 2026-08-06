-- Agent 服务相关表（从零初始化）

CREATE TABLE IF NOT EXISTS alphafrog_agent_run (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'PLANNING', 'EXECUTING', 'WAITING', 'SUMMARIZING', 'COMPLETED', 'FAILED', 'CANCELED', 'EXPIRED')),
    current_step INT NOT NULL DEFAULT 0,
    max_steps INT NOT NULL DEFAULT 12,
    plan_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error TEXT,
    ttl_expires_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS alphafrog_agent_run_event (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    seq INT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, seq)
);

CREATE TABLE IF NOT EXISTS alphafrog_agent_credit_application (
    id BIGSERIAL PRIMARY KEY,
    application_id VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64) NOT NULL,
    amount INTEGER NOT NULL,
    reason TEXT,
    contact VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    processed_by VARCHAR(64),
    process_reason TEXT,
    version INT NOT NULL DEFAULT 0,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS alphafrog_agent_credit_ledger (
    id BIGSERIAL PRIMARY KEY,
    ledger_id VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64) NOT NULL,
    biz_type VARCHAR(32) NOT NULL,
    delta NUMERIC(20, 6) NOT NULL,
    balance_before NUMERIC(20, 6) NOT NULL,
    balance_after NUMERIC(20, 6) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64),
    idempotency_key VARCHAR(128),
    reason TEXT NOT NULL DEFAULT '',
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alphafrog_agent_credit_recharge (
    id BIGSERIAL PRIMARY KEY,
    recharge_id VARCHAR(64) NOT NULL UNIQUE,
    ledger_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    username VARCHAR(128),
    operator_id VARCHAR(64) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    original_amount NUMERIC(20, 6) NOT NULL,
    exchange_rate_to_usd NUMERIC(20, 8) NOT NULL,
    credit_amount NUMERIC(20, 6) NOT NULL,
    reason TEXT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alphafrog_agent_run_credit_summary (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64) NOT NULL,
    total_credit_consumed NUMERIC(20, 6) NOT NULL DEFAULT 0,
    immediate_credit_consumed NUMERIC(20, 6) NOT NULL DEFAULT 0,
    delayed_credit_consumed NUMERIC(20, 6) NOT NULL DEFAULT 0,
    currency VARCHAR(8) NOT NULL DEFAULT 'USD',
    settlement_status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128),
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_settlement_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alphafrog_agent_run_llm_call_credit (
    id BIGSERIAL PRIMARY KEY,
    record_id VARCHAR(64) NOT NULL UNIQUE,
    run_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    llm_call_id VARCHAR(128) NOT NULL,
    endpoint_name VARCHAR(64),
    model_name VARCHAR(255),
    cost_source VARCHAR(32) NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'USD',
    cost_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    credit_delta NUMERIC(20, 6) NOT NULL DEFAULT 0,
    settlement_status VARCHAR(32) NOT NULL,
    settlement_attempt INT NOT NULL DEFAULT 1,
    reason TEXT NOT NULL DEFAULT '',
    idempotency_key VARCHAR(128) NOT NULL,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '30 days')
);

CREATE TABLE IF NOT EXISTS alphafrog_admin_audit_log (
    id BIGSERIAL PRIMARY KEY,
    audit_id VARCHAR(64) NOT NULL UNIQUE,
    operator_id VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    before_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    after_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    reason TEXT,
    idempotency_key VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alphafrog_admin_idempotency (
    id BIGSERIAL PRIMARY KEY,
    operator_id VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    response_json TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 索引

CREATE INDEX IF NOT EXISTS idx_agent_run_user ON alphafrog_agent_run(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_status ON alphafrog_agent_run(status);
CREATE INDEX IF NOT EXISTS idx_agent_run_updated ON alphafrog_agent_run(updated_at);
CREATE INDEX IF NOT EXISTS idx_agent_run_user_started_desc ON alphafrog_agent_run(user_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_run_event_run ON alphafrog_agent_run_event(run_id);

CREATE INDEX IF NOT EXISTS idx_agent_credit_apply_user ON alphafrog_agent_credit_application(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_credit_apply_created ON alphafrog_agent_credit_application(created_at);
CREATE INDEX IF NOT EXISTS idx_credit_app_status_created ON alphafrog_agent_credit_application(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_credit_app_user_status ON alphafrog_agent_credit_application(user_id, status);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_ledger_biz_source_idem ON alphafrog_agent_credit_ledger(biz_type, source_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_credit_ledger_user_created ON alphafrog_agent_credit_ledger(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_credit_ledger_biz_created ON alphafrog_agent_credit_ledger(biz_type, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_credit_recharge_operator_idem ON alphafrog_agent_credit_recharge(operator_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_credit_recharge_user_created ON alphafrog_agent_credit_recharge(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_run_credit_summary_user_created ON alphafrog_agent_run_credit_summary(user_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_run_llm_call_credit_idem ON alphafrog_agent_run_llm_call_credit(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_run_llm_call_credit_run_created ON alphafrog_agent_run_llm_call_credit(run_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_run_llm_call_credit_expires ON alphafrog_agent_run_llm_call_credit(expires_at);

CREATE INDEX IF NOT EXISTS idx_audit_target ON alphafrog_admin_audit_log(target_type, target_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_operator_created ON alphafrog_admin_audit_log(operator_id, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_admin_idem ON alphafrog_admin_idempotency(operator_id, action, target_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_admin_idem_updated_at ON alphafrog_admin_idempotency(updated_at DESC);
