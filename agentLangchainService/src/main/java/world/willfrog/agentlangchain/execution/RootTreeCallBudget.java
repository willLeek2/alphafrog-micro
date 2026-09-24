package world.willfrog.agentlangchain.execution;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.exception.RunBudgetException;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentRunBudgetService;
import world.willfrog.agent.platform.treebudget.RootTreeBudgetStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agentlangchain.control.dualpool.RootRunResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 按稳定操作身份统计 V2 父子 Run 根树里的每次逻辑模型或工具调用。 */
@Component
@RequiredArgsConstructor
public class RootTreeCallBudget {
    private final RootTreeBudgetStore store;
    private final RootRunResolver roots;
    private final AgentRunMapper runs;
    private final AgentRunBudgetService config;

    public void beforeModelCall(NodeWorkItemIdentity segment, String modelTurn) {
        if (segment == null || modelTurn == null || modelTurn.isBlank()) {
            throw new IllegalArgumentException("模型调用缺少稳定的节点和回合身份");
        }
        reserveAndConfirm(segment.runId(),
                operationId("llm", segment.describe(), modelTurn),
                RootTreeBudgetStore.Kind.LLM_CALL,
                config.effectiveConfig().maxLlmCalls(), "llm_calls");
    }

    public void beforeToolCall(String runId, long groupId, String memberIdentity) {
        if (groupId <= 0 || memberIdentity == null || memberIdentity.isBlank()) {
            throw new IllegalArgumentException("工具调用缺少持久等待组成员身份");
        }
        reserveAndConfirm(runId,
                operationId("tool", Long.toString(groupId), memberIdentity),
                RootTreeBudgetStore.Kind.TOOL_CALL,
                config.effectiveConfig().maxToolCalls(), "tool_calls");
    }

    private void reserveAndConfirm(String runId, String operationId, RootTreeBudgetStore.Kind kind,
                                   long limit, String dimension) {
        AgentRun run = runs.findById(runId);
        if (run == null) {
            throw new IllegalStateException("预算调用找不到 Run：" + runId);
        }
        if (!"DUAL_POOL_V2".equals(run.getSchedulerVersion())) {
            return;
        }
        if (kind == RootTreeBudgetStore.Kind.LLM_CALL) {
            config.checkBeforeLlmCall();
        } else {
            config.checkBeforeToolCall();
        }
        String rootRunId = roots.rootRunId(runId);
        RootTreeBudgetStore.State state = store.reserve(rootRunId, operationId, kind,
                limit <= 0 ? Long.MAX_VALUE : limit);
        if (state == RootTreeBudgetStore.State.REJECTED) {
            long used = kind == RootTreeBudgetStore.Kind.LLM_CALL
                    ? store.snapshot(rootRunId).llmCalls() : store.snapshot(rootRunId).toolCalls();
            throw new RunBudgetException(dimension, used, limit, false);
        }
        if (state == RootTreeBudgetStore.State.RESERVED) {
            store.confirm(operationId);
        } else if (state != RootTreeBudgetStore.State.CONFIRMED) {
            throw new IllegalStateException("调用额度处于不可重放状态：" + state);
        }
    }

    private static String operationId(String kind, String first, String second) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = kind + ':' + first.length() + ':' + first + ':' + second.length() + ':' + second;
            return "tree-call:" + kind + ':' + HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("缺少 SHA-256，无法生成持久调用身份", failure);
        }
    }
}
