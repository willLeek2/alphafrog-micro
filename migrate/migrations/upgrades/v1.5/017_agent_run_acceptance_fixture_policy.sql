-- 阶段三验收夹具的放行策略：规则快照与命中记录。
--
-- 放行策略原来按「工具调用编号」点名成员。编号是模型给的，同一个编号在别的节点、别的分段、别的
-- 模型回合里可以再出现一次（模型完全可能每次都从 call_1 开始编号），只写编号的规则会连带命中那些
-- 成员——压住、放行、判失败的对象就不是夹具作者想说的那一条。改成选择器（节点、第几次尝试、第几段、
-- 第几次模型回合 + 组内序号或工具调用编号）之后，还剩一件事要能核对：规则写错了字段名或序号时
-- 不会报错，只会一条都不打中，那次验收看起来像跑完了。所以每条规则命中一条成员就留一行记录，
-- 跑到终态时按它核对「点名的这几件事是不是真的都发生了」。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_acceptance_fixture_policy (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    fixture_id VARCHAR(128) NOT NULL,
    scenario_id VARCHAR(128) NOT NULL,
    -- 读到策略时那份原文的 sha-256：同一条 Run 跑的过程中夹具行被原位改过时，重读会对不上
    policy_digest VARCHAR(64) NOT NULL,
    -- 全部规则的清单（序号、动作、选择器的规范写法），JSON 数组
    rules_json JSONB NOT NULL,
    rule_count INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_policy_run_key UNIQUE (run_id)
);

COMMENT ON TABLE alphafrog_agent_run_acceptance_fixture_policy IS
    '夹具 Run 读到时的那份放行策略：验收结论里「这次点名要求哪些调用」读它，不再依赖夹具行还在不在。';

CREATE TABLE IF NOT EXISTS alphafrog_agent_run_acceptance_fixture_rule_hit (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    -- 规则在策略里的序号（从 0 起）
    rule_index INT NOT NULL,
    -- 规则选择器的规范写法：排查时一眼看得出这条规则点的名
    selector_text TEXT NOT NULL,
    action VARCHAR(32) NOT NULL,
    group_id BIGINT NOT NULL,
    member_seq INT NOT NULL,
    matched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 同一件事只记一行：派发前核一次、结果接收方再核一次，两条路都会记，重复记不算新事件
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_rule_hit_key
        UNIQUE (run_id, rule_index, group_id, member_seq),
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_rule_hit_index_check
        CHECK (rule_index >= 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_run_acceptance_fixture_rule_hit_run
    ON alphafrog_agent_run_acceptance_fixture_rule_hit(run_id, rule_index);

COMMENT ON TABLE alphafrog_agent_run_acceptance_fixture_rule_hit IS
    '放行策略里每条规则真的打中了哪一条成员：一次都没打中的规则要在终态结论里被点名。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_rule_hit.selector_text IS
    '规则选择器的规范写法（字段名按字母序）：规则写错字段名时会一条都不打中，核对结论按它点名。';

-- 终态核对的结论里补上策略这一路：点名的规则一条都没用上时，与「必答回合没发生」一样算这次验收
-- 的证据不完整。
ALTER TABLE alphafrog_agent_run_acceptance_fixture_scenario
    ADD COLUMN IF NOT EXISTS policy_rule_count INT,
    ADD COLUMN IF NOT EXISTS policy_hit_rule_count INT,
    ADD COLUMN IF NOT EXISTS policy_missing_count INT,
    ADD COLUMN IF NOT EXISTS policy_detail TEXT;

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.policy_rule_count IS
    '这次验收要求点名的规则条数（策略快照里的条数；夹具没写策略时为空）。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.policy_hit_rule_count IS
    '真的打中过成员的规则条数。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.policy_missing_count IS
    '一次都没打中任何成员的规则条数。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.policy_detail IS
    '一次都没打中的规则清单（规则序号、动作、选择器写法）。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.verdict IS
    '终态核对结论：complete 要求发生的事都发生了；script_incomplete 有必答回复没发生、或者点名的规则一条都没打中；pending 还没走到终态。';
