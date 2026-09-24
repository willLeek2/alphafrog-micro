-- 阶段三验收的受限夹具：冻结计划、冻结模型回合与结果放行策略。
--
-- 与阶段二那张一次性故障表（005/006）同一套形状：内容由受限控制面预先写入，Agent 进程只按自己的
-- 泳道、部署代际与夹具编号查回来用；业务请求里只能带一个编号，带不了计划或模型内容，所以普通用户
-- 借不到它注入任意计划。
--
-- 夹具按场景提供，不按 Run 绑定：验收执行器在创建 Run 之前还不知道 Run 号，而 Run 自己的
-- ext.context_json 已经原样保存了请求上下文（含那个编号），执行层凭 Run 的泳道与部署代际加编号
-- 就查得回来，多一份按 Run 的绑定就多一份会漂的副本。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_acceptance_fixture (
    id BIGSERIAL PRIMARY KEY,
    fixture_id VARCHAR(128) NOT NULL,
    lane_id VARCHAR(128) NOT NULL,
    traffic_scope_id VARCHAR(96) NOT NULL,
    deployment_version VARCHAR(128) NOT NULL,
    scenario_id VARCHAR(128) NOT NULL,
    plan_json JSONB,
    model_script_json JSONB NOT NULL,
    dispatch_policy_json JSONB,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    enabled_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    use_count BIGINT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 同一个泳道、同一个部署代际下，一个编号只能有一份夹具：查回来的内容必须是唯一的。
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_identity_key
        UNIQUE (lane_id, deployment_version, fixture_id),
    -- 启用与启用时间成对：只写 enabled 不写时间（或反过来）会让「什么时候开的」这个证据说不清。
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_enablement_check
        CHECK ((enabled = FALSE AND enabled_at IS NULL)
            OR (enabled = TRUE AND enabled_at IS NOT NULL)),
    -- 使用次数与最后一次使用时间成对：证据里要能看出这个夹具被用过几次、最后一次什么时候。
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_use_check
        CHECK ((use_count = 0 AND last_used_at IS NULL)
            OR (use_count > 0 AND last_used_at IS NOT NULL))
);

-- 只索引可用的夹具行：查询按「泳道 + 部署代际 + 编号」精确命中，未启用的行不进索引。
CREATE INDEX IF NOT EXISTS idx_agent_run_acceptance_fixture_ready
    ON alphafrog_agent_run_acceptance_fixture(
        lane_id, deployment_version, fixture_id, expires_at)
    WHERE enabled = TRUE;

COMMENT ON TABLE alphafrog_agent_run_acceptance_fixture IS
    '阶段三验收的受限夹具：按泳道与部署代际提供冻结计划、冻结模型回合与结果放行策略。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture.fixture_id IS
    '验收请求上下文里的 acceptanceFixtureId：请求只带编号，内容全部由受限控制面写入。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture.plan_json IS
    '冻结计划；写计划时必须带 executionMode，Agent 侧拿它与请求的执行模式比对，不符即拒绝创建。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture.model_script_json IS
    '冻结的模型回合，按顺序消费；工具调用的身份与原始顺序都在这里面。一个回合里 text 与 toolCalls 至少写一个，两个都写也可以。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture.dispatch_policy_json IS
    '结果放行与故障策略：延迟哪些成员、按什么顺序放行、哪些成员失败、注入哪种坏记录。';
