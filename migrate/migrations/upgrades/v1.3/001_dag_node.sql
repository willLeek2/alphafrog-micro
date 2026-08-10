-- D08: DAG 并行计划持久化挂起/恢复 — 子表与 frontier 列
-- 新增 alphafrog_agent_run_dag_node 子表、dag_frontier_json 列与 usage_record 表

-- 1. alphafrog_agent_run 增加 dag_frontier_json（DAG 代际协调元数据）
ALTER TABLE alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS dag_frontier_json JSONB NOT NULL DEFAULT '{}'::jsonb;

-- 2. DAG 节点子表：每个 (run_id, generation, node_id) 唯一
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_dag_node (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    generation INT NOT NULL,
    node_id VARCHAR(256) NOT NULL,
    tool_job_anchor_json JSONB NOT NULL DEFAULT '{"nodePhase":"DRAFT","nodeVersion":0}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, generation, node_id)
);

-- 3. Usage 记录沉底表：按 operation_id 唯一，防重复计费
CREATE TABLE IF NOT EXISTS alphafrog_agent_usage_record (
    id BIGSERIAL PRIMARY KEY,
    operation_id VARCHAR(160) NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 4. 索引
CREATE INDEX IF NOT EXISTS idx_dag_node_run_gen
    ON alphafrog_agent_run_dag_node(run_id, generation);

CREATE INDEX IF NOT EXISTS idx_dag_node_phase
    ON alphafrog_agent_run_dag_node((tool_job_anchor_json #>> '{nodePhase}'));

CREATE INDEX IF NOT EXISTS idx_dag_node_disposition
    ON alphafrog_agent_run_dag_node((tool_job_anchor_json #>> '{disposition}'))
    WHERE tool_job_anchor_json #>> '{disposition}' IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_usage_record_operation
    ON alphafrog_agent_usage_record(operation_id);

CREATE INDEX IF NOT EXISTS idx_agent_run_dag_frontier_phase
    ON alphafrog_agent_run((dag_frontier_json #>> '{phase}'))
    WHERE dag_frontier_json #>> '{phase}' IS NOT NULL;
