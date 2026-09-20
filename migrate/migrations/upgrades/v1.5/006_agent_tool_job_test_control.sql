-- 受限验收控制面需要同时保存部署标识与流量泳道。005 中的 lane_id 为保持
-- Agent 消费侧兼容，实际保存 deployment_id；traffic_scope_id 单独保存真实泳道标签。
ALTER TABLE alphafrog_agent_tool_job_fault_injection
    ADD COLUMN IF NOT EXISTS traffic_scope_id VARCHAR(96);

UPDATE alphafrog_agent_tool_job_fault_injection fault
   SET traffic_scope_id = COALESCE(run.lane_tag, fault.lane_id)
  FROM alphafrog_agent_run run
 WHERE fault.run_id = run.id
   AND fault.traffic_scope_id IS NULL;

ALTER TABLE alphafrog_agent_tool_job_fault_injection
    ALTER COLUMN traffic_scope_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_tool_job_fault_control_identity
    ON alphafrog_agent_tool_job_fault_injection(
        lane_id, traffic_scope_id, deployment_version, run_id, scenario_id);

COMMENT ON COLUMN alphafrog_agent_tool_job_fault_injection.lane_id IS
    'Agent 消费侧使用的 deployment_id；历史列名为 lane_id，暂时保留以兼容已发布代码。';
COMMENT ON COLUMN alphafrog_agent_tool_job_fault_injection.traffic_scope_id IS
    'Beta 控制器从部署状态派生的隔离泳道标签，用于阻止跨泳道写入和查询。';
