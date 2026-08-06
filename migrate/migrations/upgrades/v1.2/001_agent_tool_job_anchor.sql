-- v1.2 / 001: external tool job anchor + WAITING_TOOL_JOB status
-- 三件事：
--   1) alphafrog_agent_run.status CHECK 约束补 WAITING_TOOL_JOB；
--   2) 新增 tool_job_anchor_json JSONB 列（durable external tool job state）；
--   3) 索引：按 anchor 内 operationId 查询 + 活跃 tool job 扫描。
--
-- 设计约束：
--   - tool_job_anchor_json 是外部工具作业的 durable 事实来源，Redis cache/index 可从此列重建。
--   - WAITING_TOOL_JOB 表示 tool job 未结束但允许自动恢复；人工 WAITING 禁止自动恢复。
--   - 此迁移只加列和约束，不删不改现有数据。

-- ==============
-- 1) WAITING_TOOL_JOB status
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
            'WAITING_TOOL_JOB',
            'SUMMARIZING',
            'COMPLETED',
            'PARTIAL',
            'FAILED',
            'CANCELING',
            'CANCELED',
            'EXPIRED'
        ));

-- ==============
-- 2) tool_job_anchor_json 列
-- ==============
ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS tool_job_anchor_json JSONB NOT NULL DEFAULT '{}'::jsonb;

-- ==============
-- 3) 索引
-- ==============
-- 扫描存在活跃 tool job anchor 的 run（reconciler 周期补扫用）
CREATE INDEX IF NOT EXISTS idx_agent_run_tool_job_active
    ON alphafrog_agent_run (status)
    WHERE tool_job_anchor_json IS NOT NULL
      AND tool_job_anchor_json <> '{}'::jsonb;
