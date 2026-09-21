-- 阶段三后台派发的证明：等待成员进入执行中时，把它那一刻的外部作业事实一并存下来。
--
-- 为什么存在成员行上：同一轮模型回复可以并列发出多个 executePython，而一个 Run 只有一列
-- tool_job_anchor_json，装不下多个外部作业。新调度器版本按成员保存外部作业事实，
-- 旧版本继续只读 Run 级锚点，两条路径不会同时更新同一个节点的等待事实。
--
-- 这些事实在派发那一刻已经全部确定，晚一步写就会在「任务已经建出来、进程紧接着退出」的窗口里丢掉：
-- canonical 请求规格（用来核对任务身份）、预估值与名额预留（终态信封、容量释放与用量结算都要用）、
-- 后台任务编号（回查任务用）。所以它和成员状态一起，在成员从「已保存待派发」进入「已派发执行中」
-- 的那一句里写入。
ALTER TABLE IF EXISTS alphafrog_agent_run_wait_member
    ADD COLUMN IF NOT EXISTS dispatch_proof_json JSONB;

COMMENT ON COLUMN alphafrog_agent_run_wait_member.dispatch_proof_json IS
    '后台派发证明：canonical 请求规格、预估值、名额预留与后台任务编号。NULL 表示这个成员没有转后台。';
