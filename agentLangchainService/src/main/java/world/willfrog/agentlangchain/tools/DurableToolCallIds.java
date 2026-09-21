package world.willfrog.agentlangchain.tools;

import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 会转后台的工具在持久层的稳定调用身份。
 *
 * <p>模型给出的工具调用身份只保证在一轮回复里唯一。节点池为每个 Todo 单独调用模型，两个不同节点
 * 完全可能都得到 {@code executePython_2}；直接拿它去 Sandbox 建任务，第二个真实任务会被当成
 * 第一个任务的幂等重放。</p>
 *
 * <p>所以 {@code executePython} 的持久身份要在模型给的名字后面追加节点分段身份的稳定摘要。同一段
 * 中断、重启或重新领取时摘要不变；换了计划代际、节点、节点尝试或执行分段就换一个摘要输入。
 * 其他工具没有跨进程持久作业，继续用模型给的原始身份。</p>
 *
 * <p>这个规则只有这一份实现：执行工具的那条路（{@code ToolRouterToolExecutor}）与整组落库时
 * 预计算外部作业身份的那条路（{@code LangchainNodeToolDispatcher}）都调这里，免得两边各写一套
 * 慢慢走偏。</p>
 */
public final class DurableToolCallIds {

    /** 会转后台、需要稳定身份的工具。 */
    public static final String ASYNC_PYTHON_TOOL = "executePython";

    /** 追加在模型给出的调用身份后面的节点摘要前缀。 */
    public static final String WORK_ITEM_SUFFIX = "--wi-";

    private DurableToolCallIds() {
    }

    /**
     * 算出某个工具调用在持久层的稳定身份。
     *
     * @param toolName      工具名
     * @param rawToolCallId 模型给出的工具调用身份
     * @param segment       当前执行分段的五字段身份，可为空（为空时按原始身份处理）
     */
    public static String forTool(String toolName, String rawToolCallId, NodeWorkItemIdentity segment) {
        if (!ASYNC_PYTHON_TOOL.equals(toolName) || segment == null || rawToolCallId == null) {
            return rawToolCallId;
        }
        String workItemScope = segment.describe();
        UUID workItemDigest = UUID.nameUUIDFromBytes(workItemScope.getBytes(StandardCharsets.UTF_8));
        return rawToolCallId + WORK_ITEM_SUFFIX + workItemDigest;
    }
}
