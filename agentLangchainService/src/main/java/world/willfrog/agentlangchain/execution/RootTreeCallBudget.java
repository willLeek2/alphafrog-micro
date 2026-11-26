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

    /** 工具调用次数维度的模型可见额度快照。 */
    public record ToolCallBudgetStatus(
            String dimension,
            boolean unlimited,
            long used,
            long limit,
            long remaining,
            String scopeNote) {
    }

    /**
     * 读取当前运行的工具调用次数额度快照，供模型可见性注入（执行逐轮提示、规划阶段提示）使用。
     *
     * <p><b>选源规则</b>：计数源按该运行<b>冻结的调度版本</b>选择，调用方只传当前运行编号、
     * 不自选计数源——是 V2（DUAL_POOL_V2）时沿用根解析把当前运行归到根运行，读根树共享计数
     * （数据库表 {@code alphafrog_agent_run_tree_budget} 的快照）；否则（V1）读本运行观测统计的
     * 工具次数累计（经 {@link AgentRunBudgetService#observedToolCalls(String)}），与该服务
     * tool_calls 拒绝检查同一数据源，避免展示与判定出现两套数字。Run 行缺失或编号为空时按
     * V1 口径降级（观测读不到即 0）。</p>
     *
     * <p><b>三条显示规则</b>：①上限 ≤0（配置为 0 或负，即不设次数上限）时 {@code unlimited=true}，
     * 不返回巨大数字；②配置调小到低于已用次数时 {@code remaining=0}，{@code used} 保留真实值；
     * ③这里只展示所选来源的额度快照，仅用于模型可见性——实际能否派发仍由现有准入决定，
     * 不为此加锁、不预分额度。</p>
     */
    public ToolCallBudgetStatus toolCallBudgetStatus(String currentRunId) {
        long limit = config.effectiveConfig().maxToolCalls();
        boolean dualPoolV2 = false;
        if (currentRunId != null && !currentRunId.isBlank()) {
            AgentRun run = runs.findById(currentRunId);
            dualPoolV2 = run != null && "DUAL_POOL_V2".equals(run.getSchedulerVersion());
        }
        long used;
        String scopeNote;
        if (dualPoolV2) {
            used = store.snapshot(roots.rootRunId(currentRunId)).toolCalls();
            scopeNote = "整棵运行树（含子运行）共享";
        } else {
            used = config.observedToolCalls(currentRunId);
            scopeNote = "本运行";
        }
        boolean unlimited = limit <= 0;
        long remaining = unlimited ? 0L : Math.max(0L, limit - used);
        return new ToolCallBudgetStatus("tool_calls", unlimited, used, limit, remaining, scopeNote);
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
