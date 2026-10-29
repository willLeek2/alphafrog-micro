package world.willfrog.agent.platform.input;

import java.util.UUID;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.experiment.ExperimentRunContextFactory;
import world.willfrog.alphafrogmicro.common.racing.RacingCallException;
import world.willfrog.alphafrogmicro.common.racing.RacingInputClient;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptClientConfiguration;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.error.RacingFailure;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputInvocation;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputScope;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSources;

/** 将本次实际来源绑定到持久根身份；调用方在本线程用try-with-resources结束作用域。 */
@Component
@Import(RacingPromptClientConfiguration.class)
public final class AgentModelInputs {
    private final ExperimentRunContextFactory contexts;
    private final RacingInputClient inputs;

    public AgentModelInputs(ExperimentRunContextFactory contexts, RacingInputClient inputs) {
        this.contexts = contexts;
        this.inputs = inputs;
    }

    public ModelInputScope open(String endpointName, ModelInputSources sources) {
        try {
            String runId = AgentContext.getRunId();
            if (runId == null || runId.isBlank() || endpointName == null || endpointName.isBlank()
                    || sources == null || sources.parts().isEmpty()) {
                throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                        "本次模型输入缺少运行、端点或实际来源");
            }
            var context = contexts.current(runId, "model-input:" + UUID.randomUUID());
            return ModelInputScope.open(ModelInputInvocation.start(endpointName, sources, inputs.boundTo(context)));
        } catch (RuntimeException failure) {
            ModelSendBlocks.rethrowIfBlocked(failure);
            if (failure instanceof RacingCallException racing) {
                throw new ModelSendBlockedException(ModelSendBlocked.MODEL_INPUT_UNCONFIRMED,
                        "本次模型输入的原根归属尚未确认", RacingFailure.from(racing.error()));
            }
            throw new ModelSendBlockedException(ModelSendBlocked.MODEL_INPUT_UNCONFIRMED,
                    "本次模型输入的原根归属尚未确认");
        }
    }
}
