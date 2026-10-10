-- 审查决定独立于长工具锚点与等待成员证明：拒绝不会产生派发证明，
-- 同步完成后锚点也会清除。这里仅保存分数、当次配置与决定，不复制脚本。
CREATE TABLE IF NOT EXISTS alphafrog_agent_python_risk_decision (
    operation_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    decision TEXT NOT NULL CHECK (decision IN ('PASS', 'REJECT')),
    risk_score INTEGER CHECK (risk_score BETWEEN 0 AND 100),
    config_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (decision = 'PASS' OR risk_score IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS alphafrog_agent_python_risk_decision_run_idx
    ON alphafrog_agent_python_risk_decision(run_id);
