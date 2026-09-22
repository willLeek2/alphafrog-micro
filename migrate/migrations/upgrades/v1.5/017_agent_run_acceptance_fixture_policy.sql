-- 阶段三验收夹具的放行策略：规则快照与命中记录。
--
-- 放行策略原来按「工具调用编号」点名成员。编号是模型给的，同一个编号在别的节点、别的分段、别的
-- 模型回合里可以再出现一次（模型完全可能每次都从 call_1 开始编号），只写编号的规则会连带命中那些
-- 成员——压住、放行、判失败的对象就不是夹具作者想说的那一条。现在选择器要写全等待组那一级的五项
-- （计划代际、节点、第几次尝试、第几段、第几次模型回合）加上组内序号，一条选择器在整条 Run 里
-- 只指得出一条成员。这条表现在按「一条规则一行」记：同一 Run 的同一规则只能绑定一个目标，
-- 想再绑定第二个目标会撞唯一键（写入方撞上就当场失败关闭，不会悄悄多记一行把结论撑成通过）。
--
-- 一张表记两件事，分两个时刻写：规则的选择器打中了哪一条成员（matched_at，派发前写），以及被点名
-- 的那个动作有没有真的落到这条成员身上（action_settled_at + action_outcome，动作真的生效时才写）。
-- 只有前者时，一次「点名要求压住某条成员」的验收可能在这条成员当场就出结果的情况下仍然显示证据完整：
-- 压住的动作根本没发生，命中的那一行却还在。补上后者之后，终态核对按「动作真的生效」算。
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
    -- 命中的那一条成员：等待组一级的五项身份加组内序号，工具调用编号模型可能没给
    plan_generation INT NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    node_attempt INT NOT NULL,
    segment_sequence INT NOT NULL,
    model_turn INT NOT NULL,
    member_seq INT NOT NULL,
    tool_call_id VARCHAR(128),
    matched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 动作真的落到这条成员身上的时刻与结果；没生效（比如这条成员当场就出结果）时写 not_applied
    action_settled_at TIMESTAMPTZ,
    action_outcome VARCHAR(32),
    action_detail TEXT,
    -- 一条规则在整条 Run 里只能绑定一个目标：再绑定第二个会撞这个唯一键，写入方当场失败关闭
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_rule_hit_key
        UNIQUE (run_id, rule_index),
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_rule_hit_index_check
        CHECK (rule_index >= 0),
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_rule_hit_outcome_check
        CHECK (action_outcome IS NULL OR action_outcome IN
            ('hold_waiting', 'peer_waiting', 'designated_failure', 'not_applied')),
    -- 结果与时刻成对：要么都还没写（动作还没落地），要么都在（已经知道动作到底有没有生效）
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_rule_hit_settled_check
        CHECK ((action_settled_at IS NULL) = (action_outcome IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_agent_run_acceptance_fixture_rule_hit_run
    ON alphafrog_agent_run_acceptance_fixture_rule_hit(run_id, rule_index);

COMMENT ON TABLE alphafrog_agent_run_acceptance_fixture_rule_hit IS
    '放行策略里每条规则打中了哪一条成员、以及被点名的动作有没有真的落到它身上：一条都没打中、'
    '或者动作没生效的规则要在终态结论里被点名。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_rule_hit.selector_text IS
    '规则选择器的规范写法（字段名按字母序）：规则写错字段名时会一条都不打中，核对结论按它点名。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_rule_hit.action_outcome IS
    '动作真的落到点名成员身上时的结果：hold_waiting（压住等放行点）、peer_waiting（等兄弟成员先落终态）、'
    'designated_failure（按失败收尾）；not_applied 表示点名了成员但动作没有生效。';

-- 终态核对的结论里补上策略这一路：点名的规则一条都没用上时，与「必答回合没发生」一样算这次验收
-- 的证据不完整。
ALTER TABLE alphafrog_agent_run_acceptance_fixture_scenario
    ADD COLUMN IF NOT EXISTS policy_rule_count INT,
    ADD COLUMN IF NOT EXISTS policy_hit_rule_count INT,
    ADD COLUMN IF NOT EXISTS policy_missing_count INT,
    ADD COLUMN IF NOT EXISTS policy_unapplied_count INT,
    ADD COLUMN IF NOT EXISTS policy_detail TEXT;

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.policy_rule_count IS
    '这次验收要求点名的规则条数（策略快照里的条数；夹具没写策略时为空）。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.policy_hit_rule_count IS
    '真的打中过成员、并且被点名的动作真的落到这条成员身上的规则条数。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.policy_missing_count IS
    '一次都没打中任何成员的规则条数。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.policy_unapplied_count IS
    '打中了成员、但被点名的动作没有落到它身上的规则条数（例如点名的成员当场就出了结果，'
    '压住与等兄弟成员没有可等的东西）。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.policy_detail IS
    '没打中的规则清单与动作没有生效的规则清单（规则序号、动作、选择器写法）。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.verdict IS
    '终态核对结论：complete 要求发生的事都发生了、点名的动作也都生效了；script_incomplete 有必答回复'
    '没发生、点名的规则一条都没打中、或者点名的动作没有生效；pending 还没走到终态。';
