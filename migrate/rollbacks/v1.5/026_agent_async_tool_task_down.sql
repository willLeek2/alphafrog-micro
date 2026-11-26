-- 异步工具任务表的一次性回退脚本。
--
-- 使用方式：停服务，执行本脚本，再部署上一个版本。本脚本不在 migrate.py 的
-- 自动迁移序列里，必须由操作者显式执行；执行前先确认新表之外没有别的写入方。
--
-- 只回写还没走完的异步任务：待执行、执行中，以及取消后停机未确认的任务。
-- 已经成功或失败的终态任务留在新表里，随上一个版本按旧表继续推进；
-- 旧表在回退期间不会再被新版本写入，不存在两边同时推进同一条任务。
--
-- 回写内容与升级脚本互逆：派发证明从请求快照、结构版本、任务编号与名额凭证拼回，
-- 停机状态从任务行的停机字段还原成旧停机记录。写完后按业务键去重，
-- 重复执行不会造出第二行。

-- 1. 未完结任务写回旧等待成员表。
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
    CASE WHEN t.request_json <> '{}'::jsonb
              OR t.task_id IS NOT NULL
              OR t.reservation_json IS NOT NULL
         THEN jsonb_strip_nulls(
                  t.request_json
                  || jsonb_build_object('schemaVersion', t.schema_version)
                  || CASE WHEN t.task_id IS NOT NULL
                          THEN jsonb_build_object('taskId', t.task_id)
                          ELSE '{}'::jsonb END
                  || CASE WHEN t.reservation_json IS NOT NULL
                          THEN jsonb_build_object('reservationJson', t.reservation_json::text)
                          ELSE '{}'::jsonb END)
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
ON CONFLICT DO NOTHING;

-- 2. 停机状态写回旧停机记录表。
--    待停止与已领取写回 PENDING，旧版本重新领取；已确认的只在任务编号存在时写回
--    CONFIRMED，缺任务编号说明当时没有外部作业，旧版本会按「取消成员缺停机记录」补建。
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

-- 3. 回退后核对：未完结任务在新旧两边的行数必须相等，关键列不能有空值。
--    这两条查询应各返回 0 行；返回非 0 时不要部署上一版本。
--
--   SELECT count(*) FROM alphafrog_agent_async_tool_task t
--    WHERE t.state IN ('PENDING', 'RUNNING')
--      AND NOT EXISTS (
--          SELECT 1 FROM alphafrog_agent_run_wait_member m
--           WHERE m.group_id = t.group_id AND m.member_identity = t.member_identity);
--
--   SELECT count(*) FROM alphafrog_agent_run_wait_member m
--    WHERE m.state IN ('PENDING', 'RUNNING')
--      AND (m.member_identity IS NULL OR m.tool_name IS NULL);
