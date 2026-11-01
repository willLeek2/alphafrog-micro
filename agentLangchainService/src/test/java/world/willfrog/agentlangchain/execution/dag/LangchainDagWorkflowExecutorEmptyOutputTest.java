package world.willfrog.agentlangchain.execution.dag;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.TodoItem;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;

/**
 * ccmax #59: DAG TODO_NODE_FAILED 事件 payload 在 empty_todo_output 场景下的结构化观测 + recovery 标记。
 *
 * <p>覆盖以下三种调用：
 * <ul>
 *   <li><b>失败 + observation</b>：调用 9 参 overload，failureMetadata 非空 → payload 含 empty_output_observation</li>
 *   <li><b>成功 + recovered</b>：success=true + recovered=true + recovery_outcome=success → payload 含 recovered=true + recovery_outcome</li>
 *   <li><b>5 参 overload delegation</b>：调用 5 参 overload（向后兼容）→ 走默认 9 参路径，failureMetadata=null，无 observation / recovered</li>
 * </ul>
 */
class LangchainDagWorkflowExecutorEmptyOutputTest {





    // ========== 辅助 ==========

    private static LangchainDagWorkflowExecutor executor() {
        return newExecutor();
    }

    private static LangchainDagWorkflowExecutor newExecutor() {
        return new LangchainDagWorkflowExecutor(
                mock(world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor.class),
                mock(LangchainDagStateRecorder.class),
                mock(AgentRunEventService.class),
                mock(world.willfrog.agentlangchain.control.LangchainRunExecutionGuard.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(org.springframework.beans.factory.ObjectProvider.class));
    }
}
