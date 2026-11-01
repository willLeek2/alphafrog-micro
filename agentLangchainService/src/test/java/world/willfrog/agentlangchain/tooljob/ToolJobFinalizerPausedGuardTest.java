package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 暂停处置（runDisposition=PAUSED）的收尾行为：长工具终态到达后，清理链
 * （envelope/release/usage/event）照常走完，但不把 Run CAS 回 RECEIVED、不生成恢复租约、
 * 不触发续接——Run 停在 WAITING 等用户手动恢复。
 *
 * <p>「WAITING + PAUSED 锚点等价于等待中」的 SQL 匹配语义由
 * {@code ToolJobAnchorMapperIntegrationTest} 在测试环境验证；本类验证的是接线：
 * finalizer 以 WAITING_TOOL_JOB 为条件发起条件更新，以及 PAUSED 分支不拉起恢复。
 */
class ToolJobFinalizerPausedGuardTest {



    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
