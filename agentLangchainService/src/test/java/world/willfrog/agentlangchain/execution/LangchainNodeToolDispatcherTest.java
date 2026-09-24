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
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
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

    @Test
    void anAsyncMemberIsDispatchedWithThePersistedOperationIdentity() {
        String expectedOperationId = expectedOperationId();
        AtomicReference<WaitGroupMemberExecutionContext.Snapshot> seen = new AtomicReference<>();
        executors.put(DurableToolCallIds.ASYNC_PYTHON_TOOL, (request, memoryId) -> {
            seen.set(WaitGroupMemberExecutionContext.current());
            throw new WaitGroupMemberPendingException(
                    proof(expectedOperationId, "task-9"), "沙箱任务已交出");
        });

        NodeToolDispatcher.DispatchOutcome outcome = dispatcher.dispatch(asyncRequest());

        assertThat(outcome).isInstanceOfSatisfying(NodeToolDispatcher.DispatchOutcome.Pending.class,
                pending -> {
                    assertThat(pending.operationId()).isEqualTo(expectedOperationId);
                    assertThat(pending.taskId()).isEqualTo("task-9");
                    assertThat(WaitMemberDispatchProof.fromJson(objectMapper, pending.dispatchProofJson()))
                            .as("写进成员行的证明能原样读回来")
                            .get()
                            .satisfies(proof -> {
                                assertThat(proof.operationId()).isEqualTo(expectedOperationId);
                                assertThat(proof.taskId()).isEqualTo("task-9");
                            });
                });
        assertThat(seen.get()).as("派发期间工具层能看到成员上下文").isNotNull();
        assertThat(seen.get().runId()).isEqualTo(RUN_ID);
        assertThat(seen.get().groupId()).isEqualTo(77L);
        assertThat(seen.get().memberSeq()).isEqualTo(1);
        assertThat(seen.get().memberIdentity()).isEqualTo(MEMBER_IDENTITY);
        assertThat(seen.get().expectedOperationId()).isEqualTo(expectedOperationId);
        assertThat(seen.get().durableToolCallId())
                .as("工具层按这个身份拼出外部作业身份").isEqualTo(durableCallId());
        assertThat(WaitGroupMemberExecutionContext.current())
                .as("派发结束后上下文必须恢复，不能留给同一线程上的下一次调用").isNull();
    }

    @Test
    void anAsyncToolWithoutAModelCallIdFailsClosedBeforeAnyContext() {
        AtomicReference<WaitGroupMemberExecutionContext.Snapshot> seen = new AtomicReference<>();
        executors.put(DurableToolCallIds.ASYNC_PYTHON_TOOL, (request, memoryId) -> {
            seen.set(WaitGroupMemberExecutionContext.current());
            return "不该走到这里";
        });

        NodeToolDispatcher.DispatchOutcome outcome = dispatcher.dispatch(
                new NodeToolDispatcher.DispatchRequest(RUN_ID, SEGMENT, 77L, 1, MEMBER_IDENTITY,
                        null, DurableToolCallIds.ASYNC_PYTHON_TOOL, "{}"));

        assertThat(outcome).isInstanceOfSatisfying(NodeToolDispatcher.DispatchOutcome.Failed.class,
                failed -> assertThat(failed.reason())
                        .isEqualTo("wait_group_member_without_operation_id:executePython"));
        assertThat(seen.get()).as("算不出身份时连工具都不执行").isNull();
    }

    @Test
    void aProofForAnotherOperationIsRejected() {
        executors.put(DurableToolCallIds.ASYNC_PYTHON_TOOL, (request, memoryId) -> {
            throw new WaitGroupMemberPendingException(
                    proof("run-stage3:someone-else:1", "task-9"), "沙箱任务已交出");
        });

        NodeToolDispatcher.DispatchOutcome outcome = dispatcher.dispatch(asyncRequest());

        assertThat(outcome).isInstanceOfSatisfying(NodeToolDispatcher.DispatchOutcome.Failed.class,
                failed -> assertThat(failed.reason())
                        .isEqualTo("wait_group_member_operation_identity_mismatch"));
    }

    // ==================== 同步工具与失败 ====================

    @Test
    void aSyncToolRunsWithoutAnyMemberContext() {
        AtomicReference<WaitGroupMemberExecutionContext.Snapshot> seen = new AtomicReference<>();
        executors.put("getStockDaily", (request, memoryId) -> {
            seen.set(WaitGroupMemberExecutionContext.current());
            return "{\"ok\":true}";
        });

        NodeToolDispatcher.DispatchOutcome outcome = dispatcher.dispatch(
                new NodeToolDispatcher.DispatchRequest(RUN_ID, SEGMENT, 77L, 0, MEMBER_IDENTITY,
                        "call-a", "getStockDaily", "{}"));

        assertThat(outcome).isInstanceOfSatisfying(NodeToolDispatcher.DispatchOutcome.Completed.class,
                completed -> assertThat(completed.output()).isEqualTo("{\"ok\":true}"));
        assertThat(seen.get()).as("当场出结果的工具不该看到成员上下文").isNull();
    }

    @Test
    void anOrdinaryToolFailureBecomesFailureText() {
        executors.put("getStockDaily", (request, memoryId) -> {
            throw new IllegalStateException("数据源连不上");
        });

        NodeToolDispatcher.DispatchOutcome outcome = dispatcher.dispatch(
                new NodeToolDispatcher.DispatchRequest(RUN_ID, SEGMENT, 77L, 0, MEMBER_IDENTITY,
                        "call-a", "getStockDaily", "{}"));

        assertThat(outcome).isInstanceOfSatisfying(NodeToolDispatcher.DispatchOutcome.Failed.class,
                failed -> assertThat(failed.reason()).isEqualTo("数据源连不上"));
    }

    @Test
    void aControlSignalIsRethrownInsteadOfBecomingText() {
        executors.put("getStockDaily", (request, memoryId) -> {
            throw new RunInterruptedException("CANCEL_REQUESTED");
        });

        assertThatThrownBy(() -> dispatcher.dispatch(
                new NodeToolDispatcher.DispatchRequest(RUN_ID, SEGMENT, 77L, 0, MEMBER_IDENTITY,
                        "call-a", "getStockDaily", "{}")))
                .isInstanceOf(RunInterruptedException.class);
    }

    @Test
    void theSubAgentToolsAreRejectedWithoutTouchingTheCatalog() {
        executors.put("spawnSubAgent", (request, memoryId) -> "不该走到这里");

        NodeToolDispatcher.DispatchOutcome outcome = dispatcher.dispatch(
                new NodeToolDispatcher.DispatchRequest(RUN_ID, SEGMENT, 77L, 0, MEMBER_IDENTITY,
                        "call-a", "spawnSubAgent", "{}"));

        assertThat(outcome).isInstanceOfSatisfying(NodeToolDispatcher.DispatchOutcome.Failed.class,
                failed -> assertThat(failed.reason()).isEqualTo("sub_agent_tool_not_available:spawnSubAgent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledSubAgentToolUsesPersistentBridgeInsteadOfLegacyCatalog() {
        PersistentSubAgentToolBridge bridge = mock(PersistentSubAgentToolBridge.class);
        ObjectProvider<PersistentSubAgentToolBridge> bridgeProvider = mock(ObjectProvider.class);
        when(bridgeProvider.getIfAvailable()).thenReturn(bridge);
        when(bridge.availableForRun(RUN_ID)).thenReturn(true);
        NodeToolDispatcher.DispatchRequest request = new NodeToolDispatcher.DispatchRequest(
                RUN_ID, SEGMENT, 77L, 0, MEMBER_IDENTITY, "call-a", "spawnSubAgent", "{\"goal\":\"查资料\"}");
        when(bridge.dispatch(request)).thenReturn(new NodeToolDispatcher.DispatchOutcome.Completed("child-1"));
        executors.put("spawnSubAgent", (toolRequest, memoryId) -> {
            throw new AssertionError("V2 不得执行旧进程内子代理工具");
        });

        NodeToolDispatcher.DispatchOutcome outcome =
                new LangchainNodeToolDispatcher(toolProvider(), objectMapper, bridgeProvider).dispatch(request);

        assertThat(outcome).isInstanceOfSatisfying(NodeToolDispatcher.DispatchOutcome.Completed.class,
                completed -> assertThat(completed.output()).isEqualTo("child-1"));
    }

    @Test
    void subAgentOperationIdentityIsStableAndScopedToEachSegmentAndTool() {
        String spawnId = dispatcher.stableOperationId("spawnSubAgent", "call-a", SEGMENT).orElseThrow();
        assertThat(dispatcher.requiresStableOperationId("spawnSubAgent")).isTrue();
        assertThat(dispatcher.requiresStableOperationId("waitForSubAgent")).isTrue();
        assertThat(dispatcher.stableOperationId("spawnSubAgent", "call-a", SEGMENT)).contains(spawnId);
        assertThat(dispatcher.stableOperationId("waitForSubAgent", "call-a", SEGMENT).orElseThrow())
                .isNotEqualTo(spawnId);
        assertThat(dispatcher.stableOperationId("spawnSubAgent", "call-a",
                new NodeWorkItemIdentity(RUN_ID, 3, "todo_2", 1, 0)).orElseThrow())
                .isNotEqualTo(spawnId);
        assertThat(dispatcher.stableOperationId("spawnSubAgent", null, SEGMENT)).isEmpty();
    }

    @Test
    void anUnknownToolIsReportedByName() {
        NodeToolDispatcher.DispatchOutcome outcome = dispatcher.dispatch(
                new NodeToolDispatcher.DispatchRequest(RUN_ID, SEGMENT, 77L, 0, MEMBER_IDENTITY,
                        "call-a", "notInCatalog", "{}"));

        assertThat(outcome).isInstanceOfSatisfying(NodeToolDispatcher.DispatchOutcome.Failed.class,
                failed -> assertThat(failed.reason()).isEqualTo("unknown_tool:notInCatalog"));
    }

    // ==================== 测试脚手架 ====================

    private NodeToolDispatcher.DispatchRequest asyncRequest() {
        return new NodeToolDispatcher.DispatchRequest(RUN_ID, SEGMENT, 77L, 1, MEMBER_IDENTITY,
                RAW_CALL_ID, DurableToolCallIds.ASYNC_PYTHON_TOOL, "{\"code\":\"print(1)\"}");
    }

    private String durableCallId() {
        return DurableToolCallIds.forTool(DurableToolCallIds.ASYNC_PYTHON_TOOL, RAW_CALL_ID, SEGMENT);
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
