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





    // ===== 身份唯一约束 =====






    // ===== 等待链的归属关系 =====




    // ===== Java 枚举与库里约束一致 =====






    // ===== 阶段三后补的三份脚本（真库验证在真库探针里，这里盯住不连库也看得出的形状） =====




    // ===== 组与成员的状态不变量 =====








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
