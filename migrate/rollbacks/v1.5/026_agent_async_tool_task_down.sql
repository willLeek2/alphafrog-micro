-- 异步工具任务表的一次性回退脚本。
--
-- 使用方式：停服务，执行本脚本（整份在一个事务里），确认末尾三条核对查询都是空结果，
-- 再部署上一个版本。本脚本不在 migrate.py 的自动迁移序列里，必须由操作者显式执行；
-- 执行前先确认新表之外没有别的写入方。
--
-- 回写范围是还没走完的异步任务：待执行、执行中，以及取消后停机未确认的任务。
-- 已经成功或失败的终态任务不需要回写，旧版本只推进未完结的成员，不会去碰它们。
--
-- 每条任务按业务键（组与成员身份）写回旧等待成员表：旧表已有这一行就覆盖成当前状态，
-- 没有就新建一行（升级后新产生的任务属于这种）。覆盖时保留旧行编号，停机记录继续挂在
-- 同一个编号上；重复执行不会造出第二行。
--
-- 派发证明按旧形状拼回：请求快照里已经有的字段原样保留（子智能体证明的结构版本是字符串
-- sub_agent_wait_v1），只有拆到独立列的沙箱任务编号与名额凭证再补回去，
-- 结构版本在快照里缺失时才用列值补。升级时折叠成取消的迟到结果恢复成取消终态，
-- 旧版本不再按迟到分支处理它。
--
-- 凑不齐回写键（组、成员序号、成员身份）的未完结任务写不进旧表：把它的原因码与名额凭证记录
-- 留在新表里（ROLLBACK_INCOMPLETE），末尾第四条查询会把它们列出来，由操作者按凭证核对容量账本，
-- 不静默丢任务。第一步的所有任务都来自等待成员，本来就不该出现这种行；出现了说明新表里混入了
-- 别的写入方，回退前应先查清。

BEGIN;

-- 1. 未完结任务写回旧等待成员表；同组同成员身份已有行时覆盖为当前状态。
INSERT INTO alphafrog_agent_run_wait_member (
    group_id, run_id, member_seq, member_identity, tool_call_id, tool_name,
    external_operation_id, state, result_ref_json, dispatch_proof_json,
    next_poll_at, poll_count, backoff_step, finished_at, created_at, updated_at
)
SELECT
    t.group_id,
    t.run_id,
    t.member_seq,
    t.member_identity,
    t.tool_call_id,
    t.tool_name,
    t.external_operation_id,
    t.state,
    t.result_ref_json,
    -- 没有证明的行写回空值，保持旧表「未派发」的语义。
    CASE WHEN t.request_json <> '{}'::jsonb
              OR t.task_id IS NOT NULL
              OR t.reservation_json IS NOT NULL
         THEN NULLIF(
             t.request_json
             || CASE WHEN t.request_json ->> 'schemaVersion' IS NULL
                     THEN jsonb_build_object('schemaVersion', t.schema_version)
                     ELSE '{}'::jsonb END
             || CASE WHEN t.task_id IS NOT NULL AND t.request_json ->> 'taskId' IS NULL
                     THEN jsonb_build_object('taskId', t.task_id)
                     ELSE '{}'::jsonb END
             || CASE WHEN t.reservation_json IS NOT NULL
                          AND t.request_json ->> 'reservationJson' IS NULL
                     THEN jsonb_build_object('reservationJson', t.reservation_json::text)
                     ELSE '{}'::jsonb END,
             '{}'::jsonb)
    END,
    t.next_poll_at,
    t.poll_count,
    t.backoff_step,
    t.finished_at,
    t.created_at,
    t.updated_at
FROM alphafrog_agent_async_tool_task t
WHERE t.group_id IS NOT NULL
  AND t.member_seq IS NOT NULL
  AND t.member_identity IS NOT NULL
  AND (t.state IN ('PENDING', 'RUNNING')
       OR (t.state = 'CANCELED'
           AND t.stop_state IS NOT NULL
           AND t.stop_state <> 'CONFIRMED'))
ON CONFLICT (group_id, member_identity) DO UPDATE SET
    tool_call_id = EXCLUDED.tool_call_id,
    tool_name = EXCLUDED.tool_name,
    external_operation_id = EXCLUDED.external_operation_id,
    state = EXCLUDED.state,
    result_ref_json = EXCLUDED.result_ref_json,
    dispatch_proof_json = EXCLUDED.dispatch_proof_json,
    next_poll_at = EXCLUDED.next_poll_at,
    poll_count = EXCLUDED.poll_count,
    backoff_step = EXCLUDED.backoff_step,
    finished_at = EXCLUDED.finished_at,
    updated_at = EXCLUDED.updated_at;

-- 2. 凑不齐回写键的未完结任务：写成失败并留原因码，名额凭证原样保留在新表里供人工核对。
UPDATE alphafrog_agent_async_tool_task
SET state = 'FAILED',
    finished_at = COALESCE(finished_at, CURRENT_TIMESTAMP),
    result_ref_json = COALESCE(result_ref_json, '{}'::jsonb)
        || jsonb_build_object('exit_reason', 'ROLLBACK_INCOMPLETE',
                              'rollback_reason', 'missing_member_identity'),
    next_poll_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE state IN ('PENDING', 'RUNNING')
  AND (group_id IS NULL OR member_seq IS NULL OR member_identity IS NULL);

-- 3. 停机状态写回旧停机记录表。
--    待停止与已领取写回 PENDING，旧版本重新领取；已确认的只在任务编号存在时写回
--    CONFIRMED，缺任务编号说明沙箱查无此作业，旧版本会按「取消成员缺停机记录」补建。
--    没有外部作业号或请求快照的取消任务本来就没有可停的作业，旧版本也不会给它建停机记录，
--    这里不写，末尾核对查询会列出仍未确认的停机行供人工确认。
INSERT INTO alphafrog_agent_run_wait_member_stop (
    wait_member_id, run_id, group_id, operation_id, task_id,
    request_fingerprint, cancel_request_id, state, next_attempt_at,
    attempt_count, last_error, terminal_task_id, terminal_status, terminal_confirmed_at)
SELECT
    m.id,
    t.run_id,
    t.group_id,
    t.external_operation_id,
    t.task_id,
    t.request_json ->> 'requestFingerprint',
    'wait-member-' || m.id::text,
    CASE WHEN t.stop_state = 'CONFIRMED' THEN 'CONFIRMED' ELSE 'PENDING' END,
    COALESCE(t.next_poll_at, CURRENT_TIMESTAMP),
    t.stop_attempt_count,
    t.stop_last_error,
    CASE WHEN t.stop_state = 'CONFIRMED' THEN t.task_id END,
    CASE WHEN t.stop_state = 'CONFIRMED' THEN 'CANCELED' END,
    CASE WHEN t.stop_state = 'CONFIRMED' THEN COALESCE(t.finished_at, CURRENT_TIMESTAMP) END
FROM alphafrog_agent_async_tool_task t
JOIN alphafrog_agent_run_wait_member m
  ON m.group_id = t.group_id
 AND m.member_identity = t.member_identity
WHERE t.state = 'CANCELED'
  AND t.stop_state IN ('PENDING', 'CLAIMED', 'CONFIRMED')
  AND t.external_operation_id IS NOT NULL
  AND t.request_json <> '{}'::jsonb
  AND (t.stop_state <> 'CONFIRMED' OR t.task_id IS NOT NULL)
ON CONFLICT DO NOTHING;

COMMIT;

-- 4. 回退后核对：前三查询必须返回 0 行，有结果时不要部署上一版本；
--    第四条是信息查询，正常情况下会有 0 行，出现行时按凭证人工核对，不属于「必须为空」。
--    第一条：新表里还有未完结任务没写回旧表（正常应为 0，除第 2 步标记为失败的之外）。
SELECT t.job_id, t.run_id, t.state, t.tool_name, t.reservation_json
  FROM alphafrog_agent_async_tool_task t
 WHERE t.state IN ('PENDING', 'RUNNING')
   AND NOT EXISTS (
       SELECT 1 FROM alphafrog_agent_run_wait_member m
        WHERE m.group_id = t.group_id AND m.member_identity = t.member_identity);

--    第二条：旧表里有关键列为空的未完结成员（正常应为 0）。
SELECT m.id, m.group_id, m.member_identity, m.tool_name
  FROM alphafrog_agent_run_wait_member m
 WHERE m.state IN ('PENDING', 'RUNNING')
   AND (m.member_identity IS NULL OR m.tool_name IS NULL OR m.group_id IS NULL);

--    第三条：还没确认的停机行（正常应为 0；有结果说明这些沙箱作业要在旧版本上继续停，
--    或由人工确认后按任务行的名额凭证核对容量）。
SELECT t.job_id, t.run_id, t.tool_name, t.external_operation_id, t.stop_state,
       t.stop_last_error, t.reservation_json
  FROM alphafrog_agent_async_tool_task t
 WHERE t.state = 'CANCELED'
   AND t.stop_state IS NOT NULL
   AND t.stop_state <> 'CONFIRMED';

--    第四条（信息查询，不属于「必须为空」）：第 2 步标成 ROLLBACK_INCOMPLETE 的行。
--    它们没有能写回旧表的身份，随旧表删除也无法被上一版本推进；请按名额凭证与容量账本
--    核对后人工归还容量，并把处理结果记进发布记录。
SELECT t.job_id, t.run_id, t.tool_name, t.stop_state,
       t.result_ref_json ->> 'rollback_reason' AS rollback_reason,
       t.reservation_json
  FROM alphafrog_agent_async_tool_task t
 WHERE t.result_ref_json ->> 'exit_reason' = 'ROLLBACK_INCOMPLETE';
