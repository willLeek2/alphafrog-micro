-- 子执行的创建意图与实际受理分开保存。父等待组、意图、投递记录及树级预留
-- 由调用方在同一数据库事务中提交；子 Run 主记录在后续投递事务中创建。

CREATE TABLE IF NOT EXISTS alphafrog_agent_run_tree_capacity (
    root_run_id VARCHAR(64) PRIMARY KEY REFERENCES alphafrog_agent_run(id),
    active_child_count INT NOT NULL DEFAULT 0 CHECK (active_child_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alphafrog_agent_run_child_intent (
    id BIGSERIAL PRIMARY KEY,
    root_run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    parent_run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id),
    child_run_id VARCHAR(64) NOT NULL UNIQUE,
    parent_wait_group_id BIGINT NOT NULL,
    parent_member_identity VARCHAR(256) NOT NULL,
    parent_node_id VARCHAR(256) NOT NULL,
    tool_call_id VARCHAR(256) NOT NULL,
    plan_generation INT NOT NULL CHECK (plan_generation >= 0),
    node_attempt INT NOT NULL CHECK (node_attempt >= 0),
    operation_id VARCHAR(256) NOT NULL UNIQUE,
    goal TEXT NOT NULL CHECK (length(btrim(goal)) > 0),
    context_text TEXT NOT NULL DEFAULT '',
    child_model_name VARCHAR(256),
    child_endpoint_name VARCHAR(256),
    child_max_steps INT NOT NULL CHECK (child_max_steps BETWEEN 1 AND 12),
    parent_scheduler_version VARCHAR(32) NOT NULL CHECK (parent_scheduler_version = 'DUAL_POOL_V2'),
    parent_deployment_id VARCHAR(64),
    parent_deployment_generation_id VARCHAR(68),
    parent_config_snapshot_digest VARCHAR(128) NOT NULL,
    parent_control_version BIGINT NOT NULL CHECK (parent_control_version >= 0),
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'ACCEPTED', 'CANCEL_REQUESTED', 'CANCELED_BEFORE_ACCEPT', 'TERMINAL')),
    accepted_at TIMESTAMPTZ,
    cancel_requested_at TIMESTAMPTZ,
    child_terminal_at TIMESTAMPTZ,
    physical_stopped_at TIMESTAMPTZ,
    capacity_released_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agent_run_child_intent_call_key
        UNIQUE (parent_wait_group_id, parent_member_identity),
    CONSTRAINT agent_run_child_intent_group_fk
        FOREIGN KEY (parent_wait_group_id, parent_run_id)
        REFERENCES alphafrog_agent_run_wait_group(id, run_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT agent_run_child_intent_member_fk
        FOREIGN KEY (parent_wait_group_id, parent_member_identity)
        REFERENCES alphafrog_agent_run_wait_member(group_id, member_identity)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT agent_run_child_intent_accepted_check
        CHECK ((state IN ('ACCEPTED', 'CANCEL_REQUESTED', 'TERMINAL')) = (accepted_at IS NOT NULL)),
    CONSTRAINT agent_run_child_intent_released_check
        CHECK (capacity_released_at IS NULL
               OR (child_terminal_at IS NOT NULL AND physical_stopped_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_agent_run_child_intent_root_active
    ON alphafrog_agent_run_child_intent(root_run_id, created_at)
    WHERE capacity_released_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_agent_run_child_intent_parent
    ON alphafrog_agent_run_child_intent(parent_run_id, created_at);

-- 操作身份是去重键；这张表才是可领取、可重投、可确认的待投递事实。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_child_outbox (
    id BIGSERIAL PRIMARY KEY,
    intent_id BIGINT NOT NULL UNIQUE REFERENCES alphafrog_agent_run_child_intent(id) ON DELETE CASCADE,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'CLAIMED', 'ACKED', 'CANCELED')),
    claim_token VARCHAR(128),
    claimed_by VARCHAR(128),
    lease_until TIMESTAMPTZ,
    next_delivery_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivery_attempts INT NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    acked_at TIMESTAMPTZ,
    canceled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agent_run_child_outbox_claim_check
        CHECK ((state = 'CLAIMED') = (claim_token IS NOT NULL AND claimed_by IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT agent_run_child_outbox_ack_check
        CHECK ((state = 'ACKED') = (acked_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_agent_run_child_outbox_due
    ON alphafrog_agent_run_child_outbox(next_delivery_at, id)
    WHERE state IN ('PENDING', 'CLAIMED');
