-- 已取消等待成员的外部任务停机记录。取消事务与停机发送分离，重启后按本表续做。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_wait_member_stop (
    id BIGSERIAL PRIMARY KEY,
    wait_member_id BIGINT NOT NULL UNIQUE
        REFERENCES alphafrog_agent_run_wait_member(id) ON DELETE CASCADE,
    run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    group_id BIGINT NOT NULL,
    operation_id VARCHAR(256) NOT NULL,
    task_id VARCHAR(256),
    request_fingerprint VARCHAR(80),
    cancel_request_id VARCHAR(128) NOT NULL UNIQUE,
    state VARCHAR(32) NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count INT NOT NULL DEFAULT 0,
    claimed_by VARCHAR(128),
    claim_token VARCHAR(128),
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(512),
    terminal_task_id VARCHAR(256),
    terminal_status VARCHAR(32),
    terminal_confirmed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT wait_member_stop_state_check
        CHECK (state IN ('PENDING', 'CLAIMED', 'BLOCKED_PROOF', 'CONFIRMED')),
    CONSTRAINT wait_member_stop_attempt_check CHECK (attempt_count >= 0),
    CONSTRAINT wait_member_stop_claim_check CHECK (
        (state = 'CLAIMED' AND claimed_by IS NOT NULL AND claim_token IS NOT NULL
         AND lease_until IS NOT NULL)
        OR (state <> 'CLAIMED' AND claimed_by IS NULL AND claim_token IS NULL
            AND lease_until IS NULL)),
    CONSTRAINT wait_member_stop_terminal_check CHECK (
        (state = 'CONFIRMED' AND terminal_task_id IS NOT NULL
         AND terminal_status IS NOT NULL AND terminal_confirmed_at IS NOT NULL)
        OR (state <> 'CONFIRMED' AND terminal_task_id IS NULL
            AND terminal_status IS NULL AND terminal_confirmed_at IS NULL)),
    CONSTRAINT wait_member_stop_terminal_status_check CHECK (
        terminal_status IS NULL OR terminal_status IN ('SUCCEEDED', 'FAILED', 'CANCELED'))
);

CREATE INDEX IF NOT EXISTS idx_wait_member_stop_due
    ON alphafrog_agent_run_wait_member_stop(next_attempt_at, id)
    WHERE state IN ('PENDING', 'CLAIMED');

CREATE INDEX IF NOT EXISTS idx_wait_member_stop_run_unconfirmed
    ON alphafrog_agent_run_wait_member_stop(run_id, id)
    WHERE state <> 'CONFIRMED';
