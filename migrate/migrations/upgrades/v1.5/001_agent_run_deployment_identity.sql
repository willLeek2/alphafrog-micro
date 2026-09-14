-- Run 在异步执行和服务重启后仍绑定创建时的部署与不可变构建代际。
-- 历史数据使用 legacy-stable，活动实例不得领取或恢复这一代际。

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS deployment_id VARCHAR(64) NOT NULL DEFAULT 'stable';

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS deployment_generation_id VARCHAR(68) NOT NULL DEFAULT 'legacy-stable';

-- 迁移前无法证明实际构建代际的历史 Run 不能再被活动实例领取。把仍在执行链上的
-- 历史记录一次性写成明确终态，避免它们永久显示为进行中。CANCELING 保留取消语义；
-- 其他状态进入 FAILED。这里只清除数据库锚点，不声称已经取消外部 Sandbox 任务。
UPDATE alphafrog_agent_run
SET status = CASE WHEN status = 'CANCELING' THEN 'CANCELED' ELSE 'FAILED' END,
    last_error = CASE
        WHEN status = 'CANCELING' THEN 'canceled_during_legacy_identity_migration'
        ELSE 'legacy_deployment_generation_inactive'
    END,
    tool_job_anchor_json = CAST('{}' AS jsonb),
    completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP),
    updated_at = CURRENT_TIMESTAMP
WHERE deployment_generation_id = 'legacy-stable'
  AND status IN ('RECEIVED', 'PLANNING', 'EXECUTING', 'SUMMARIZING',
                 'WAITING_TOOL_JOB', 'WAITING', 'CANCELING');

-- 默认值只用于给迁移前的记录补齐身份。迁移完成后移除默认值，使所有新写入都必须
-- 显式提供经过服务实例校验的部署身份，不能把漏传误记成历史代际。
ALTER TABLE IF EXISTS alphafrog_agent_run
    ALTER COLUMN deployment_id DROP DEFAULT;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ALTER COLUMN deployment_generation_id DROP DEFAULT;

ALTER TABLE IF EXISTS alphafrog_agent_run
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_deployment_id_check;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD CONSTRAINT alphafrog_agent_run_deployment_id_check
        CHECK (deployment_id ~ '^(stable|[a-z0-9](?:[a-z0-9-]{1,62}[a-z0-9]))$');

ALTER TABLE IF EXISTS alphafrog_agent_run
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_deployment_generation_id_check;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD CONSTRAINT alphafrog_agent_run_deployment_generation_id_check
        CHECK (deployment_generation_id = 'legacy-stable'
            OR deployment_generation_id ~ '^gen-[0-9a-f]{64}$');

CREATE OR REPLACE FUNCTION alphafrog_reject_agent_run_identity_change()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.deployment_id IS DISTINCT FROM OLD.deployment_id
        OR NEW.deployment_generation_id IS DISTINCT FROM OLD.deployment_generation_id THEN
        RAISE EXCEPTION 'Agent Run deployment identity is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_agent_run_deployment_identity_immutable ON alphafrog_agent_run;

CREATE TRIGGER trg_agent_run_deployment_identity_immutable
    BEFORE UPDATE OF deployment_id, deployment_generation_id
    ON alphafrog_agent_run
    FOR EACH ROW
    EXECUTE FUNCTION alphafrog_reject_agent_run_identity_change();

CREATE INDEX IF NOT EXISTS idx_agent_run_deployment_generation_status
    ON alphafrog_agent_run(deployment_id, deployment_generation_id, status, updated_at);
