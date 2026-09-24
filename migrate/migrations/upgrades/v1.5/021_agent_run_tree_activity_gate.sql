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

-- 根树行预先存在，领取事务按 Run → 根树额度 → 工作项的顺序加锁。
INSERT INTO alphafrog_agent_run_tree_budget(root_run_id)
SELECT id FROM alphafrog_agent_run
ON CONFLICT (root_run_id) DO NOTHING;
