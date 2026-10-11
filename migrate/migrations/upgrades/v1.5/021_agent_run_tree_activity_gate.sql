-- 新额度代码接管之前必须排空旧进程留下的在途节点和外部等待。
-- 旧版本不会写根树操作记录；若直接上线，释放时无法判断该扣哪一棵树。
DO $$
DECLARE
    outstanding TEXT;
BEGIN
    SELECT string_agg(detail, ', ') INTO outstanding
      FROM (
        SELECT 'work_item=' || id || ' run=' || run_id || ' epoch=' || claim_epoch AS detail
          FROM alphafrog_agent_run_work_item
         WHERE state IN ('CLAIMED', 'EXECUTING')
        UNION ALL
        SELECT 'wait_group=' || id || ' run=' || run_id AS detail
          FROM alphafrog_agent_run_wait_group
         WHERE state = 'WAITING'
         LIMIT 20
      ) sample;
    IF outstanding IS NOT NULL THEN
        RAISE EXCEPTION '阶段5A根树在途额度迁移被阻止；先停旧实例并收尾这些行：%', outstanding;
    END IF;
END $$;

-- 旧版 DAG 在创建请求回读为“暂未找到”时可能把容量记为已释放，
-- 但迟到的 Sandbox 创建请求仍可能到达。新协议不能追认这类旧证明；
-- 先隔离旧记录，核对 operationId / requestFingerprint 对应任务的真实终态。
DO $$
DECLARE
    legacy_abort TEXT;
BEGIN
    SELECT string_agg(detail, ', ') INTO legacy_abort
      FROM (
        SELECT 'run=' || id || ' operation=' ||
               COALESCE(tool_job_anchor_json ->> 'operationId', '<missing>') AS detail
          FROM alphafrog_agent_run
         WHERE tool_job_anchor_json ->> 'runDisposition' = 'DAG_BLOCKING_PREPARING_ABORT'
            OR (tool_job_anchor_json ->> 'anchorState' IN ('ABORTING', 'CLEARING')
                AND tool_job_anchor_json ->> 'runDisposition' LIKE 'DAG_BLOCKING%')
         LIMIT 20
      ) sample;
    IF legacy_abort IS NOT NULL THEN
        RAISE EXCEPTION '阶段5A迁移发现旧版DAG创建中止记录；先核对Sandbox任务终态：%', legacy_abort;
    END IF;
END $$;

-- 根树行预先存在，领取事务按 Run → 根树额度 → 工作项的顺序加锁。
INSERT INTO alphafrog_agent_run_tree_budget(root_run_id)
SELECT id FROM alphafrog_agent_run
ON CONFLICT (root_run_id) DO NOTHING;
