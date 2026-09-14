-- v1.1 / 002: agent workspace + simple memory v0
-- 三件事：
--   1) alphafrog_agent_run.status CHECK 约束补 PARTIAL（Java 枚举已有，DB 缺）；
--   2) 新建 alphafrog_agent_conversation_memory 表（Grace 字段一次建全，写入分阶段用）；
--   3) 3 个索引（用户主查询、来源回溯、跨用户 scope 联合）。
--
-- 设计约束：
--   - PARTIAL 落 DB 约束前，workspace DumpService 跳过 PARTIAL 终态；COMPLETED / FAILED / CANCELED 先跑通。
--   - memory 表字段一次建全，但 v0 worker 只填 9 个必填字段，剩余 6 字段（embedding_status / expires_at / verification_status / supersedes_memory_id / confidence 默认 1.0）允许 NULL / 默认。
--   - 索引不阻塞范围；3 个索引覆盖 90% 查询。

-- ==============
-- 1) PARTIAL status
-- ==============
ALTER TABLE IF EXISTS alphafrog_agent_run
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_status_check;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD CONSTRAINT alphafrog_agent_run_status_check
        CHECK (status IN (
            'RECEIVED',
            'PLANNING',
            'EXECUTING',
            'WAITING',
            'SUMMARIZING',
            'COMPLETED',
            'PARTIAL',
            'FAILED',
            'CANCELED',
            'EXPIRED'
        ));

-- ==============
-- 2) memory 表
-- ==============
CREATE TABLE IF NOT EXISTS alphafrog_agent_conversation_memory (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    -- conversation_scope = {tenant_id}_{user_id} 组合键，便于跨 run memory 演化
    conversation_scope VARCHAR(128) NOT NULL,
    -- memory_type: fact / preference / open_issue / correction
    memory_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    source_run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    source_message_seq_start INT NOT NULL,
    source_message_seq_end INT NOT NULL,
    -- confidence：v0 写入路径固定默认 1.0（避免被读为低置信）；后续有模型参与再启用
    confidence NUMERIC(4, 3) NOT NULL DEFAULT 1.0,
    -- verification_status: pending / auto_extracted / verified
    verification_status VARCHAR(32),
    supersedes_memory_id BIGINT REFERENCES alphafrog_agent_conversation_memory(id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ,
    -- status: active / superseded / deleted（删除不真删，保留审计）
    status VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'superseded', 'deleted')),
    -- embedding_status: pending / done / failed
    embedding_status VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ==============
-- 3) 索引
-- ==============
-- 用户主查询（按 scope 拉 active memory，按时间倒序）
CREATE INDEX IF NOT EXISTS idx_memory_user_scope
    ON alphafrog_agent_conversation_memory (user_id, conversation_scope, status, created_at DESC);

-- 来源回溯（按 source_run_id 找 memory）
CREATE INDEX IF NOT EXISTS idx_memory_source_run
    ON alphafrog_agent_conversation_memory (source_run_id);

-- 跨用户 + scope 联合（按 codex 建议，便于后续 tenant 级 memory 检索）
CREATE INDEX IF NOT EXISTS idx_memory_tenant_scope
    ON alphafrog_agent_conversation_memory (tenant_id, user_id, conversation_scope, status, created_at DESC);
