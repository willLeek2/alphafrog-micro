package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

/** 后台读取正文时仅恢复持久调用身份；正文读取器继续按该运行的原根选择读取。 */
final class BackgroundPromptContext implements AutoCloseable {
    private final AgentContext.ContextSnapshot previous;
    private final String todoId;
    private final Integer sequence;
    private final String toolCallId;

    static BackgroundPromptContext open(AgentRun run, String todo, Integer sequence, String call) {
        if (run == null || run.getId() == null || run.getId().isBlank()
                || run.getUserId() == null || run.getUserId().isBlank()) {
            throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, "background-run-context");
        }
        return new BackgroundPromptContext(run, todo, sequence, call);
    }
    private BackgroundPromptContext(AgentRun run, String todo, Integer seq, String call) {
        previous = AgentContext.captureRunContext();
        todoId = AgentContext.getTodoId(); sequence = AgentContext.getTodoSequence();
        toolCallId = AgentContext.getToolCallId();
        AgentContext.setRunId(run.getId()); AgentContext.setUserId(run.getUserId());
        AgentContext.setTodoContext(todo, seq); AgentContext.setToolCallId(call);
        AgentContext.clearPromptRunSelection(); AgentContext.clearDataFreshness();
    }
    @Override public void close() {
        AgentContext.restoreRunContext(previous);
        AgentContext.setTodoContext(todoId, sequence); AgentContext.setToolCallId(toolCallId);
    }
}
