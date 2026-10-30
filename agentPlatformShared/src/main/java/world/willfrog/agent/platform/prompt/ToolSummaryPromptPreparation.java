package world.willfrog.agent.platform.prompt;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.experiment.ExperimentRunBindingStore;
import world.willfrog.agent.platform.experiment.ExperimentRunContextFactory;
import world.willfrog.alphafrogmicro.common.racing.RacingCallException;
import world.willfrog.alphafrogmicro.common.racing.RacingInputClient;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptClientConfiguration;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptSetReader;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.error.RacingFailure;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputInvocation;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputScope;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSource;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSources;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputStore;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptTemplateRenderer;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.racing.idl.RuntimeCallContext;

/** 工具总结只读取原根集合的两个片段，渲染后为本次独立调用建立来源作用域。 */
@Component
@Import(RacingPromptClientConfiguration.class)
public final class ToolSummaryPromptPreparation {
    public static final String SYSTEM_FRAGMENT = "toolSummarySystemPrompt";
    public static final String USER_FRAGMENT = "toolSummaryUserPromptTemplate";
    private final ExperimentRunContextFactory contexts;
    private final ExperimentRunBindingStore bindings;
    private final RacingPromptSetReader reader;
    private final RacingInputClient inputs;

    public ToolSummaryPromptPreparation(ExperimentRunContextFactory contexts, ExperimentRunBindingStore bindings,
            RacingPromptSetReader reader, RacingInputClient inputs) {
        this.contexts = contexts;
        this.bindings = bindings;
        this.reader = reader;
        this.inputs = inputs;
    }

    public Prepared prepare(String toolName, String rawOutput, String todoGoal) {
        return prepare(toolName, rawOutput, null, todoGoal);
    }

    public Prepared preparePrompt(String toolName, PromptText rawPrompt, String todoGoal) {
        if (rawPrompt == null) throw sourceMissing();
        return prepare(toolName, rawPrompt.text(), rawPrompt, todoGoal);
    }

    private Prepared prepare(String toolName, String rawOutput, PromptText rawPrompt,
                             String todoGoal) {
        try {
            String runId = AgentContext.getRunId();
            if (runId == null || runId.isBlank()) {
                throw blocked("工具总结缺少当前运行，无法确认根选择与输入归属");
            }
            RuntimeCallContext context = contexts.current(runId, "tool-summary:" + UUID.randomUUID());
            PromptRunSelection saved = PromptRunSelection.fromExt(bindings.savedPromptSelection(runId));
            var fragments = reader.readFragments(context, saved.racingSelection(), Map.of(
                    SYSTEM_FRAGMENT, SYSTEM_FRAGMENT, USER_FRAGMENT, USER_FRAGMENT));
            var userBody = fragments.get(USER_FRAGMENT);
            PromptText system = PromptTemplateRenderer.render(fragments.get(SYSTEM_FRAGMENT), Map.of());
            PromptText user = PromptTemplateRenderer.render(userBody, Map.of(
                    "toolName", nvl(toolName), "rawOutput", nvl(rawOutput), "todoGoal", nvl(todoGoal)));
            if (userBody.variables().stream().anyMatch(variable -> "rawOutput".equals(variable.name()))) {
                if (rawPrompt == null) throw sourceMissing();
                var sources = new ArrayList<>(user.sources());
                sources.addAll(rawPrompt.sources());
                user = new PromptText(user.text(), sources);
            }
            return new Prepared(context, system, user, inputs.boundTo(context));
        } catch (RuntimeException failure) {
            ModelSendBlocks.rethrowIfBlocked(failure);
            if (failure instanceof RacingCallException racing) {
                throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_UNREADABLE,
                        "工具总结的原根绑定或正文选择尚未确认", RacingFailure.from(racing.error()));
            }
            throw blocked("工具总结的原根绑定或正文选择尚未确认");
        }
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static ModelSendBlockedException blocked(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_UNREADABLE, reason);
    }

    private static ModelSendBlockedException sourceMissing() {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                "工具总结使用的原始结果尚未明确保存来源");
    }

    public record Prepared(RuntimeCallContext context, PromptText system, PromptText user, ModelInputStore store) {
        public ModelInputScope openInputScope(String endpointName) {
            ModelInputSources sources = new ModelInputSources(List.of(
                    new ModelInputSource("/messages/0", ModelInputSource.Origin.APPLICATION_INSTRUCTION, system.sources()),
                    new ModelInputSource("/messages/1", ModelInputSource.Origin.APPLICATION_INSTRUCTION, user.sources())));
            return ModelInputScope.open(ModelInputInvocation.start(endpointName, sources, store));
        }

        @Override
        public String toString() {
            return "Prepared[systemSources=" + system.sources().size() + ", userSources=" + user.sources().size() + "]";
        }
    }
}
