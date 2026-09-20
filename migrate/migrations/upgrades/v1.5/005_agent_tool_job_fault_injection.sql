-- 长工具恢复验收的一次性故障记录。
--
-- 表只保存受限控制面预先写入的精确 Run 场景。Agent 进程只会按自身泳道、部署版本、
-- run_id 与固定检查点原子消费，不接受业务请求携带故障参数。
CREATE TABLE IF NOT EXISTS alphafrog_agent_tool_job_fault_injection (
    id BIGSERIAL PRIMARY KEY,
    lane_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    scenario_id VARCHAR(128) NOT NULL,
    checkpoint VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    deployment_version VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMPTZ NOT NULL,
    enabled_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    triggered_at TIMESTAMPTZ,
    restart_observed_at TIMESTAMPTZ,
    trigger_instance VARCHAR(256),
    CONSTRAINT alphafrog_agent_tool_job_fault_identity_key
        UNIQUE (lane_id, deployment_version, run_id, scenario_id, checkpoint),
    CONSTRAINT alphafrog_agent_tool_job_fault_checkpoint_check
        CHECK (checkpoint IN ('BEFORE_SANDBOX_SUBMIT', 'AFTER_SANDBOX_ACCEPTED',
                              'AFTER_RESUME_COMMITTED', 'AFTER_MODEL_COMPLETED')),
    CONSTRAINT alphafrog_agent_tool_job_fault_action_check
        CHECK (action IN ('THREAD_INTERRUPT', 'PROCESS_HALT')),
    CONSTRAINT alphafrog_agent_tool_job_fault_consumption_check
        CHECK ((consumed_at IS NULL AND triggered_at IS NULL)
            OR (consumed_at IS NOT NULL AND triggered_at IS NOT NULL)),
    CONSTRAINT alphafrog_agent_tool_job_fault_enablement_check
        CHECK ((enabled = FALSE AND enabled_at IS NULL)
            OR (enabled = TRUE AND enabled_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_agent_tool_job_fault_pending
    ON alphafrog_agent_tool_job_fault_injection(
        lane_id, deployment_version, run_id, checkpoint, expires_at)
    WHERE enabled = TRUE AND consumed_at IS NULL;

COMMENT ON TABLE alphafrog_agent_tool_job_fault_injection IS
    'Beta 长工具恢复验收的受限一次性故障点；先原子消费，再中断线程或进程。';
