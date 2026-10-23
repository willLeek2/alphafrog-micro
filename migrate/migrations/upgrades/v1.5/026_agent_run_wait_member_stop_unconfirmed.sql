-- 外部作业停止的负结果统一为“未确认”。先解除旧约束，再迁移旧行，最后换上新约束；
-- 顺序不能调换，否则更新旧行时会被旧约束拒绝。
ALTER TABLE alphafrog_agent_run_wait_member_stop
    DROP CONSTRAINT IF EXISTS wait_member_stop_state_check;

UPDATE alphafrog_agent_run_wait_member_stop
   SET state = 'UNCONFIRMED',
       last_error = COALESCE(last_error, 'blocked_proof'),
       updated_at = CURRENT_TIMESTAMP
 WHERE state = 'BLOCKED_PROOF';

ALTER TABLE alphafrog_agent_run_wait_member_stop
    ADD CONSTRAINT wait_member_stop_state_check
        CHECK (state IN ('PENDING', 'CLAIMED', 'UNCONFIRMED', 'CONFIRMED'));
