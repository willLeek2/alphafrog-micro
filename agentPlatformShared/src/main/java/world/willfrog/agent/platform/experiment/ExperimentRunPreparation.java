package world.willfrog.agent.platform.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.prompt.PromptRunSelection;
import world.willfrog.agent.platform.prompt.PromptSelectionCoordinator;
import world.willfrog.alphafrogmicro.common.racing.RacingCallException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.error.RacingFailure;

import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_UNREADABLE;

/** 创建时冻结服务身份，执行与恢复时只读取原根已经提交的集合选择。 */
@Component
public class ExperimentRunPreparation {
    private final ExperimentRunContextFactory contexts;
    private final ExperimentRunBindingStore bindings;
    private final PromptSelectionCoordinator prompts;

    public ExperimentRunPreparation(ExperimentRunContextFactory contexts,
            ExperimentRunBindingStore bindings, PromptSelectionCoordinator prompts) {
        this.contexts = contexts;
        this.bindings = bindings;
        this.prompts = prompts;
    }

    /** 新根使用服务配置确认身份；用户传入的上下文不能覆盖此值。 */
    public JsonNode freezeContext(String rootRunId) {
        return bindings.toExtValue(contexts.freeze(rootRunId));
    }

    /** 子运行和恢复沿持久关系回到原根，保存与独立重读都确认后才允许使用正文。 */
    public PromptRunSelection ensurePrepared(String runId) {
        try {
            var expected = prompts.ensurePrepared(contexts.current(runId));
            var stored = PromptRunSelection.fromExt(bindings.savedPromptSelection(runId));
            if (stored.schemaVersion() != PromptRunSelection.RACING_SCHEMA_VERSION || !stored.equals(expected)) {
                throw new ModelSendBlockedException(PROMPT_UNREADABLE, "原根提示词选择的保存结果不一致");
            }
            return stored;
        } catch (RuntimeException failure) {
            ModelSendBlocks.rethrowIfBlocked(failure);
            if (failure instanceof RacingCallException racing) {
                throw new ModelSendBlockedException(PROMPT_UNREADABLE,
                        "原根提示词准备尚未确认", RacingFailure.from(racing.error()));
            }
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "原根提示词准备尚未确认");
        }
    }
}
