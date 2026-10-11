-- 恢复通知的消费方标识：写进去就必须是有效值。
--
-- 为什么加这条：恢复通知是等待链唯一的一次放行资格，谁取走它必须查得到。应用入口已经要求
-- 消费方标识非空，数据库这一层也要挡住空白字符串，否则审计字段会出现「有消费时间、没有消费方」
-- 的记录，事后无法判断是谁放行的。
--
-- 两条判定合在一起：空值只能表示「还没被消费」；一旦填了值就不许是空白；反过来，
-- 状态写着已消费的必须留下消费方。
ALTER TABLE IF EXISTS alphafrog_agent_run_recovery_notification
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_recovery_notification_consumed_by_check;

ALTER TABLE IF EXISTS alphafrog_agent_run_recovery_notification
    ADD CONSTRAINT alphafrog_agent_run_recovery_notification_consumed_by_check
    CHECK ((consumed_by IS NULL OR btrim(consumed_by) <> '')
           AND (state <> 'CONSUMED' OR consumed_by IS NOT NULL));

COMMENT ON CONSTRAINT alphafrog_agent_run_recovery_notification_consumed_by_check
    ON alphafrog_agent_run_recovery_notification IS
    '消费方标识非空白，且已消费的通知必须留下消费方：恢复资格只有一次，谁取走的要查得到。';
