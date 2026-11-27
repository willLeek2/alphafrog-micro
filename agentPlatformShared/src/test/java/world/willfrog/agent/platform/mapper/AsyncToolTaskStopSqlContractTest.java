package world.willfrog.agent.platform.mapper;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取消停机主路径的 SQL 契约。
 *
 * <p>这些条件在单元测试里靠夹具模拟，夹具和真实语句曾经有两处分歧：停机入队不写到期时刻、
 * 停机收尾不写任务编号。两条都会让「取消 → 停机 → 收口」停在半路而测试仍然全绿。
 * 这里直接读映射文件，把真实语句里必须存在的条件钉住；语句在真实库上的执行仍由独立泳道验证。</p>
 */
class AsyncToolTaskStopSqlContractTest {

    private static final String WAIT_GROUP = statement("WaitGroupMapper.xml", "cancelChain");
    private static final String LATE_MEMBER = statement("WaitGroupMapper.xml", "reportLateMember");
    private static final String ENSURE_STOP = statement("WaitGroupMapper.xml", "ensureCanceledMemberStopTasks");
    private static final String SCAN_STOP = statement("AsyncToolTaskMapper.xml", "scanStopDue");
    private static final String FINISH_STOP = statement("AsyncToolTaskMapper.xml", "finishStop");
    private static final String COMPLETE_CANCELED = statement("AgentRunMapper.xml", "completeCanceledSqlWaitMember");
    private static final String WAIT_GROUP_FILE = wholeFile("WaitGroupMapper.xml");

    @Test
    void queuingAStopMakesItDueImmediately() {
        assertThat(WAIT_GROUP).as("取消链入队停机时必须同时写下一次扫描时刻")
                .contains("next_poll_at = CASE")
                .contains("THEN CURRENT_TIMESTAMP END");
        assertThat(LATE_MEMBER).as("迟到结果停链的兄弟成员同样要立刻到期")
                .contains("next_poll_at = CASE");
        assertThat(ENSURE_STOP).as("补扫停机任务也要立刻到期")
                .contains("next_poll_at = CURRENT_TIMESTAMP");
        assertThat(SCAN_STOP).as("到期时刻为空按立刻到期处理，停机意图不能因为少写一个时刻被跳过")
                .contains("next_poll_at IS NULL OR next_poll_at");
    }

    @Test
    void stopScanOnlyTakesTasksOfThisDeploymentGenerationAndLane() {
        assertThat(SCAN_STOP).as("停机扫描必须带部署标识、部署代际与泳道条件："
                        + "缺少任何一项，别的实例都会把待停任务领走，重试用尽后把停止状态写成已确认，"
                        + "真正归属的实例再也停不掉那个沙箱作业")
                .contains("r.deployment_id = #{deploymentId}")
                .contains("r.deployment_generation_id = #{deploymentGenerationId}")
                .contains("r.lane_tag IS NULL")
                .contains("r.lane_tag = #{laneTag}");
    }

    @Test
    void stopConfirmationWritesTheSandboxTaskId() {
        assertThat(FINISH_STOP).as("取消之后 attach 语句不再命中，收尾必须把任务编号写回任务行")
                .contains("task_id = COALESCE(NULLIF(#{taskId}::varchar, ''), task_id)");
    }

    @Test
    void readAdapterKeepsTheStoredProofShape() {
        assertThat(WAIT_GROUP_FILE).as("读适配不能递归删除空值键，否则按原文等值比对的两道守卫永远不成立")
                .doesNotContain("jsonb_strip_nulls");
    }

    @Test
    void schemaVersionIsOnlyCastWhenItIsNumeric() {
        assertThat(statement("WaitGroupMapper.xml", "recordMemberPreparing"))
                .contains("jsonb_typeof(");
        assertThat(statement("WaitGroupMapper.xml", "markMemberDispatched"))
                .contains("jsonb_typeof(");
    }

    @Test
    void canceledSqlMemberSettlementAcceptsTheNoTaskBranch() {
        assertThat(COMPLETE_CANCELED).as("沙箱查无此作业的收口分支：没有任务编号、干净收尾、不带终态凭证")
                .contains("t.task_id IS NULL")
                .contains("t.stop_last_error IS NULL")
                .contains("CAST(#{terminalReservationJson} AS jsonb) IS NULL");
    }

    private static String statement(String file, String id) {
        String text = wholeFile(file);
        int start = text.indexOf("id=\"" + id + "\"");
        assertThat(start).as(file + " 里没有语句 " + id).isGreaterThanOrEqualTo(0);
        int selectStart = text.lastIndexOf("<select", start);
        int updateStart = text.lastIndexOf("<update", start);
        int begin = Math.max(selectStart, updateStart);
        int end = text.indexOf("</select>", start);
        int updateEnd = text.indexOf("</update>", start);
        int finish = updateEnd < 0 ? end : (end < 0 ? updateEnd : Math.min(end, updateEnd));
        assertThat(finish).as(file + " 里语句 " + id + " 没有收尾").isGreaterThan(start);
        return text.substring(begin, finish);
    }

    private static String wholeFile(String file) {
        try {
            URL resource = AsyncToolTaskStopSqlContractTest.class.getClassLoader()
                    .getResource("mapper/" + file);
            assertThat(resource).as("找不到映射文件：" + file).isNotNull();
            return Files.readString(Path.of(resource.toURI()));
        } catch (Exception failure) {
            throw new IllegalStateException("读取映射文件失败：" + file, failure);
        }
    }
}
