package world.willfrog.agent.platform.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.experiment.ExperimentRunBindingStore;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptClient;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptClientConfiguration;
import world.willfrog.alphafrogmicro.common.racing.RacingCallException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.error.RacingFailure;
import world.willfrog.racing.idl.GetPromptSelectionRequest;
import world.willfrog.racing.idl.PromptScope;
import world.willfrog.racing.idl.PromptSelection;
import world.willfrog.racing.idl.PromptSetDefaultChoice;
import world.willfrog.racing.idl.ResolvePromptSelectionRequest;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.TargetIdentity;

import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_UNREADABLE;

/** 确认远端根选择后保存本地专属字段；旧摘要需要补录，不能重新选默认代替。 */
@Component
@Import(RacingPromptClientConfiguration.class)
public final class PromptSelectionCoordinator {
    public static final String SELECTION_KEY = "agent.default_prompt_set";
    public static final String BUSINESS_NAME = "alphafrog.agent";
    public static final String REFERENCE_DATE_FIELD = "prompt_reference_date";
    public static final String EXPERIMENT_ID_FIELD = "prompt_set_experiment_id";
    private final RacingPromptClient prompts;
    private final ExperimentRunBindingStore bindings;
    private final AgentRunMapper runs;
    private final ObjectMapper json;

    public PromptSelectionCoordinator(RacingPromptClient prompts, ExperimentRunBindingStore bindings,
                                       AgentRunMapper runs, ObjectMapper json) {
        this.prompts = Objects.requireNonNull(prompts);
        this.bindings = Objects.requireNonNull(bindings);
        this.runs = Objects.requireNonNull(runs);
        this.json = Objects.requireNonNull(json);
    }

    public PromptRunSelection ensurePrepared(RuntimeCallContext confirmedContext) {
        try {
            check(confirmedContext != null && confirmedContext.hasRoot() && confirmedContext.hasTarget(),
                    "提示词准备缺少已确认的根绑定");
            String rootId = confirmedContext.getRoot().getRootId();
            check(!rootId.isBlank(), "提示词准备缺少根编号");
            RuntimeCallContext savedBinding = bindings.saved(rootId);
            requireBinding(savedBinding, confirmedContext);
            RootPromptSettings settings = rootSettings(rootId);
            LocalDate referenceDate = settings.referenceDate();
            JsonNode saved = bindings.savedPromptSelection(rootId);
            PromptRunSelection result;
            if (saved != null) {
                check(saved.isObject() && saved.path("schema_version").isIntegralNumber()
                                && saved.path("schema_version").intValue() == PromptRunSelection.RACING_SCHEMA_VERSION,
                        "根运行保留旧摘要或未知选择结构，需要补录真实集合版本");
                PromptRunSelection local = PromptRunSelection.fromExt(saved);
                check(referenceDate.equals(local.referenceDate()), "根运行固定日期与已保存选择不一致");
                PromptSelection original = local.racingSelection();
                requireSelection(original, confirmedContext, settings.experimentId());
                PromptSelection actual = prompts.getSelection(GetPromptSelectionRequest.newBuilder()
                        .setContext(confirmedContext).setSelectionId(original.getSelectionId()).build());
                requireSelection(actual, confirmedContext, settings.experimentId());
                // 普通发布可刷新预期代际，原根选择及其来源、记录时间仍保持。
                check(original.toBuilder().clearTarget().clearConfirmedAt().build()
                                .equals(actual.toBuilder().clearTarget().clearConfirmedAt().build()),
                        "平台返回的根选择与本地原记录不一致");
                result = local;
            } else {
                TargetIdentity target = confirmedContext.getTarget();
                PromptSelection actual = prompts.resolveSelection(ResolvePromptSelectionRequest.newBuilder()
                        .setContext(confirmedContext).setSelectionKey(SELECTION_KEY)
                        .setExperimentId(settings.experimentId())
                        .setPromptSetDefault(PromptSetDefaultChoice.newBuilder().setBusinessName(BUSINESS_NAME)
                                .setScope(PromptScope.newBuilder().setEnvironment(target.getEnvironment())
                                        .setScopeInstanceId(target.getTargetScopeInstanceId())))
                        .build());
                requireSelection(actual, confirmedContext, settings.experimentId());
                result = PromptRunSelection.fromRacing(actual, referenceDate);
            }
            // 公共存储提交并重读后才成功返回，外层公共准备不再重复写相同字段。
            bindings.confirmPromptSelection(confirmedContext, result.toExtMap());
            return result;
        } catch (RuntimeException failure) {
            ModelSendBlocked marker = ModelSendBlocks.find(failure).orElse(null);
            if (marker != null) {
                throw new ModelSendBlockedException(marker.code(), marker.reason(), marker.racingFailure());
            }
            if (failure instanceof RacingCallException racing) {
                throw new ModelSendBlockedException(PROMPT_UNREADABLE,
                        "根运行提示词选择或本地保存尚未确认", RacingFailure.from(racing.error()));
            }
            throw blocked("根运行提示词选择或本地保存尚未确认");
        }
    }

    private RootPromptSettings rootSettings(String rootId) {
        try {
            AgentRun root = runs.findById(rootId);
            check(root != null && rootId.equals(root.getId()), "根运行记录不可读取");
            JsonNode ext = json.readTree(root.getExt());
            JsonNode date = ext == null ? null : ext.get(REFERENCE_DATE_FIELD);
            check(date != null && date.isTextual() && !date.textValue().isBlank(), "根运行缺少固定提示词日期");
            JsonNode experiment = ext.get(EXPERIMENT_ID_FIELD);
            check(experiment == null || experiment.isTextual(), "根运行的提示词实验编号无效");
            String experimentId = experiment == null ? "" : experiment.textValue();
            check(experimentId.isEmpty() || !experimentId.isBlank(), "根运行的提示词实验编号无效");
            return new RootPromptSettings(LocalDate.parse(date.textValue()), experimentId);
        } catch (ModelSendBlockedException failure) {
            throw failure;
        } catch (Exception failure) {
            throw blocked("根运行固定提示词日期或实验编号无法读取");
        }
    }

    private static void requireSelection(PromptSelection selection, RuntimeCallContext context, String experimentId) {
        check(selection.hasRoot() && selection.getRoot().equals(context.getRoot()), "平台根选择归属不一致");
        check(selection.hasTarget() && sameLogicalTarget(selection.getTarget(), context.getTarget()),
                "平台根选择逻辑目标不一致");
        check(SELECTION_KEY.equals(selection.getSelectionKey()), "平台根选择键不一致");
        check(selection.hasSelectedTarget() && selection.getSelectedTarget().hasPromptSetVersion(),
                "根运行提示词准备只接受明确集合");
        check(selection.hasOrigin() && BUSINESS_NAME.equals(selection.getOrigin().getBusinessName()),
                "平台根选择的业务默认来源不一致");
        if (experimentId.isEmpty()) {
            check(selection.getOrigin().hasValueSource(), "普通根选择不能使用未配置的提示词实验");
        } else {
            check(selection.getOrigin().hasExperiment()
                            && experimentId.equals(selection.getOrigin().getExperiment().getExperimentId()),
                    "平台根选择与原根冻结的提示词实验不一致");
        }
    }

    private record RootPromptSettings(LocalDate referenceDate, String experimentId) { }

    private static void requireBinding(RuntimeCallContext saved, RuntimeCallContext confirmed) {
        check(saved != null && saved.hasRoot() && saved.hasTarget()
                        && saved.getRoot().equals(confirmed.getRoot())
                        && sameLogicalTarget(saved.getTarget(), confirmed.getTarget()),
                "提示词准备与持久根绑定不一致");
    }

    private static boolean sameLogicalTarget(TargetIdentity left, TargetIdentity right) {
        return left.getEnvironment().equals(right.getEnvironment())
                && left.getTargetScopeInstanceId().equals(right.getTargetScopeInstanceId())
                && left.getTargetRouteTag().equals(right.getTargetRouteTag())
                && left.getTargetDeploymentId().equals(right.getTargetDeploymentId());
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw blocked(reason);
    }

    private static ModelSendBlockedException blocked(String reason) {
        return new ModelSendBlockedException(PROMPT_UNREADABLE, reason);
    }
}
