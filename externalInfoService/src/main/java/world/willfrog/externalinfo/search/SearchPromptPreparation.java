package world.willfrog.externalinfo.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.racing.RacingCallException;
import world.willfrog.alphafrogmicro.common.racing.RacingInputClient;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptClient;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptClientConfiguration;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptSetReader;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.error.RacingFailure;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputInvocation;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSource;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSources;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.racing.idl.GetPromptSelectionRequest;
import world.willfrog.racing.idl.PromptSelection;
import world.willfrog.racing.idl.RuntimeCallContext;

/** 网页搜索显式接收原根绑定；正文与保存客户端继续由平台核对权限及目标。 */
@Component
@Import(RacingPromptClientConfiguration.class)
public final class SearchPromptPreparation {
    private final RacingPromptClient prompts;
    private final RacingPromptSetReader reader;
    private final RacingInputClient inputs;

    public SearchPromptPreparation(RacingPromptClient prompts, RacingPromptSetReader reader, RacingInputClient inputs) {
        this.prompts = prompts;
        this.reader = reader;
        this.inputs = inputs;
    }

    public Binding bind(WebSearchRequest request) {
        try {
            if (request == null || request.getRuntimeCallContext().isEmpty() || request.getPromptSelection().isEmpty()) {
                throw missing("网页搜索缺少原运行及正文选择");
            }
            RuntimeCallContext context = RuntimeCallContext.parseFrom(request.getRuntimeCallContext());
            PromptSelection selection = PromptSelection.parseFrom(request.getPromptSelection());
            if (!context.hasRoot() || !context.hasTarget() || context.getRoot().getRootId().isBlank()
                    || context.getCurrentRunId().isBlank() || !context.getCurrentRunId().equals(request.getRunId())
                    || !selection.hasRoot() || !selection.getRoot().equals(context.getRoot())
                    || selection.getSelectionId().isBlank()) {
                throw missing("网页搜索运行身份与原根选择不一致");
            }
            PromptSelection saved = prompts.getSelection(GetPromptSelectionRequest.newBuilder()
                    .setContext(context).setSelectionId(selection.getSelectionId()).build());
            if (!stable(saved).equals(stable(selection))) {
                throw missing("网页搜索选择与平台保存的原选择不一致");
            }
            return new Binding(context, selection, reader, inputs);
        } catch (Exception failure) {
            ModelSendBlocks.rethrowIfBlocked(failure);
            if (failure instanceof RacingCallException racing) {
                throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_UNREADABLE,
                        "网页搜索无法确认原运行正文", RacingFailure.from(racing.error()));
            }
            throw missing("网页搜索无法解析原运行正文绑定");
        }
    }

    private static PromptSelection stable(PromptSelection selection) {
        return selection.toBuilder().clearConfirmedAt().setTarget(selection.getTarget().toBuilder()
                .clearExpectedTargetGenerationId()).build();
    }

    public static ModelSendBlockedException missing(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, reason);
    }

    public record Binding(RuntimeCallContext context, PromptSelection selection,
                          RacingPromptSetReader reader, RacingInputClient inputs) {
        public Map<String, PromptText> render(Map<String, Map<String, String>> values) {
            Map<String, String> purposes = new LinkedHashMap<>();
            values.keySet().forEach(fragment -> purposes.put(fragment, fragment));
            return reader.renderFragments(context, selection, purposes, values);
        }

        public ModelInputInvocation invocation(String endpoint, ModelInputSources sources) {
            return ModelInputInvocation.start(endpoint, sources, inputs.boundTo(context));
        }
    }

    public record RequestBody(Map<String, Object> body, ModelInputSources sources) {
        public static RequestBody of(Map<String, Object> body, Map<String, PromptText> texts) {
            List<ModelInputSource> sources = texts.entrySet().stream().map(entry -> {
                PromptText prompt = entry.getValue();
                return new ModelInputSource(entry.getKey(), prompt.sources().isEmpty()
                        ? ModelInputSource.Origin.RUNTIME_DATA : ModelInputSource.Origin.APPLICATION_INSTRUCTION,
                        prompt.sources());
            }).toList();
            return new RequestBody(body, new ModelInputSources(sources));
        }
    }

    public static Binding requireBinding(WebSearchExecutionContext context) {
        if (context == null || context.promptBinding() == null) throw missing("网页搜索缺少已核对正文绑定");
        return context.promptBinding();
    }
}
