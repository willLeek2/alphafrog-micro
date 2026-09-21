-- 共享候选的两处配套：多一个延期原因，加一个与全局排序对齐的索引。
--
-- 一、延期原因补一个「这条 Run 的服务所有权不在我手上」。
--
-- 候选从「只有双池家族」变成三个版本共用之后，旧版本的行也会被选出来。共享分发器能不能动它，
-- 取决于它有没有那条 Run 的服务所有权：所有权在别的进程手上（或者刚接手就被拒）时，这一轮不接手，
-- 但要把它按退避推后——不然它会一直停在候选页首，页数一满，排在它后面的双池 Run 永远看不见。
-- 这不是「容量挡住的」延期，也不是「这个 Run 自己的活没干完」，所以不能借现有的四个取值。
ALTER TABLE IF EXISTS alphafrog_agent_run_coordination
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_coordination_defer_reason_check;

ALTER TABLE IF EXISTS alphafrog_agent_run_coordination
    ADD CONSTRAINT alphafrog_agent_run_coordination_defer_reason_check
        CHECK (defer_reason IS NULL OR defer_reason IN
               ('RUN_COORDINATION_PERMIT_FULL', 'PER_ROUND_NEW_NODE_LIMIT',
                'PER_RUN_UNFINISHED_LIMIT', 'GLOBAL_UNFINISHED_PAUSED',
                'SERVICE_OWNERSHIP_ELSEWHERE'));

-- 二、与全局排序对齐的索引。
--
-- 007 建的两条索引都以版本打头（当时候选按版本分家）。现在三条版本共用一份排序——先比最近被服务的
-- 轮次、再比下次可见时间、最后比编号——以版本打头的索引帮不上这个顺序，扫描还得自己排一遍。
-- 这条按排序的前两列建，取出到期的那些之后顺序基本就绪；版本不再出现在索引里，它只用于取出之后的
-- 分发，不参与筛选。
CREATE INDEX IF NOT EXISTS idx_agent_run_coordination_shared_due
    ON alphafrog_agent_run_coordination(next_visible_at, coordination_served_round, run_id);
