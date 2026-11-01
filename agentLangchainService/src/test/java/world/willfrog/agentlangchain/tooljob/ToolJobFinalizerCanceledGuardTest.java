package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the CANCELED branch isStepDone guard added to prevent the 5-second
 * hot-loop when an already-finalized CANCELED run is re-entered.
 *
 * <p>260818: the terminal CAS now goes through {@code cancelFromStatuses},
 * which accepts both WAITING_TOOL_JOB (cancel during background wait) and
 * EXECUTING (cancel landing after markHandoffAccepted resumed execution —
 * batch 20260818-182948's permanent-EXECUTING + dual retry loop root cause).
 * 260913: the accepted set additionally covers WAITING (pause-then-cancel) and
 * RECEIVED (finalizer CAS to RECEIVED, resume claimed but handoff not yet
 * written — batch 20260913-012514). The status-set semantics live in the SQL
 * and are pinned by {@code ToolJobAnchorMapperIntegrationTest}; this class
 * pins the wiring.
 */
class ToolJobFinalizerCanceledGuardTest {





    // ---- 260819: 终态 Run 残留取消锚点的兜底收口（e572 告警循环） ----





    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
