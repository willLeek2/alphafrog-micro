-- v1.4 / 001: workflow-level checkpoint and bounded service-restart attempts.
--
-- execution_checkpoint_json deliberately does not reuse tool_job_anchor_json:
-- the former records a frozen Plan's coarse Todo boundary, while the latter belongs
-- to the legacy durable external-tool handoff protocol.

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS execution_checkpoint_json JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS restart_attempt INT NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS alphafrog_agent_run
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_restart_attempt_check;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD CONSTRAINT alphafrog_agent_run_restart_attempt_check
        CHECK (restart_attempt >= 0);

CREATE INDEX IF NOT EXISTS idx_agent_run_startup_recovery
    ON alphafrog_agent_run (status, started_at, id)
    WHERE status IN (
        'RECEIVED',
        'PLANNING',
        'EXECUTING',
        'SUMMARIZING',
        'WAITING_TOOL_JOB',
        'CANCELING'
    );
