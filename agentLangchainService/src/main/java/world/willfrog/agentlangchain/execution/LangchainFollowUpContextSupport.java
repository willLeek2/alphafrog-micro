package world.willfrog.agentlangchain.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.service.AgentContextCompressor;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LangchainFollowUpContextSupport {

    private final AgentMessageService messageService;
    private final AgentContextCompressor contextCompressor;
    private final AgentRunEventService eventService;

    public ExecutionContext resolve(AgentRun run) {
        String goal = userGoal(run);
        PromptText history = buildDialogueContext(run.getId(), goal);
        return new ExecutionContext(goal, history.text(), history);
    }

    /** 失败收口只取用户目标，不压缩历史，也不触发模型。 */
    public String userGoal(AgentRun run) {
        String currentGoal = eventService.extractUserGoal(run.getExt());
        AgentRunMessage latestUser = messageService.findLatestUserMessage(run.getId());
        if (latestUser != null
                && AgentRunMessage.MSG_TYPE_FOLLOW_UP.equals(latestUser.getMsgType())
                && latestUser.getContent() != null && !latestUser.getContent().isBlank()) {
            currentGoal = latestUser.getContent().trim();
        }
        return nvl(currentGoal);
    }

    private PromptText buildDialogueContext(String runId, String currentUserGoal) {
        try {
            List<AgentRunMessage> messages = messageService.listMessages(runId);
            if (messages == null || messages.isEmpty()) {
                return PromptText.runtimeData("");
            }
            AgentContextCompressor.ContextBuildResult result =
                    contextCompressor.buildCompressedContext(messages, currentUserGoal);
            if (result == null || result.dialogueContextPrompt() == null
                    || !nvl(result.text()).equals(result.dialogueContextPrompt().text())) {
                throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                        "历史上下文缺少与正文一致的来源记录");
            }
            return result.dialogueContextPrompt();
        } catch (Exception e) {
            ModelSendBlocks.rethrowIfBlocked(e);
            log.warn("Failed to build follow-up dialogue context for runId={}: {}", runId, e.getMessage());
            return PromptText.runtimeData("");
        }
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    public record ExecutionContext(String userGoal, String dialogueContext, PromptText dialogueContextPrompt) {
        /** 旧调用只提供文字；后续模型入口不得为非空文字补造来源。 */
        public ExecutionContext(String userGoal, String dialogueContext) {
            this(userGoal, dialogueContext, null);
        }
    }
}
