-- 接收事实修补的扫描索引：投射修补按事件流自己的保留期反复回看「哪些接收事实还没进事件流」。
--
-- 事件表按 Run 一直长、历史不清，而这条扫描只关心一种事件。没有索引时，每半分钟的一轮修补都要把
-- 整张事件表翻一遍，表越大越贵；做成分区索引（只含 RUN_RECEIVED，每个 Run 一行）之后，修补的两条
-- 查询——最新一页与从游标往后一页——都能直接走它，代价只跟「保留期内创建了多少个 Run」有关，
-- 与事件表的历史总量无关。
--
-- 索引列顺序与两条查询的排序一致（时间在前、编号在后）：同一毫秒落下的多行靠编号才排得稳定，
-- 正着扫是从旧到新翻页，反着扫就是最新一页。
CREATE INDEX IF NOT EXISTS idx_agent_run_event_received_created
    ON alphafrog_agent_run_event (created_at, id)
    WHERE event_type = 'RUN_RECEIVED';
