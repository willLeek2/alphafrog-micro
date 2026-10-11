-- 自动清理先封住 Run 的恢复与新派发；删盘确认后留下永久过期事实。
ALTER TABLE alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS workspace_cleanup_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS workspace_expired_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_agent_run_workspace_cleanup_started
    ON alphafrog_agent_run (workspace_cleanup_started_at)
    WHERE workspace_cleanup_started_at IS NOT NULL AND workspace_expired_at IS NULL;
