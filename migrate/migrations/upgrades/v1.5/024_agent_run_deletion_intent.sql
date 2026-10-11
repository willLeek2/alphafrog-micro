-- 用户删除 Run 时先持久封住整棵调用树；沙箱资源确认清理后才物理删除这些行。
ALTER TABLE alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS deletion_started_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_agent_run_deletion_started
    ON alphafrog_agent_run (deletion_started_at)
    WHERE deletion_started_at IS NOT NULL;
