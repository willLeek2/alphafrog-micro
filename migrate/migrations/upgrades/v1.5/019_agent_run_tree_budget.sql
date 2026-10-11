-- 根调用树的四类额度在 PostgreSQL 中串行预留。操作身份让重投不会重复扣额。
-- 本迁移仅提供存储合同；调用入口必须提供稳定身份并接入预留、确认与释放。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_tree_budget (
    root_run_id VARCHAR(64) PRIMARY KEY REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    llm_calls BIGINT NOT NULL DEFAULT 0 CHECK (llm_calls >= 0),
    tool_calls BIGINT NOT NULL DEFAULT 0 CHECK (tool_calls >= 0),
    active_nodes BIGINT NOT NULL DEFAULT 0 CHECK (active_nodes >= 0),
    external_waits BIGINT NOT NULL DEFAULT 0 CHECK (external_waits >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alphafrog_agent_run_tree_budget_operation (
    operation_id VARCHAR(512) PRIMARY KEY,
    root_run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run_tree_budget(root_run_id) ON DELETE CASCADE,
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('LLM_CALL', 'TOOL_CALL', 'ACTIVE_NODE', 'EXTERNAL_WAIT')),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RESERVED', 'CONFIRMED', 'RELEASED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_run_tree_budget_operation_root
    ON alphafrog_agent_run_tree_budget_operation(root_run_id, kind, state);
