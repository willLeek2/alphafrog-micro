-- 13. 恢复通知的关闭态：不可能再被服务的通知要有一个持久终态。
--
-- 背景：恢复通知只有「等待、已取走、随组取消」三种状态。一条永远不可能被服务的通知（Run 已经终态、
-- 计划代际被推进、下一段工作项已经不在等待态）在旧状态机里只能一直保持「等待」，靠退避推到封顶之后
-- 每隔一个固定间隔回来一次，永久占用固定扫描名额，把真正能恢复的通知挤在后面。
--
-- 这里加一个「已关闭」终态与一句原因。关闭只允许从「等待」改过来（语句里是条件更新），已经取走或
-- 随组取消的通知不会被改。原因是一段有界的短文本，用于区分「不可归属」「Run 终态收口」「计划或
-- 控制版本失效」「下一段已经不在等待态」这几类，事后能查。
--
-- 代价说明：这一步是普通 DDL。迁移工具把每个脚本放在一个事务里跑，加约束会取排他锁并全表校验一次；
-- 这张表只存等待组的恢复资格、行数量级跟等待组同量级，代价可以接受，但整表校验期间这张表上的写会被挡住。

ALTER TABLE alphafrog_agent_run_recovery_notification
    ADD COLUMN IF NOT EXISTS close_reason VARCHAR(64);

ALTER TABLE alphafrog_agent_run_recovery_notification
    ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ;

ALTER TABLE alphafrog_agent_run_recovery_notification
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_recovery_notification_state_check;

ALTER TABLE alphafrog_agent_run_recovery_notification
    ADD CONSTRAINT alphafrog_agent_run_recovery_notification_state_check
        CHECK (state IN ('WAITING', 'CONSUMED', 'CANCELED', 'CLOSED'));

-- 关闭时刻、关闭原因与关闭状态一一对应：关闭必须有时刻和原因，没关闭的不能带这两样。
ALTER TABLE alphafrog_agent_run_recovery_notification
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_recovery_notification_closed_check;

ALTER TABLE alphafrog_agent_run_recovery_notification
    ADD CONSTRAINT alphafrog_agent_run_recovery_notification_closed_check
        CHECK ((state = 'CLOSED') = (closed_at IS NOT NULL)
               AND (state = 'CLOSED'
                    OR (close_reason IS NULL))
               AND (state <> 'CLOSED'
                    OR (close_reason IS NOT NULL AND length(btrim(close_reason)) > 0)));

COMMENT ON COLUMN alphafrog_agent_run_recovery_notification.close_reason IS
    '通知被关闭的原因（短文本）：不可归属、Run 终态收口、计划或控制版本失效、下一段已不在等待态等。';
COMMENT ON COLUMN alphafrog_agent_run_recovery_notification.closed_at IS
    '通知被关闭的时刻；只有关闭态才有值。';
