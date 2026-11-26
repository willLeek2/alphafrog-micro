-- 异步工具任务表：一行对应一次异步工具调用从创建到终态的全过程。
--
-- 合并范围是异步任务本身的状态，来源是等待成员、停机记录和运行锚点三处；
-- 等待组、工作项、恢复通知三张表不合并，分别保留组关系、领取与通知的职责。
-- 旧表在本迁移执行后保持只读，第七步统一删除。
CREATE TABLE IF NOT EXISTS alphafrog_agent_async_tool_task (
    job_id BIGSERIAL PRIMARY KEY,
    job_kind VARCHAR(32) NOT NULL,
    run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    work_item_id BIGINT,
    group_id BIGINT,
    member_seq INT,
    member_identity VARCHAR(256),
    tool_call_id VARCHAR(256),
    tool_name VARCHAR(128) NOT NULL,
    external_operation_id VARCHAR(256),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    request_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    task_id VARCHAR(256),
    reservation_json JSONB,
    result_ref_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    schema_version INT NOT NULL DEFAULT 1,
    owner_instance_id VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    claim_epoch BIGINT NOT NULL DEFAULT 0,
    next_poll_at TIMESTAMPTZ,
    poll_count INT NOT NULL DEFAULT 0,
    backoff_step INT NOT NULL DEFAULT 0,
    stop_state VARCHAR(32),
    stop_attempt_count INT NOT NULL DEFAULT 0,
    stop_last_error VARCHAR(512),
    timeout_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agent_async_tool_task_kind_check
        CHECK (job_kind IN ('SANDBOX_TOOL', 'CHILD_RUN_WAIT')),
    CONSTRAINT agent_async_tool_task_state_check
        CHECK (state IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED')),
    CONSTRAINT agent_async_tool_task_counter_check
        CHECK (poll_count >= 0 AND backoff_step >= 0 AND claim_epoch >= 0
               AND stop_attempt_count >= 0),
    CONSTRAINT agent_async_tool_task_stop_state_check
        CHECK (stop_state IS NULL OR stop_state IN ('PENDING', 'CLAIMED', 'CONFIRMED')),
    -- 终态与结束时刻一一对应：终态必须有结束时刻，非终态必须没有。
    CONSTRAINT agent_async_tool_task_finished_check
        CHECK ((state IN ('SUCCEEDED', 'FAILED', 'CANCELED')) = (finished_at IS NOT NULL)),
    CONSTRAINT agent_async_tool_task_member_identity_not_blank_check
        CHECK (member_identity IS NULL OR length(btrim(member_identity)) > 0),
    CONSTRAINT agent_async_tool_task_tool_name_not_blank_check
        CHECK (length(btrim(tool_name)) > 0),
    -- 任务行只能挂在本组的 Run 上：组在 A 运行、成员行却写 B 运行，结果接收方与停机扫描
    -- 就会在两个 Run 之间各找一半。组编号为空的行不受这条约束（复合外键按 MATCH SIMPLE 判定）。
    CONSTRAINT agent_async_tool_task_group_fk
        FOREIGN KEY (group_id, run_id) REFERENCES alphafrog_agent_run_wait_group(id, run_id)
);

-- 一个外部作业只能挂到一行；跨运行也一样。唯一约束只防两行抢同一作业，
-- 不能证明创建请求已经发出，创建结果不确定时仍要按请求快照重放。
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_async_tool_task_operation
    ON alphafrog_agent_async_tool_task(external_operation_id)
    WHERE external_operation_id IS NOT NULL;

-- 组内成员序号与成员身份各自唯一，保留旧等待成员表的两个约束口径。
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_async_tool_task_member_seq
    ON alphafrog_agent_async_tool_task(group_id, member_seq)
    WHERE group_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_async_tool_task_member_identity
    ON alphafrog_agent_async_tool_task(group_id, member_identity)
    WHERE group_id IS NOT NULL;

-- 到期扫描直接从表里取，不再依赖 Redis 到期索引。
CREATE INDEX IF NOT EXISTS idx_agent_async_tool_task_due
    ON alphafrog_agent_async_tool_task(next_poll_at, job_id)
    WHERE state IN ('PENDING', 'RUNNING') AND next_poll_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_async_tool_task_run_state
    ON alphafrog_agent_async_tool_task(run_id, state);

CREATE INDEX IF NOT EXISTS idx_agent_async_tool_task_work_item
    ON alphafrog_agent_async_tool_task(run_id, work_item_id, state);

-- 停机扫描只取待停止与已领取的行；取消后的行是 CANCELED，到期索引覆盖不到，单独建一条。
CREATE INDEX IF NOT EXISTS idx_agent_async_tool_task_stop_due
    ON alphafrog_agent_async_tool_task(next_poll_at, job_id)
    WHERE stop_state IN ('PENDING', 'CLAIMED');

-- 一次性迁移：只迁未完结的异步任务，以及仍有未确认停机的已取消任务。
-- 已终态且没有停机尾巴的行留在旧表，随第七步删表一起清理。
--
-- 派发证明原样搬进请求快照列：沙箱证明与子智能体证明（spawnSubAgent、waitForSubAgent）
-- 的字段形状不同，后者的结构版本是字符串 sub_agent_wait_v1，不做逐字段拆分，
-- 由读适配把它拼回旧形状，避免两类证明互相覆盖。
INSERT INTO alphafrog_agent_async_tool_task (
    job_kind, run_id, work_item_id, group_id, member_seq, member_identity,
    tool_call_id, tool_name, external_operation_id, state, request_json,
    task_id, reservation_json, result_ref_json, schema_version,
    owner_instance_id, lease_expires_at, claim_epoch, next_poll_at, poll_count,
    backoff_step, timeout_at, finished_at, created_at, updated_at
)
SELECT
    CASE WHEN m.tool_name IN ('executePython', 'executeQuery')
         THEN 'SANDBOX_TOOL' ELSE 'CHILD_RUN_WAIT' END,
    m.run_id,
    NULL,
    m.group_id,
    m.member_seq,
    m.member_identity,
    m.tool_call_id,
    m.tool_name,
    m.external_operation_id,
    CASE WHEN m.state = 'LATE' THEN 'CANCELED' ELSE m.state END,
    COALESCE(m.dispatch_proof_json, '{}'::jsonb),
    NULLIF(m.dispatch_proof_json ->> 'taskId', ''),
    NULLIF(m.dispatch_proof_json ->> 'reservationJson', '')::jsonb,
    m.result_ref_json,
    CASE WHEN jsonb_typeof(m.dispatch_proof_json -> 'schemaVersion') = 'number'
         THEN (m.dispatch_proof_json ->> 'schemaVersion')::int
         ELSE 1 END,
    NULL,
    NULL,
    0,
    m.next_poll_at,
    m.poll_count,
    m.backoff_step,
    NULL,
    m.finished_at,
    m.created_at,
    m.updated_at
FROM alphafrog_agent_run_wait_member m
WHERE m.state IN ('PENDING', 'RUNNING')
   OR EXISTS (
       SELECT 1
       FROM alphafrog_agent_run_wait_member_stop s
       WHERE s.wait_member_id = m.id
         AND s.state <> 'CONFIRMED'
   );

-- 停机尾巴按成员行挂到新任务行上。BLOCKED_PROOF 并入待停止，
-- 停机的领取复用任务租约：重试时刻取旧停机记录的下一次尝试时刻，已领取的行连持有实例与租约一起带过来
-- （旧实例已停服，租约到期前新实例不会接手，等它过期或旧实例退出后自然接手）。
-- 旧的 claim_token 是随机串，与新的领取轮次不是一套东西，不搬；轮次从 0 开始重新计数。
UPDATE alphafrog_agent_async_tool_task t
SET stop_state = CASE s.state
                     WHEN 'CONFIRMED' THEN 'CONFIRMED'
                     WHEN 'CLAIMED' THEN 'CLAIMED'
                     ELSE 'PENDING'
                 END,
    stop_attempt_count = s.attempt_count,
    stop_last_error = s.last_error,
    -- 停机记录的时刻优先：带停机尾巴的行要尽快被停机扫描取走，成员行的轮询退避可能还在未来。
    next_poll_at = COALESCE(s.next_attempt_at, t.next_poll_at),
    owner_instance_id = CASE WHEN s.state = 'CLAIMED' THEN s.claimed_by END,
    lease_expires_at = CASE WHEN s.state = 'CLAIMED' THEN s.lease_until END,
    updated_at = GREATEST(t.updated_at, s.updated_at)
FROM alphafrog_agent_run_wait_member_stop s
JOIN alphafrog_agent_run_wait_member m ON m.id = s.wait_member_id
WHERE m.run_id = t.run_id
  AND m.group_id = t.group_id
  AND m.member_seq = t.member_seq;

UPDATE alphafrog_agent_async_tool_task
SET timeout_at = CAST(r.tool_job_anchor_json ->> 'timeoutAt' AS timestamptz),
    updated_at = GREATEST(updated_at, CURRENT_TIMESTAMP)
FROM alphafrog_agent_run r
WHERE r.id = run_id
  AND external_operation_id = r.tool_job_anchor_json ->> 'operationId'
  AND (r.tool_job_anchor_json ->> 'timeoutAt') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}'
  AND timeout_at IS NULL;

-- 迁移校验：与源表未完结行数对账、检查关键列，不通过就抛异常，整份脚本随事务回滚。
DO $migration_check$
DECLARE
    expected BIGINT;
    migrated BIGINT;
BEGIN
    SELECT count(*) INTO expected
      FROM alphafrog_agent_run_wait_member m
     WHERE m.state IN ('PENDING', 'RUNNING')
        OR EXISTS (
            SELECT 1 FROM alphafrog_agent_run_wait_member_stop s
             WHERE s.wait_member_id = m.id AND s.state <> 'CONFIRMED');
    SELECT count(*) INTO migrated
      FROM alphafrog_agent_async_tool_task
     WHERE member_identity IS NOT NULL AND work_item_id IS NULL;
    IF migrated <> expected THEN
        RAISE EXCEPTION '异步工具任务迁移行数不一致：源表未完结 % 行，新表迁入 % 行', expected, migrated;
    END IF;
    IF EXISTS (
            SELECT 1 FROM alphafrog_agent_async_tool_task
             WHERE member_identity IS NOT NULL AND work_item_id IS NULL
               AND (run_id IS NULL OR tool_name IS NULL OR state IS NULL
                    OR request_json IS NULL OR finished_at IS NOT NULL AND state IN ('PENDING', 'RUNNING'))) THEN
        RAISE EXCEPTION '异步工具任务迁移后存在关键列为空或状态与结束时刻不一致的行';
    END IF;
END
$migration_check$;

COMMENT ON TABLE alphafrog_agent_async_tool_task IS
    '异步工具任务：一次异步工具调用一行，合并等待成员、停机记录与运行锚点里的任务状态。';
COMMENT ON COLUMN alphafrog_agent_async_tool_task.job_kind IS
    '任务种类：SANDBOX_TOOL 是沙箱作业，CHILD_RUN_WAIT 是等待子运行。';
COMMENT ON COLUMN alphafrog_agent_async_tool_task.request_json IS
    '请求快照：保留创建请求、规范化规格与请求指纹，崩溃后按它原样重放。';
COMMENT ON COLUMN alphafrog_agent_async_tool_task.task_id IS
    '沙箱返回的任务编号；用于查询结果和取消时停机。';
COMMENT ON COLUMN alphafrog_agent_async_tool_task.reservation_json IS
    '名额凭证；终态确认后按它精确归还容量。';
COMMENT ON COLUMN alphafrog_agent_async_tool_task.claim_epoch IS
    '任务级领取轮次；写回时与持有实例、租约一起做条件更新，拒绝旧执行者。';
