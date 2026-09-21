package world.willfrog.agent.platform.mapper;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.coordination.RunCoordinationDeferReason;
import world.willfrog.agent.platform.wait.RecoveryNotificationState;
import world.willfrog.agent.platform.wait.WaitGroupState;
import world.willfrog.agent.platform.wait.WaitMemberState;
import world.willfrog.agent.platform.workitem.NodeDispatchDeferReason;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段三数据合同在库里长什么样：等待组、等待成员、持久恢复通知、Run 协调资格、全局容量状态与轮转轮次。
 *
 * <p>这份测试不连数据库，只读迁移脚本，盯四件事：Java 枚举与库里约束的取值必须一致（漂移会让写入在
 * 生产才失败）；关键的「只结束一次」靠的唯一约束必须真的在（条件更新替代不了唯一约束）；等待链的归属
 * 关系必须写成数据库能检查的外键，而不是只写在注释里；这份脚本只能加东西，不能删旧数据。</p>
 */
class Stage3WaitGroupMigrationTest {

    /** 阶段三数据合同那一份脚本：按内容定位，不写死版本目录与文件名。 */
    private final String script = readScript();

    private static String readScript() {
        for (String candidate : MigrationScripts.allInOrder()) {
            if (candidate.contains("CREATE TABLE IF NOT EXISTS alphafrog_agent_run_wait_group")) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "没有任何迁移脚本建 alphafrog_agent_run_wait_group：阶段三数据合同必须跟着代码一起在仓库里");
    }

    // ===== 只能加，不能删 =====

    @Test
    void scriptOnlyAddsStructuresAndNeverDeletesData() {
        String upper = script.toUpperCase();
        assertThat(upper).as("不允许删表").doesNotContain("DROP TABLE");
        assertThat(upper).as("不允许删列").doesNotContain("DROP COLUMN");
        assertThat(upper).as("不允许清表").doesNotContain("TRUNCATE");
        assertThat(upper).as("不允许删数据").doesNotContain("DELETE FROM");
    }

    @Test
    void everyDroppedConstraintIsImmediatelyWidened() {
        List<String> dropped = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (String constraint : constraintNames(script)) {
            if (script.contains("DROP CONSTRAINT IF EXISTS " + constraint)) {
                dropped.add(constraint);
            }
            if (script.contains("ADD CONSTRAINT " + constraint)) {
                added.add(constraint);
            }
        }
        assertThat(dropped).as("脚本里确实宽化了约束").isNotEmpty();
        for (String constraint : dropped) {
            assertThat(added)
                    .as("删掉的约束必须同名加回来，否则约束会凭空消失：" + constraint)
                    .contains(constraint);
        }
    }

    @Test
    void everyStructureIsCreatedIdempotently() {
        for (String table : List.of(
                "alphafrog_agent_run_coordination",
                "alphafrog_agent_run_wait_group",
                "alphafrog_agent_run_wait_member",
                "alphafrog_agent_run_recovery_notification",
                "alphafrog_agent_scheduler_capacity_state",
                "alphafrog_agent_scheduler_round")) {
            assertThat(script).as("建表要幂等：" + table)
                    .contains("CREATE TABLE IF NOT EXISTS " + table);
        }
        assertThat(script).as("加列要幂等").contains("ADD COLUMN IF NOT EXISTS idempotency_key");
        assertThat(script).as("加列要幂等").contains("ADD COLUMN IF NOT EXISTS request_digest");
        assertThat(script).as("加列要幂等").contains("ADD COLUMN IF NOT EXISTS runnable_since");
        assertThat(script).as("加列要幂等").contains("ADD COLUMN IF NOT EXISTS dispatch_defer_reason");
        assertThat(script).as("加索引要幂等").contains("INDEX IF NOT EXISTS");
    }

    /** 迁移脚本是仓库产物，不带内部任务编号，免得把工单编号带进生产库的约束名与注释里。 */
    @Test
    void scriptDoesNotCarryInternalTaskNumbers() {
        assertThat(script).as("迁移脚本里不写内部任务编号").doesNotContain("01318");
    }

    // ===== 身份唯一约束 =====

    @Test
    void waitGroupIdentityIsFiveFieldsPlusModelTurn() {
        assertThat(normalize(script)).as("等待组身份 = 节点五字段身份 + 模型回合")
                .contains("UNIQUE (run_id, plan_generation, node_id, node_attempt, "
                        + "segment_sequence, model_turn)");
    }

    @Test
    void waitMemberCarriesSeqAndIdentityUniqueness() {
        assertThat(normalize(script))
                .as("成员原始序号在组内唯一")
                .contains("UNIQUE (group_id, member_seq)");
        assertThat(normalize(script))
                .as("成员稳定身份在组内唯一；跨组和跨段复用同一原始调用身份不算冲突")
                .contains("UNIQUE (group_id, member_identity)");
    }

    @Test
    void waitMemberExternalOperationIdentityIsGloballyUnique() {
        assertThat(normalize(script))
                .as("异步外部操作身份全局唯一：一个外部作业只能挂到一个成员上，跨 Run 也不行")
                .contains("ON alphafrog_agent_run_wait_member(external_operation_id)")
                .contains("WHERE external_operation_id IS NOT NULL");
        assertThat(normalize(script))
                .as("唯一范围不能收回到单个 Run 内，否则两条 Run 会抢同一份外部结果")
                .doesNotContain("ON alphafrog_agent_run_wait_member(run_id, external_operation_id)");
    }

    @Test
    void recoveryNotificationIsOnePerGroupPerGeneration() {
        assertThat(normalize(script))
                .as("同一个组同一恢复代际只能有一条通知：最后成员只产生一次恢复资格")
                .contains("UNIQUE (group_id, recovery_generation)");
    }

    @Test
    void runCreationIdempotencyIsUniquePerUser() {
        String normalized = normalize(script);
        assertThat(normalized).as("同一用户下幂等键唯一")
                .contains("ON alphafrog_agent_run(user_id, idempotency_key)")
                .contains("WHERE idempotency_key IS NOT NULL");
        assertThat(normalized).as("键与摘要成对出现")
                .contains("CHECK ((idempotency_key IS NULL) = (request_digest IS NULL))");
    }

    // ===== 等待链的归属关系 =====

    @Test
    void waitGroupIsBoundToItsOwnSegmentAndToTheNextOne() {
        String normalized = normalize(script);
        assertThat(normalized)
                .as("组绑在它所属的工作项上：五个身份字段就是那条工作项的身份")
                .contains("FOREIGN KEY (run_id, plan_generation, node_id, node_attempt, segment_sequence) "
                        + "REFERENCES alphafrog_agent_run_work_item"
                        + "(run_id, plan_generation, node_id, node_attempt, segment_sequence)");
        assertThat(normalized)
                .as("组也绑在挂起之后继续执行的那一段上，下一段必须是一行真实的工作项")
                .contains("FOREIGN KEY (run_id, plan_generation, node_id, node_attempt, next_segment_sequence) "
                        + "REFERENCES alphafrog_agent_run_work_item"
                        + "(run_id, plan_generation, node_id, node_attempt, segment_sequence)");
    }

    @Test
    void waitChainRowsAreBoundToTheirGroupAndItsRun() {
        String normalized = normalize(script);
        assertThat(normalized).as("组上要有能被子行引用的 (id, run_id) 唯一约束")
                .contains("UNIQUE (id, run_id)");
        for (String child : List.of("alphafrog_agent_run_wait_member",
                "alphafrog_agent_run_recovery_notification")) {
            assertThat(normalized)
                    .as(child + " 的 run_id 必须等于它所属组的 run_id，抄错就写不进去")
                    .contains("FOREIGN KEY (group_id, run_id) "
                            + "REFERENCES alphafrog_agent_run_wait_group(id, run_id)");
        }
    }

    @Test
    void waitGroupFactsAreV2OnlyWhileRunCoordinationIsShared() {
        assertThat(normalize(script))
                .as("等待组只属于 DUAL_POOL_V2；老版本走各自的锚点，不写等待事实")
                .contains("CONSTRAINT alphafrog_agent_run_wait_group_scheduler_version_check "
                        + "CHECK (scheduler_version = 'DUAL_POOL_V2')");
        assertThat(MigrationScripts.constraintValues(script,
                "alphafrog_agent_run_coordination_scheduler_version_check"))
                .as("Run 协调资格是旧、新版本共用的入口：老 Run 也要能建出资格行参与公平轮转，"
                        + "这里不能跟着等待组一起收窄")
                .containsExactlyElementsOf(java.util.Arrays.stream(SchedulerVersion.values())
                        .map(Enum::name)
                        .toList());
    }

    // ===== Java 枚举与库里约束一致 =====

    @Test
    void waitGroupStateCheckMatchesEnum() {
        assertThat(MigrationScripts.constraintValues(
                "alphafrog_agent_run_wait_group_state_check"))
                .as("等待组状态取值应与 WaitGroupState 逐项一致")
                .containsExactlyElementsOf(WaitGroupState.allWireValues());
    }

    @Test
    void waitMemberStateCheckMatchesEnum() {
        assertThat(MigrationScripts.constraintValues(
                "alphafrog_agent_run_wait_member_state_check"))
                .as("等待成员状态取值应与 WaitMemberState 逐项一致")
                .containsExactlyElementsOf(WaitMemberState.allWireValues());
    }

    @Test
    void recoveryNotificationStateCheckMatchesEnum() {
        assertThat(MigrationScripts.constraintValues(
                "alphafrog_agent_run_recovery_notification_state_check"))
                .as("恢复通知状态取值应与 RecoveryNotificationState 逐项一致")
                .containsExactlyElementsOf(RecoveryNotificationState.allWireValues());
    }

    @Test
    void coordinationDeferReasonCheckMatchesEnum() {
        assertThat(MigrationScripts.constraintValues(
                "alphafrog_agent_run_coordination_defer_reason_check"))
                .as("协调延期原因应与 RunCoordinationDeferReason 逐项一致")
                .containsExactlyElementsOf(RunCoordinationDeferReason.allWireValues());
        assertThat(MigrationScripts.constraintValues(
                "alphafrog_agent_run_coordination_defer_reason_check"))
                .as("节点派发的失败原因不属于 Run 协调这一层")
                .doesNotContain("HINT_QUEUE_FULL");
    }

    @Test
    void workItemDispatchDeferReasonCheckMatchesEnum() {
        assertThat(MigrationScripts.constraintValues(
                "alphafrog_agent_run_work_item_dispatch_defer_reason_check"))
                .as("节点派发延期原因应与 NodeDispatchDeferReason 逐项一致")
                .containsExactlyElementsOf(NodeDispatchDeferReason.allWireValues());
    }

    // ===== 阶段三后补的三份脚本（真库验证在真库探针里，这里盯住不连库也看得出的形状） =====

    /**
     * 事件修补的索引只能普通建：迁移工具每个脚本都在一个事务里跑，并发建索引
     * （{@code CONCURRENTLY}）在事务块里会直接报错，整份脚本都落不下去。
     *
     * <p>代价要说清楚：普通建索引期间对这张表加写锁，建完为止。事件表只增不减，表越大这一步越久，
     * 大表上要留出维护窗口。</p>
     */
    @Test
    void theRepairIndexIsBuiltWithoutConcurrentBuild() {
        String script = MigrationScripts.lastContaining("idx_agent_run_event_received_created");
        assertThat(script.toUpperCase())
                .as("迁移工具一个脚本一个事务，并发建索引在事务块里跑不了")
                .doesNotContain("CONCURRENTLY");
        assertThat(script)
                .as("建的必须是那条偏索引：只收接收事实，按（时间，编号）排序")
                .contains("CREATE INDEX IF NOT EXISTS idx_agent_run_event_received_created")
                .contains("(created_at, id)")
                .contains("RUN_RECEIVED");
    }

    /** 服务所有权租约表：主键就是 Run，换主人时令牌必须往前走，过期时间有索引好查。 */
    @Test
    void theServiceLeaseTableCarriesItsOwnGuard() {
        String script = MigrationScripts.lastContaining("alphafrog_agent_run_service_lease");
        assertThat(script)
                .as("建表要幂等").contains("CREATE TABLE IF NOT EXISTS alphafrog_agent_run_service_lease");
        assertThat(script)
                .as("Run 是主键：一条 Run 在库里只能有一份所有权记录")
                .contains("run_id").contains("PRIMARY KEY");
        assertThat(script)
                .as("令牌与主人不能是空的：空值会让「换主人了没有」判断不了")
                .contains("fencing_token > 0")
                .contains("btrim(owner_instance_id)");
        assertThat(script)
                .as("按过期时间取可接手的那些，要有索引")
                .contains("idx_agent_run_service_lease_expiry");
    }

    /** 共享候选的配套索引按全局排序建：先比最近被服务的轮次，再比下次可见时间，最后比编号。 */
    @Test
    void theSharedCandidateIndexFollowsTheGlobalOrder() {
        String script = MigrationScripts.lastContaining("idx_agent_run_coordination_shared_due");
        assertThat(script)
                .as("候选的三个版本共用一份排序，索引要按这份排序的前两列建")
                .contains("idx_agent_run_coordination_shared_due")
                .contains("ON alphafrog_agent_run_coordination(next_visible_at, coordination_served_round, run_id)");
        assertThat(script)
                .as("延期原因多了一个「所有权不在我手上」，与枚举逐项一致（上面那条用例按最后一份定义比）")
                .contains("SERVICE_OWNERSHIP_ELSEWHERE");
    }

    // ===== 组与成员的状态不变量 =====

    @Test
    void waitGroupCarriesCounterAndReadyInvariants() {
        String normalized = normalize(script);
        assertThat(normalized)
                .as("完成数不能超过期望数，下一段只能紧挨着本段")
                .contains("next_segment_sequence = segment_sequence + 1")
                .contains("completed_members <= expected_members");
        assertThat(normalized)
                .as("齐备与已交接都必须完成数等于期望数并且有齐备时间")
                .contains("CHECK (state NOT IN ('READY', 'RESUMED') "
                        + "OR (completed_members = expected_members AND ready_at IS NOT NULL))");
    }

    @Test
    void waitMemberTerminalStatesMatchFinishTimeBothWays() {
        assertThat(normalize(script))
                .as("终态成员必须有结束时间，非终态成员必须没有，两边都不能漏")
                .contains("CHECK ((state IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'LATE')) "
                        + "= (finished_at IS NOT NULL))");
    }

    @Test
    void recoveryNotificationConsumeTimeMatchesState() {
        assertThat(normalize(script))
                .as("已消费与被取消的通知不能带着同一个消费时刻")
                .contains("CHECK ((state = 'CONSUMED') = (consumed_at IS NOT NULL))");
    }

    @Test
    void capacityStateSeedsGlobalRowAndKeepsWatermarksSane() {
        String normalized = normalize(script);
        assertThat(normalized).as("种子行必须存在，否则重启后读不到暂停状态")
                .contains("VALUES ('GLOBAL', 0, FALSE, NULL, 128, 96)")
                .contains("ON CONFLICT (scope_key) DO NOTHING");
        assertThat(normalized).as("低水位不能高于高水位")
                .contains("low_watermark <= high_watermark");
        assertThat(normalized).as("暂停标记与暂停起始时间一一对应，两个方向都要管住")
                .contains("CHECK (add_paused = (paused_since IS NOT NULL))");
    }

    @Test
    void coordinationKeepsTwoSeparateRoundPositions() {
        String normalized = normalize(script);
        assertThat(normalized)
                .as("Run 协调与节点派发各记一组轮转位置，互相不覆盖")
                .contains("coordination_served_round")
                .contains("coordination_missed_rounds")
                .contains("dispatch_served_round")
                .contains("dispatch_missed_rounds")
                .as("合成一组会让两个调度器互相改写进度")
                .doesNotContain("last_served_round");
        assertThat(normalized)
                .as("节点派发的轮转顺序也要能走索引")
                .contains("idx_agent_run_coordination_dispatch")
                .contains("ON alphafrog_agent_run_coordination"
                        + "(scheduler_version, dispatch_served_round, run_id)");
    }

    @Test
    void roundTableSeedsBothScopes() {
        assertThat(normalize(script))
                .contains("VALUES ('RUN_COORDINATION', 0), ('NODE_DISPATCH', 0)");
    }

    @Test
    void runnableSinceIsBackfilledNotIntoTheFutureAndScannedByRun() {
        String normalized = normalize(script);
        assertThat(normalized).as("存量行按可见时间回填，退避中的行取当前时间，不能回填出将来的起点")
                .contains("SET runnable_since = LEAST(next_visible_at, CURRENT_TIMESTAMP)")
                .contains("ALTER COLUMN runnable_since SET NOT NULL");
        assertThat(normalized).as("按 Run 分组的到期扫描要有索引")
                .contains("idx_agent_run_work_item_run_due")
                .contains("ON alphafrog_agent_run_work_item(scheduler_version, run_id, next_visible_at)")
                .contains("WHERE state IN ('RUNNABLE', 'RESUMABLE')");
    }

    private static List<String> constraintNames(String text) {
        List<String> names = new ArrayList<>();
        int index = 0;
        String marker = "CONSTRAINT ";
        while ((index = text.indexOf(marker, index)) >= 0) {
            int start = index + marker.length();
            int end = start;
            while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end))
                    || text.charAt(end) == '_')) {
                end++;
            }
            String name = text.substring(start, end);
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
            index = end;
        }
        return names;
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ");
    }
}
