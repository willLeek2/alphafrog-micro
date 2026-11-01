package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.PythonRiskReplayEvidenceMissingException;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.exception.RunInterruptedException;
import world.willfrog.agent.platform.wait.WaitGroupMemberExecutionContext;
import world.willfrog.agent.platform.wait.WaitGroupMemberPendingException;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agentlangchain.tools.DurableToolCallIds;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

/**
 * 派发一次工具调用的规则自检。
 *
 * <p>这一层夹在「节点执行器已经决定要派发」与「工具目录真正执行」之间，用例盯的是它在两件事上的表现：
 * 会转后台的工具必须先装上成员上下文（工具层据此把这次调用建成等待成员的后台作业），
 * 以及工具层交出的派发证明必须与本组成员行上的外部作业身份一致，不一致就不写进成员行。</p>
 */
class LangchainNodeToolDispatcherTest {

    private static final String RUN_ID = "run-stage3";
    private static final String RAW_CALL_ID = "executePython_2";
    private static final String MEMBER_IDENTITY = "derived:v1:abc";

    private static final NodeWorkItemIdentity SEGMENT =
            new NodeWorkItemIdentity(RUN_ID, 3, "todo_1", 1, 0);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ToolExecutor> executors = new LinkedHashMap<>();

    private LangchainNodeToolDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new LangchainNodeToolDispatcher(toolProvider(), objectMapper);
    }


    // ==================== 会转后台的工具 ====================






    // ==================== 同步工具与失败 ====================








    // ==================== 测试脚手架 ====================

    private NodeToolDispatcher.DispatchRequest queryRequest() {
        return new NodeToolDispatcher.DispatchRequest(RUN_ID, SEGMENT, 77L, 2, MEMBER_IDENTITY,
                "call-q", ToolJobAnchor.EXECUTE_QUERY_TOOL, "{}");
    }

    private String queryExpectedOperationId() {
        String durableCallId = DurableToolCallIds.forTool(
                ToolJobAnchor.EXECUTE_QUERY_TOOL, "call-q", SEGMENT);
        return new DataAnalysisOperationIdentity(RUN_ID, durableCallId, 1).operationId();
    }

    private NodeToolDispatcher.DispatchRequest asyncRequest() {
        return new NodeToolDispatcher.DispatchRequest(RUN_ID, SEGMENT, 77L, 1, MEMBER_IDENTITY,
                RAW_CALL_ID, ToolJobAnchor.EXECUTE_PYTHON_TOOL, "{\"code\":\"print(1)\"}");
    }

    private String durableCallId() {
        return DurableToolCallIds.forTool(ToolJobAnchor.EXECUTE_PYTHON_TOOL, RAW_CALL_ID, SEGMENT);
    }

    private String expectedOperationId() {
        return new DataAnalysisOperationIdentity(RUN_ID, durableCallId(), 1).operationId();
    }

    private static WaitMemberDispatchProof proof(String operationId, String taskId) {
        return new WaitMemberDispatchProof(
                WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION,
                operationId,
                taskId,
                "sha256:tests",
                "{\"schemaVersion\":\"sandbox_create_v1\"}",
                "{\"estimatedRows\":1}",
                "{\"reservationId\":\"" + operationId + "\"}",
                "2026-09-22T00:00:00Z");
    }

    /** 工具目录：按名字取执行器，与生产目录的取法一致。 */
    private ObjectProvider<ToolProvider> toolProvider() {
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderResult provideTools(ToolProviderRequest request) {
                Map<ToolSpecification, ToolExecutor> map = new LinkedHashMap<>();
                executors.forEach((name, executor) -> map.put(
                        ToolSpecification.builder().name(name).build(), executor));
                return new ToolProviderResult(map);
            }
        };
        return new ObjectProvider<>() {
            @Override
            public ToolProvider getObject() {
                return provider;
            }

            @Override
            public ToolProvider getObject(Object... args) {
                return provider;
            }

            @Override
            public ToolProvider getIfAvailable() {
                return provider;
            }

            @Override
            public ToolProvider getIfUnique() {
                return provider;
            }
        };
    }
}
