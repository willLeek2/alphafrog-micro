-- lane_tag 的格式在 Run 受理入口统一校验，现有更新语句也不会修改部署身份或泳道标签。
-- 删除重复的泳道标签格式检查与不可变触发器，保留三个持久化字段及部署身份格式约束。

ALTER TABLE IF EXISTS alphafrog_agent_run
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_lane_tag_check;

DROP TRIGGER IF EXISTS trg_agent_run_deployment_identity_immutable ON alphafrog_agent_run;

DROP FUNCTION IF EXISTS alphafrog_reject_agent_run_identity_change();
