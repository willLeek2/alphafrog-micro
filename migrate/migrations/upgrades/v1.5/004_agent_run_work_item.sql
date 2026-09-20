-- 双 Worker 池骨架（01318-02A）：通用节点工作项表与 Run 的调度器版本列。
--
-- 这份脚本只做扩展，不删除存量数据：新建一张通用工作项表，并在 Run 主记录上增加
-- 调度器版本、计划代际与控制版本三列。
-- 旧调度器版本的 Run 继续用旧的 DAG 子表，不会往这张新表里写任何东西；等旧 Run 排空之后
-- 再另做一次收缩迁移（计划《双 Worker 池骨架开发与验收计划》第五节「清理与迁移」）。

-- 1. Run 主记录加调度器版本：权威来源。字段非空，存量行回填 LEGACY。
ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS scheduler_version VARCHAR(32) NOT NULL DEFAULT 'LEGACY';

-- Run 行保存计划代际权威值；-1 表示还没有创建过双池计划。
ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS plan_generation INT NOT NULL DEFAULT -1;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS run_control_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS alphafrog_agent_run
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_scheduler_version_check;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD CONSTRAINT alphafrog_agent_run_scheduler_version_check
        CHECK (scheduler_version IN ('LEGACY', 'DUAL_POOL_V1'));

ALTER TABLE IF EXISTS alphafrog_agent_run
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_plan_generation_check;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD CONSTRAINT alphafrog_agent_run_plan_generation_check
        CHECK (plan_generation >= -1);

ALTER TABLE IF EXISTS alphafrog_agent_run
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_control_version_check;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD CONSTRAINT alphafrog_agent_run_control_version_check
        CHECK (run_control_version >= 0);

-- 2. 通用节点工作项表：一次节点工作一行，两种编排生成同一种行。
--
-- 五个身份字段（run_id、plan_generation、node_id、node_attempt、segment_sequence）由唯一约束保证
-- 同一个身份不会出现两行；领取时的条件更新只解决同一行的竞争，替代不了这条约束。
--
-- 状态取值：RUNNABLE / CLAIMED / EXECUTING / RESULT_COMMITTED / EXECUTION_FAILED / CANCELED / STALE
-- 是本段生效的七个；WAITING / RESUMABLE 是下一段（持久挂起与恢复）的保留值，本段只允许写进列、
-- 不产生迁移，避免下一段再改一次约束。
--
-- 四个版本列：plan_generation 同时是身份字段与计划代际；context_version 是创建这条工作项时读取的
-- 精确上下文版本；run_control_version 是暂停、恢复、取消、计划失效的推进版本；claim_epoch 是领取
-- 成功后原子加一的执行权令牌。scheduler_version 在这里只做冗余复制，用来按版本过滤，权威仍在 Run 行。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_work_item (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    plan_generation INT NOT NULL,
    node_id VARCHAR(256) NOT NULL,
    node_attempt INT NOT NULL DEFAULT 0,
    segment_sequence INT NOT NULL DEFAULT 0,
    state VARCHAR(32) NOT NULL DEFAULT 'RUNNABLE',
    context_version BIGINT NOT NULL,
    run_control_version BIGINT NOT NULL,
    claim_epoch INT NOT NULL DEFAULT 0,
    scheduler_version VARCHAR(32) NOT NULL DEFAULT 'DUAL_POOL_V1',
    claimed_by VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    next_visible_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT alphafrog_agent_run_work_item_identity_key
        UNIQUE (run_id, plan_generation, node_id, node_attempt, segment_sequence),
    CONSTRAINT alphafrog_agent_run_work_item_state_check
        CHECK (state IN ('RUNNABLE', 'CLAIMED', 'EXECUTING', 'RESULT_COMMITTED',
                         'EXECUTION_FAILED', 'CANCELED', 'STALE', 'WAITING', 'RESUMABLE')),
    CONSTRAINT alphafrog_agent_run_work_item_scheduler_version_check
        CHECK (scheduler_version IN ('LEGACY', 'DUAL_POOL_V1')),
    CONSTRAINT alphafrog_agent_run_work_item_counter_check
        CHECK (plan_generation >= 0 AND node_attempt >= 0 AND segment_sequence >= 0 AND claim_epoch >= 0),
    CONSTRAINT alphafrog_agent_run_work_item_owner_check
        CHECK (state NOT IN ('CLAIMED', 'EXECUTING') OR claimed_by IS NOT NULL)
);

-- 3. 热路径索引。终态是 RESULT_COMMITTED / EXECUTION_FAILED / CANCELED / STALE 四种；
--    保留值 WAITING / RESUMABLE 属于非终态，所以用「不在终态里」而不是「在三个状态里」来圈定热扫描范围。
CREATE INDEX IF NOT EXISTS idx_agent_run_work_item_unfinished
    ON alphafrog_agent_run_work_item(scheduler_version, state, next_visible_at)
    WHERE state NOT IN ('RESULT_COMMITTED', 'EXECUTION_FAILED', 'CANCELED', 'STALE');

CREATE INDEX IF NOT EXISTS idx_agent_run_work_item_run_unfinished
    ON alphafrog_agent_run_work_item(run_id, state)
    WHERE state NOT IN ('RESULT_COMMITTED', 'EXECUTION_FAILED', 'CANCELED', 'STALE');

CREATE INDEX IF NOT EXISTS idx_agent_run_work_item_lease
    ON alphafrog_agent_run_work_item(lease_expires_at)
    WHERE lease_expires_at IS NOT NULL
      AND state NOT IN ('RESULT_COMMITTED', 'EXECUTION_FAILED', 'CANCELED', 'STALE');

COMMENT ON TABLE alphafrog_agent_run_work_item IS
    '通用节点工作项：一次节点工作一行，五个身份字段唯一；状态、四类版本、领取者与租约都在本行上。';
COMMENT ON COLUMN alphafrog_agent_run_work_item.payload_json IS
    '节点本地载荷：节点输入、执行位置、检查点引用、执行器专用内容。整图的 frontier 不放这里，它在 Run 主记录上。';
COMMENT ON COLUMN alphafrog_agent_run_work_item.scheduler_version IS
    '调度器版本的冗余复制，只用于按版本过滤；权威在 alphafrog_agent_run.scheduler_version。';
COMMENT ON COLUMN alphafrog_agent_run_work_item.lease_expires_at IS
    '租约到期只是一条观测事实：不据此更换领取者，也不据此把工作项标成过期。';
