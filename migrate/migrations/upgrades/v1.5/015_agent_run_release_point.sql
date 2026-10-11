-- 15. 验收夹具的放行点：被策略压住的成员结果，等这里被标成已放行才收尾。
--
-- 用途：阶段三的验收要看「结果先回来、但先被压住，之后再集中放行」这类形态。夹具的
-- dispatch_policy_json 点名哪几条成员要压住，压住的成员结果不落终态；这里的一行表示某个放行点，
-- opened_at 有值就是已经放行。控制面（宿主机上的受限验收执行器）先把点建出来，需要放的那一刻把
-- opened_at 与 opened_by 写上。
--
-- 为什么按「许可」而不是「令牌」：代码每一轮重新读它，标成已放行之后重复读不会有副作用。
-- 若改成「取走一次」，进程在取走与落终态之间退出，这次放行就丢了，被压住的成员要再等一轮没人
-- 会再触发的事件。同一条成员终态只会写一次，由成员状态机自己的条件更新保证。
--
-- lane_id 与 deployment_version 两列与夹具表同一套作用域口径（部署编号与部署代际），作为「这个点
-- 是哪个泳道哪个代际下开的」的证据随行保存；判断本身按 run_id + release_key 命中——Run 自己的泳道
-- 与代际在取夹具那一步已经核对过，同一条 Run 的放行点不会串到别的泳道去。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_release_point (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    release_key VARCHAR(128) NOT NULL,
    lane_id VARCHAR(128) NOT NULL,
    deployment_version VARCHAR(128) NOT NULL,
    opened_at TIMESTAMPTZ,
    opened_by VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 同一个 Run 的一个放行点只能有一行：策略里点名的是「哪一个点」，重复行会让放行判断说不清。
    CONSTRAINT alphafrog_agent_run_release_point_identity_key
        UNIQUE (run_id, release_key),
    -- 放行时刻与放行人成对：只写时刻不写人会让「谁放的」这个证据说不清，反过来也一样。
    CONSTRAINT alphafrog_agent_run_release_point_open_check
        CHECK ((opened_at IS NULL AND opened_by IS NULL)
            OR (opened_at IS NOT NULL AND opened_by IS NOT NULL))
);

-- 放行判断（Agent 侧唯一那条读语句）按「Run + 放行点」精确命中，未放行的行不进索引。
CREATE INDEX IF NOT EXISTS idx_agent_run_release_point_opened
    ON alphafrog_agent_run_release_point(run_id, release_key)
    WHERE opened_at IS NOT NULL;

COMMENT ON TABLE alphafrog_agent_run_release_point IS
    '阶段三验收夹具的放行点：被策略压住的成员结果，等这里标成已放行才收尾。';
COMMENT ON COLUMN alphafrog_agent_run_release_point.release_key IS
    '夹具放行策略里点名的放行点名字（holdUntilPoint 的值）。';
COMMENT ON COLUMN alphafrog_agent_run_release_point.opened_at IS
    '放行时刻；为空表示还没放行，被压住的成员继续等。';
COMMENT ON COLUMN alphafrog_agent_run_release_point.opened_by IS
    '放行动作是谁做的（控制面标识）；与放行时刻成对。';
