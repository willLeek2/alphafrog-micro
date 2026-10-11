-- 阶段三验收夹具的「消费位置」与「终态核对」：哪一次模型调用拿走了哪一个脚本回合。
--
-- 原来「第几个回合」记在 Agent 进程的内存里：进程一重启，位置就没了，已经规划过的夹具 Run 只能按
-- 失败处理，于是「停 Agent 再恢复」这类场景永远测不出来；而且位置按顺序发，同一个 Run 上两个节点
-- 并行时谁先拿到回复由线程调度决定，脚本说不清哪一份是哪个节点的。
--
-- 改成按调用身份发之后，位置成为数据库里的事实：一次调用（哪条 Run、哪个阶段、哪个节点、哪一段、
-- 第几次模型回合）对应一个回合，重启后重做同一段报同一个身份，拿回同一份回复；两个节点并行时各领
-- 各的声明回合，与先后无关。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_acceptance_fixture_call (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    fixture_id VARCHAR(128) NOT NULL,
    scenario_id VARCHAR(128) NOT NULL,
    -- 调用身份的规范写法：stage=node;modelTurn=0;nodeId=n1;...（字段名按字母序，落库文本唯一）
    call_identity TEXT NOT NULL,
    call_stage VARCHAR(32) NOT NULL,
    turn_index INT NOT NULL,
    -- 认领时那份脚本的摘要：同一条 Run 跑的过程中夹具行被原位改过时，重放会对不上，当场拒绝
    script_digest VARCHAR(64) NOT NULL,
    -- 命中的那条声明原文：证据里要能看出这个回合当初是声明回答哪一次调用的
    declared_for TEXT NOT NULL,
    -- 这条声明是不是「可以不发生」；可以不发生的回合没被领走不算问题
    optional_turn BOOLEAN NOT NULL DEFAULT FALSE,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 一次调用只能领一个回合：同一个身份重放时读回这一行，不再往下领
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_call_identity_key
        UNIQUE (run_id, call_identity),
    -- 一个回合只能被一次调用领走：两个线程同时抢，只有一条会插进去，另一条换下一个声明回合
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_call_turn_key
        UNIQUE (run_id, turn_index),
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_call_turn_check
        CHECK (turn_index >= 0)
);

-- 按 Run 读全部认领行：终态核对、验收证据与排查都用这一条。
CREATE INDEX IF NOT EXISTS idx_agent_run_acceptance_fixture_call_run
    ON alphafrog_agent_run_acceptance_fixture_call(run_id, turn_index);

COMMENT ON TABLE alphafrog_agent_run_acceptance_fixture_call IS
    '阶段三验收夹具的模型回合认领表：调用身份 → 脚本回合，跨进程重启仍然是同一份事实。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_call.call_identity IS
    '模型调用的稳定身份（阶段 + 域字段），同一段重做、重启后重领都是同一个值。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_call.script_digest IS
    '认领时那份夹具脚本的 sha-256：内容被原位改过时重放会拒绝，避免一次验收说不清用的是哪一版。';

-- 一条 Run 用的那份场景（脚本声明）与它终态那一刻的核对结论。
--
-- 少了回合（比如某个节点的模型回合根本没发生、连续等待组被跳过）时，前面那几条回复照样能让 Run
-- 走到终态，脚本尾部没用上的部分会被静默丢掉，验收看起来是过的。所以「这次验收要求哪些回复发生」
-- 与「实际发生了哪些」要一起留成可查的证据：声明快照在第一次认领时写下（那时夹具行一定还在，
-- 之后夹具过期或被删掉也不影响事后核对），终态那一刻按认领行算出差了哪几条必答声明。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_acceptance_fixture_scenario (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    fixture_id VARCHAR(128) NOT NULL,
    scenario_id VARCHAR(128) NOT NULL,
    script_digest VARCHAR(64) NOT NULL,
    script_size INT NOT NULL,
    -- 全部声明的清单（回合序号、阶段、域字段、是否可以不发生），JSON 数组
    declarations_json JSONB NOT NULL,
    required_turn_count INT NOT NULL,
    optional_turn_count INT NOT NULL,
    -- 终态核对结论：complete 必答声明都发生过；script_incomplete 有必答声明没发生；pending 还没走到终态
    verdict VARCHAR(32) NOT NULL DEFAULT 'pending',
    claimed_turn_count INT,
    required_missing_count INT,
    -- 没发生的必答声明清单（回合序号 + 声明原文）
    detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recorded_at TIMESTAMPTZ,
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_scenario_run_key
        UNIQUE (run_id),
    CONSTRAINT alphafrog_agent_run_acceptance_fixture_scenario_verdict_check
        CHECK (verdict IN ('pending', 'complete', 'script_incomplete'))
);

CREATE INDEX IF NOT EXISTS idx_agent_run_acceptance_fixture_scenario_verdict
    ON alphafrog_agent_run_acceptance_fixture_scenario(verdict, created_at);

COMMENT ON TABLE alphafrog_agent_run_acceptance_fixture_scenario IS
    '夹具 Run 用的场景声明快照与终态核对结论：脚本写了但实际没发生的必答回合要能被点名。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.declarations_json IS
    '脚本里全部回合的声明：验收结论里「这次到底要求发生哪些调用」读它，不再依赖夹具行还在不在。';

COMMENT ON COLUMN alphafrog_agent_run_acceptance_fixture_scenario.detail IS
    '没发生的必答声明清单（含回合序号与声明原文），以及核对没做（Run 还没走到终态）时的说明。';
