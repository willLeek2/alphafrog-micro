-- 阶段三「完整 DAG、容量保护与恢复风暴」的数据合同。
--
-- 这份脚本只增加列、表、约束和索引，不删除任何存量数据。它给新的调度器版本留出落库位置：
-- 等待组、等待成员、持久恢复通知、Run 协调资格、全局容量暂停状态与跨图轮转轮次。
--
-- 旧调度器版本（LEGACY）继续只读 Run 级工具锚点；DUAL_POOL_V1 继续只读单工具锚点；
-- 新版本（DUAL_POOL_V2）的 LINEAR 和 DAG 都读等待组与等待成员。两条路径不会同时更新
-- 同一个节点的等待事实：等待事实由本版本新增的三张表独家拥有。

-- 1. Run 与应用两处调度器版本约束扩到新版本。宽化而不是替换：旧取值原样保留，
--    已经写进库里的 LEGACY / DUAL_POOL_V1 行继续有效。
ALTER TABLE IF EXISTS alphafrog_agent_run
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_scheduler_version_check;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD CONSTRAINT alphafrog_agent_run_scheduler_version_check
        CHECK (scheduler_version IN ('LEGACY', 'DUAL_POOL_V1', 'DUAL_POOL_V2'));

ALTER TABLE IF EXISTS alphafrog_agent_run_work_item
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_work_item_scheduler_version_check;

ALTER TABLE IF EXISTS alphafrog_agent_run_work_item
    ADD CONSTRAINT alphafrog_agent_run_work_item_scheduler_version_check
        CHECK (scheduler_version IN ('LEGACY', 'DUAL_POOL_V1', 'DUAL_POOL_V2'));

-- 2. Run 创建的幂等身份：同一个用户下同一个幂等键只能对应一条 Run。
--
-- 请求摘要是创建请求内容的摘要：同一个键配同一个摘要读回原来那条 Run，同一个键配不同摘要
-- 直接拒绝，两种情况都不新建第二条 Run。两列要么都有值要么都为空，靠配对约束保证。
--
-- 存量 Run 不回填：它们只把键写在 ext 里，当年没有摘要，凭现在的数据算不出正确的摘要，
-- 硬填一个值会让「键相同摘要不同必须拒绝」这条判断失效。约束只对新 Run 生效。
ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS request_digest VARCHAR(64);

ALTER TABLE IF EXISTS alphafrog_agent_run
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_idempotency_pair_check;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD CONSTRAINT alphafrog_agent_run_idempotency_pair_check
        CHECK ((idempotency_key IS NULL) = (request_digest IS NULL));

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_run_user_idempotency_key
    ON alphafrog_agent_run(user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN alphafrog_agent_run.idempotency_key IS
    '创建请求携带的幂等键；与 request_digest 成对出现，同一用户下唯一。';
COMMENT ON COLUMN alphafrog_agent_run.request_digest IS
    '创建请求内容的摘要；与同一个幂等键重复提交时必须一致，不一致直接拒绝。';

-- 3. 工作项记录「进入可运行状态的时间」：排队等待计时的起点。
--
-- 首次可运行的段落在插入时写入；由等待转回可恢复的段落在条件更新里改写为那一刻。
-- 行的可见时间（next_visible_at）会随退避不断推后，所以不能用它替代这一列。
ALTER TABLE IF EXISTS alphafrog_agent_run_work_item
    ADD COLUMN IF NOT EXISTS runnable_since TIMESTAMPTZ;

-- 存量行取「可见时间」与「当前时间」里更早的那个：退避中的行可见时间在未来，
-- 直接抄过去会得到一个将来的起点，排队时长算出来是负数。
--
-- 存量行的这个值是近似值：当年的排队起点没有单独记录，回填只能保证「不晚于迁移时刻」。
-- 新写入的行都是精确值，排队时长统计要看这一段就从迁移之后算起。
UPDATE alphafrog_agent_run_work_item
   SET runnable_since = LEAST(next_visible_at, CURRENT_TIMESTAMP)
 WHERE runnable_since IS NULL;

ALTER TABLE IF EXISTS alphafrog_agent_run_work_item
    ALTER COLUMN runnable_since SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE IF EXISTS alphafrog_agent_run_work_item
    ALTER COLUMN runnable_since SET NOT NULL;

-- 按 Run 分组的到期扫描：补扫先在每张运行图内按最早可见时间排序，再跨图轮转。
CREATE INDEX IF NOT EXISTS idx_agent_run_work_item_run_due
    ON alphafrog_agent_run_work_item(scheduler_version, run_id, next_visible_at)
    WHERE state IN ('RUNNABLE', 'RESUMABLE');

COMMENT ON COLUMN alphafrog_agent_run_work_item.runnable_since IS
    '这一段落进入可运行或可恢复状态的时间；退避只推后 next_visible_at，不改这一列。';

-- 4. 工作项记录「这次派发没成功的原因」：内存提醒队列满属于节点派发这一层的事实，
--    与 Run 协调层的延期原因分开记，不混进 Run 那一行。
--
-- 派发成功后清空（成功入队、被领取、被交接都会清），所以它只表示最近一次派发失败。
ALTER TABLE IF EXISTS alphafrog_agent_run_work_item
    ADD COLUMN IF NOT EXISTS dispatch_defer_reason VARCHAR(64);

ALTER TABLE IF EXISTS alphafrog_agent_run_work_item
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_work_item_dispatch_defer_reason_check;

ALTER TABLE IF EXISTS alphafrog_agent_run_work_item
    ADD CONSTRAINT alphafrog_agent_run_work_item_dispatch_defer_reason_check
        CHECK (dispatch_defer_reason IS NULL OR dispatch_defer_reason IN ('HINT_QUEUE_FULL'));

COMMENT ON COLUMN alphafrog_agent_run_work_item.dispatch_defer_reason IS
    '最近一次节点派发没成功的原因；派发成功（入队、领取、交接）时清空。';

-- 5. Run 协调资格：一个 Run 一行，保存持久延期原因、下次可见时间与两个轮次空间的轮转位置。
--
-- 阶段三直接使用已有的 Run 身份分组，不另外造一份分组身份；这一行只承载调度侧的资格事实。
-- plan_generation 跟随 Run 主记录：还没有计划时是 -1，计划建好或代际推进时一起改写。
--
-- 轮转位置分两组：Run 协调与节点派发各走一个轮次空间，各记各的进度。合成一组会让两个
-- 调度器互相改写对方的进度，公平性就无从算起。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_coordination (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    scheduler_version VARCHAR(32) NOT NULL,
    plan_generation INT NOT NULL DEFAULT -1,
    defer_reason VARCHAR(64),
    next_visible_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    coordination_served_round BIGINT NOT NULL DEFAULT 0,
    coordination_missed_rounds INT NOT NULL DEFAULT 0,
    dispatch_served_round BIGINT NOT NULL DEFAULT 0,
    dispatch_missed_rounds INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT alphafrog_agent_run_coordination_scheduler_version_check
        CHECK (scheduler_version = 'DUAL_POOL_V2'),
    CONSTRAINT alphafrog_agent_run_coordination_plan_generation_check
        CHECK (plan_generation >= -1),
    CONSTRAINT alphafrog_agent_run_coordination_defer_reason_check
        CHECK (defer_reason IS NULL OR defer_reason IN
               ('RUN_COORDINATION_PERMIT_FULL', 'PER_ROUND_NEW_NODE_LIMIT',
                'PER_RUN_UNFINISHED_LIMIT', 'GLOBAL_UNFINISHED_PAUSED')),
    CONSTRAINT alphafrog_agent_run_coordination_round_check
        CHECK (coordination_served_round >= 0 AND coordination_missed_rounds >= 0
               AND dispatch_served_round >= 0 AND dispatch_missed_rounds >= 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_run_coordination_due
    ON alphafrog_agent_run_coordination(scheduler_version, next_visible_at, run_id);

CREATE INDEX IF NOT EXISTS idx_agent_run_coordination_dispatch
    ON alphafrog_agent_run_coordination(scheduler_version, dispatch_served_round, run_id);

COMMENT ON TABLE alphafrog_agent_run_coordination IS
    'Run 协调资格：延期原因、下次可见时间与两个轮次空间的轮转位置；一个 Run 一行，直接使用 Run 身份。';
COMMENT ON COLUMN alphafrog_agent_run_coordination.defer_reason IS
    '最近一次 Run 协调没能推进这个 Run 的原因；成功推进时清空。节点派发失败的原因记在工作项上。';
COMMENT ON COLUMN alphafrog_agent_run_coordination.coordination_served_round IS
    '这个 Run 最近一次被 Run 协调服务时的全局轮次号；补扫按它升序取，没有图连续错过两个完整轮次。';
COMMENT ON COLUMN alphafrog_agent_run_coordination.dispatch_served_round IS
    '这个 Run 最近一次被节点派发服务时的全局轮次号；与 Run 协调各记一组，互不覆盖。';

-- 6. 等待组：一个节点分段的一次模型回合里发出的整组工具请求。
--
-- 身份是节点五字段身份加模型回合；同一身份只能有一行，靠唯一约束保证。
-- 这一行由复合外键绑在它所属的工作项上：五个身份字段就是节点工作项的身份，
-- 另一条复合外键绑在这次挂起之后继续执行的那一段上。两段都必须在同一事务里存在，
-- 所以外键把「组属于哪一段、接下来是哪一段」写成数据库可检查的事实。
--
-- 下一分段序号只能是当前序号加一：这样「下一段」不需要另存一份身份就能推出来，
-- 也不允许跳号留空。版本列只允许 DUAL_POOL_V2：等待事实是新调度器版本独家拥有的。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_wait_group (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    plan_generation INT NOT NULL,
    node_id VARCHAR(256) NOT NULL,
    node_attempt INT NOT NULL DEFAULT 0,
    segment_sequence INT NOT NULL DEFAULT 0,
    model_turn INT NOT NULL,
    scheduler_version VARCHAR(32) NOT NULL,
    next_segment_sequence INT NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'WAITING',
    expected_members INT NOT NULL,
    completed_members INT NOT NULL DEFAULT 0,
    recovery_generation INT NOT NULL DEFAULT 0,
    ready_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT alphafrog_agent_run_wait_group_identity_key
        UNIQUE (run_id, plan_generation, node_id, node_attempt, segment_sequence, model_turn),
    CONSTRAINT alphafrog_agent_run_wait_group_uuid_run_key
        UNIQUE (id, run_id),
    CONSTRAINT alphafrog_agent_run_wait_group_state_check
        CHECK (state IN ('WAITING', 'READY', 'RESUMED', 'CANCELED')),
    CONSTRAINT alphafrog_agent_run_wait_group_scheduler_version_check
        CHECK (scheduler_version = 'DUAL_POOL_V2'),
    CONSTRAINT alphafrog_agent_run_wait_group_counter_check
        CHECK (plan_generation >= 0 AND node_attempt >= 0 AND segment_sequence >= 0
               AND model_turn >= 0 AND next_segment_sequence = segment_sequence + 1
               AND completed_members >= 0 AND completed_members <= expected_members
               AND recovery_generation >= 0),
    -- 成员数量上限由配置决定（起始值 16）；这里是防止脏数据的粗界，不是生效上限。
    CONSTRAINT alphafrog_agent_run_wait_group_member_bound_check
        CHECK (expected_members BETWEEN 1 AND 128),
    -- 齐备与已交接都要求「成员真的都结束了」且「齐备时刻有值」：交接之后完整性事实必须留着，
    -- 不能靠状态改写把它抹掉。
    CONSTRAINT alphafrog_agent_run_wait_group_ready_check
        CHECK (state NOT IN ('READY', 'RESUMED')
               OR (completed_members = expected_members AND ready_at IS NOT NULL)),
    CONSTRAINT alphafrog_agent_run_wait_group_segment_fk
        FOREIGN KEY (run_id, plan_generation, node_id, node_attempt, segment_sequence)
        REFERENCES alphafrog_agent_run_work_item(run_id, plan_generation, node_id, node_attempt, segment_sequence)
        ON DELETE CASCADE,
    CONSTRAINT alphafrog_agent_run_wait_group_next_segment_fk
        FOREIGN KEY (run_id, plan_generation, node_id, node_attempt, next_segment_sequence)
        REFERENCES alphafrog_agent_run_work_item(run_id, plan_generation, node_id, node_attempt, segment_sequence)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_agent_run_wait_group_run_state
    ON alphafrog_agent_run_wait_group(run_id, state);

CREATE INDEX IF NOT EXISTS idx_agent_run_wait_group_open
    ON alphafrog_agent_run_wait_group(created_at)
    WHERE state IN ('WAITING', 'READY');

COMMENT ON TABLE alphafrog_agent_run_wait_group IS
    '等待组：一次模型回合里并列发出的整组工具请求；身份是节点五字段身份加模型回合。';
COMMENT ON COLUMN alphafrog_agent_run_wait_group.next_segment_sequence IS
    '挂起之后继续执行的分段序号，只能是当前序号加一，且在同一事务里被创建为 WAITING。';
COMMENT ON COLUMN alphafrog_agent_run_wait_group.recovery_generation IS
    '这个组已经发出过的恢复资格次数；每次发出都写一条同代际的持久恢复通知。';
COMMENT ON COLUMN alphafrog_agent_run_wait_group.completed_members IS
    '已经真正结束的成员数，只数成功与失败；被取消和迟到不算结束，它们让整组走取消。';

-- 7. 等待成员：组里的一次工具调用。
--
-- 成员身份优先用模型给出的规范化工具调用身份；模型没给身份时，按「组 + 原始序号」
-- 生成稳定身份并在整组事务里立即保存，重试不得再生成随机值。不同节点、连续等待组
-- 出现相同的原始工具调用身份不算冲突，因为唯一范围限定在同一个组内。
--
-- 异步外部操作身份全局唯一：一个外部作业只能挂到一个成员上，跨 Run 也一样。范围放宽到全局
-- 是因为外部作业身份由外部系统给出，同一个作业被两条 Run 同时认领会让两次恢复抢同一份结果。
-- 成员行与通知行都靠 (group_id, run_id) 复合外键绑定它所属的组，run_id 抄错就写不进去。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_wait_member (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    member_seq INT NOT NULL,
    member_identity VARCHAR(256) NOT NULL,
    tool_call_id VARCHAR(256),
    tool_name VARCHAR(128) NOT NULL,
    external_operation_id VARCHAR(256),
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    result_ref_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    next_poll_at TIMESTAMPTZ,
    poll_count INT NOT NULL DEFAULT 0,
    backoff_step INT NOT NULL DEFAULT 0,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT alphafrog_agent_run_wait_member_seq_key
        UNIQUE (group_id, member_seq),
    CONSTRAINT alphafrog_agent_run_wait_member_identity_key
        UNIQUE (group_id, member_identity),
    CONSTRAINT alphafrog_agent_run_wait_member_state_check
        CHECK (state IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED', 'LATE')),
    CONSTRAINT alphafrog_agent_run_wait_member_counter_check
        CHECK (member_seq >= 0 AND poll_count >= 0 AND backoff_step >= 0),
    CONSTRAINT alphafrog_agent_run_wait_member_identity_not_blank_check
        CHECK (length(btrim(member_identity)) > 0 AND length(btrim(tool_name)) > 0),
    -- 结束时刻与终态一一对应：终态必须有结束时刻，非终态必须没有，两边都不能漏。
    CONSTRAINT alphafrog_agent_run_wait_member_finished_check
        CHECK ((state IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'LATE')) = (finished_at IS NOT NULL)),
    CONSTRAINT alphafrog_agent_run_wait_member_group_fk
        FOREIGN KEY (group_id, run_id) REFERENCES alphafrog_agent_run_wait_group(id, run_id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_run_wait_member_operation
    ON alphafrog_agent_run_wait_member(external_operation_id)
    WHERE external_operation_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_run_wait_member_due
    ON alphafrog_agent_run_wait_member(next_poll_at)
    WHERE state = 'RUNNING' AND next_poll_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_run_wait_member_run_state
    ON alphafrog_agent_run_wait_member(run_id, state);

COMMENT ON TABLE alphafrog_agent_run_wait_member IS
    '等待成员：组里的一次工具调用；结果引用、下次查询时间、轮询次数与退避都在本行上。';
COMMENT ON COLUMN alphafrog_agent_run_wait_member.member_identity IS
    '稳定成员身份：模型给了工具调用身份就用它，没给就按「组 + 原始序号」算出定长摘要并保存，重试不改。';
COMMENT ON COLUMN alphafrog_agent_run_wait_member.external_operation_id IS
    '异步外部操作身份；全局唯一，重投同一作业时按它定位到同一个成员。';
COMMENT ON COLUMN alphafrog_agent_run_wait_member.result_ref_json IS
    '结果引用：指向持久结果载荷的位置，不在这里放整份结果正文。';

-- 8. 持久恢复通知：一个等待组每一次发出恢复资格写一条。
--
-- 消费是条件更新（WAITING 到 CONSUMED），同一代际只有一次消费机会；这也是「最后一个成员
-- 只产生一次恢复资格」在库里的落点：组的 WAITING 到 READY 条件更新加上本表的唯一约束。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_recovery_notification (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    recovery_generation INT NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'WAITING',
    next_visible_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed_at TIMESTAMPTZ,
    consumed_by VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT alphafrog_agent_run_recovery_notification_generation_key
        UNIQUE (group_id, recovery_generation),
    CONSTRAINT alphafrog_agent_run_recovery_notification_state_check
        CHECK (state IN ('WAITING', 'CONSUMED', 'CANCELED')),
    CONSTRAINT alphafrog_agent_run_recovery_notification_generation_check
        CHECK (recovery_generation > 0),
    -- 消费时刻与已消费状态一一对应：被取消的通知不能带着消费时刻。
    CONSTRAINT alphafrog_agent_run_recovery_notification_consumed_check
        CHECK ((state = 'CONSUMED') = (consumed_at IS NOT NULL)),
    CONSTRAINT alphafrog_agent_run_recovery_notification_group_fk
        FOREIGN KEY (group_id, run_id) REFERENCES alphafrog_agent_run_wait_group(id, run_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_agent_run_recovery_notification_due
    ON alphafrog_agent_run_recovery_notification(next_visible_at, id)
    WHERE state = 'WAITING';

CREATE INDEX IF NOT EXISTS idx_agent_run_recovery_notification_run
    ON alphafrog_agent_run_recovery_notification(run_id, state);

COMMENT ON TABLE alphafrog_agent_run_recovery_notification IS
    '持久恢复通知：等待组每次发出恢复资格一条；消费是条件更新，同一代际只消费一次。';
COMMENT ON COLUMN alphafrog_agent_run_recovery_notification.consumed_by IS
    '消费这条通知的恢复分发器实例标识，只用于诊断。';

-- 9. 全局新增暂停状态：高低水位滞回必须跨重启有效。
--
-- 水位值是最近一次判定时用的配置值，留作审计；判定口径以配置为准。暂停标记是持久权威：
-- 重启后读到「暂停」就不能因为当前数量低于高水位而提前恢复，必须等回落到低水位。
CREATE TABLE IF NOT EXISTS alphafrog_agent_scheduler_capacity_state (
    scope_key VARCHAR(32) PRIMARY KEY,
    unfinished_count INT NOT NULL DEFAULT 0,
    add_paused BOOLEAN NOT NULL DEFAULT FALSE,
    paused_since TIMESTAMPTZ,
    high_watermark INT NOT NULL,
    low_watermark INT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT alphafrog_agent_scheduler_capacity_state_counter_check
        CHECK (unfinished_count >= 0 AND high_watermark >= 0
               AND low_watermark >= 0 AND low_watermark <= high_watermark),
    -- 暂停标记与暂停起始时间一一对应：暂停必须有起始时刻，不暂停必须没有。
    CONSTRAINT alphafrog_agent_scheduler_capacity_state_paused_check
        CHECK (add_paused = (paused_since IS NOT NULL))
);

INSERT INTO alphafrog_agent_scheduler_capacity_state
    (scope_key, unfinished_count, add_paused, paused_since, high_watermark, low_watermark)
VALUES ('GLOBAL', 0, FALSE, NULL, 128, 96)
ON CONFLICT (scope_key) DO NOTHING;

COMMENT ON TABLE alphafrog_agent_scheduler_capacity_state IS
    '全局新增暂停状态与水位：暂停标记是持久权威，水位值是最近一次判定所用的配置值。';
COMMENT ON COLUMN alphafrog_agent_scheduler_capacity_state.add_paused IS
    '是否暂停新增节点；为真时只回落到低水位才恢复，中途不允许凭低于高水位提前恢复。';

-- 10. 跨图轮转轮次：Run 协调与节点派发各一个计数器，按 Run 记录最近被服务的轮次号。
CREATE TABLE IF NOT EXISTS alphafrog_agent_scheduler_round (
    scope_key VARCHAR(32) PRIMARY KEY,
    round_number BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT alphafrog_agent_scheduler_round_counter_check CHECK (round_number >= 0)
);

INSERT INTO alphafrog_agent_scheduler_round (scope_key, round_number)
VALUES ('RUN_COORDINATION', 0), ('NODE_DISPATCH', 0)
ON CONFLICT (scope_key) DO NOTHING;

COMMENT ON TABLE alphafrog_agent_scheduler_round IS
    '跨图轮转轮次：每经过一个派发轮次加一，与 Run 协调资格里的最近服务轮次配合保证公平。';
