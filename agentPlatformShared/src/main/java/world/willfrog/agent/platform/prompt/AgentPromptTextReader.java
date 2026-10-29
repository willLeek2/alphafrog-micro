package world.willfrog.agent.platform.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.experiment.ExperimentRunContextFactory;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.racing.RacingCallException;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptClient;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptClientConfiguration;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptSetReader;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.error.RacingFailure;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptTemplateRenderer;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionBody;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;
import world.willfrog.racing.idl.PromptSetMember;
import world.willfrog.racing.idl.ReadPromptSetVersionRequest;
import world.willfrog.racing.idl.RuntimeCallContext;

/** 正文只按持久根集合读取；日期和数据时效也来自同一个根记录。 */
@Component
@Import(RacingPromptClientConfiguration.class)
public final class AgentPromptTextReader {
    private static final String REQUIREMENTS_PREFIX = "pythonRefineRequirements.";
    private final ExperimentRunContextFactory contexts;
    private final AgentRunMapper runs;
    private final ObjectMapper json;
    private final RacingPromptClient prompts;
    private final RacingPromptSetReader reader;

    public AgentPromptTextReader(ExperimentRunContextFactory contexts, AgentRunMapper runs, ObjectMapper json,
                                 RacingPromptClient prompts, RacingPromptSetReader reader) {
        this.contexts = contexts;
        this.runs = runs;
        this.json = json;
        this.prompts = prompts;
        this.reader = reader;
    }

    public RootPrompts current() {
        try {
            String runId = AgentContext.getRunId();
            require(runId != null && !runId.isBlank(), "读取托管正文缺少当前运行");
            RuntimeCallContext context = contexts.current(runId, "prompt-read:" + java.util.UUID.randomUUID());
            require(context != null && context.hasRoot() && context.hasTarget()
                    && runId.equals(context.getCurrentRunId()), "正文读取缺少已确认的根上下文");
            String rootId = context.getRoot().getRootId();
            AgentRun root = runs.findById(rootId);
            require(root != null && rootId.equals(root.getId()), "正文读取的原根记录不存在");
            JsonNode ext = json.readTree(root.getExt());
            require(ext != null && ext.isObject(), "原根上下文无法读取");
            PromptRunSelection selection = PromptRunSelection.fromExt(ext.get("prompt_selection"));
            var binding = selection.racingSelection();
            require(binding.getRoot().equals(context.getRoot())
                    && sameTarget(binding.getTarget(), context.getTarget())
                    && PromptSelectionCoordinator.SELECTION_KEY.equals(binding.getSelectionKey())
                    && PromptSelectionCoordinator.BUSINESS_NAME.equals(binding.getOrigin().getBusinessName()),
                    "正文选择与原根绑定不一致");
            JsonNode date = ext.get(PromptSelectionCoordinator.REFERENCE_DATE_FIELD);
            require(date != null && date.isTextual()
                    && LocalDate.parse(date.textValue()).equals(selection.referenceDate()), "原根固定日期与选择不一致");
            return new RootPrompts(context, selection, freshness(ext.get("data_freshness")));
        } catch (RuntimeException failure) {
            throw blocked(failure);
        } catch (Exception failure) {
            throw unreadable("原根正文上下文无法读取");
        }
    }

    public Map<String, PromptVersionBody> read(RootPrompts root, Map<String, String> expectedPurposes) {
        try {
            require(root != null, "正文读取缺少原根上下文");
            return reader.readFragments(root.context(), root.selection().racingSelection(), expectedPurposes);
        } catch (RuntimeException failure) {
            throw blocked(failure);
        }
    }

    /** 按已选集合的结构化成员名枚举要求，序号可以有空缺，不依赖本地资源条数。 */
    public List<PromptVersionBody> requirements(RootPrompts root) {
        try {
            require(root != null, "要求读取缺少原根上下文");
            var set = prompts.readSet(ReadPromptSetVersionRequest.newBuilder().setContext(root.context())
                    .setVersion(root.selection().racingSelection().getSelectedTarget().getPromptSetVersion()).build());
            List<PromptSetMember> members = new ArrayList<>();
            for (PromptSetMember member : set.getMembersList()) {
                if (member.getFragmentName().startsWith(REQUIREMENTS_PREFIX)) {
                    require(member.getFragmentName().substring(REQUIREMENTS_PREFIX.length()).matches("[0-9]+"),
                            "要求片段的序号无效");
                    members.add(member);
                }
            }
            require(!members.isEmpty(), "原集合缺少Python要求片段组");
            members.sort(Comparator.comparing((PromptSetMember member) -> new BigInteger(
                    member.getFragmentName().substring(REQUIREMENTS_PREFIX.length())))
                    .thenComparing(PromptSetMember::getFragmentName));
            Map<String, String> expected = new LinkedHashMap<>();
            members.forEach(member -> expected.put(member.getFragmentName(), "PYTHON_REQUIREMENT"));
            Map<String, PromptVersionBody> bodies = read(root, expected);
            return members.stream().map(member -> bodies.get(member.getFragmentName())).toList();
        } catch (RuntimeException failure) {
            throw blocked(failure);
        }
    }

    /** 每个版本只渲染原模板一次，并仅并入该模板实际采用的指令变量来源。 */
    public PromptText render(PromptVersionBody body, Map<String, String> dataValues,
                             Map<String, PromptText> instructionValues) {
        require(body != null, "正文版本不可读取");
        Map<String, String> values = new LinkedHashMap<>(dataValues == null ? Map.of() : dataValues);
        Map<String, PromptText> instructions = instructionValues == null ? Map.of() : instructionValues;
        List<PromptVersionSource> embedded = new ArrayList<>();
        instructions.forEach((name, text) -> {
            if (values.containsKey(name)) {
                throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_VARIABLE_INVALID, name);
            }
            values.put(name, text == null ? null : text.text());
        });
        for (var variable : body.variables()) {
            PromptText text = instructions.get(variable.name());
            if (text != null) {
                if (!text.text().isEmpty() && text.sources().isEmpty()) {
                    throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, variable.name());
                }
                embedded.addAll(text.sources());
            }
        }
        PromptText rendered = PromptTemplateRenderer.render(body, values);
        List<PromptVersionSource> sources = new ArrayList<>(rendered.sources());
        sources.addAll(embedded);
        return new PromptText(rendered.text(), sources);
    }

    private static DataFreshnessFacts freshness(JsonNode node) {
        if (node == null || node.isNull()) return null;
        require(node.isObject(), "原根数据时效不是对象");
        return new DataFreshnessFacts(optionalText(node, "start_date"), optionalText(node, "end_date"),
                optionalText(node, "as_of_date"), optionalText(node, "description"));
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        require(value.isTextual(), "原根数据时效字段无效：" + field);
        return value.textValue();
    }

    private static boolean sameTarget(world.willfrog.racing.idl.TargetIdentity left,
                                      world.willfrog.racing.idl.TargetIdentity right) {
        return left.getEnvironment().equals(right.getEnvironment())
                && left.getTargetScopeInstanceId().equals(right.getTargetScopeInstanceId())
                && left.getTargetRouteTag().equals(right.getTargetRouteTag())
                && left.getTargetDeploymentId().equals(right.getTargetDeploymentId());
    }

    private static RuntimeException blocked(RuntimeException failure) {
        ModelSendBlocks.rethrowIfBlocked(failure);
        return failure instanceof RacingCallException racing
                ? new ModelSendBlockedException(ModelSendBlocked.PROMPT_UNREADABLE,
                        "原根选择或正文读取未确认", RacingFailure.from(racing.error()))
                : unreadable("原根选择或正文读取未确认");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw unreadable(reason);
    }

    private static ModelSendBlockedException unreadable(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_UNREADABLE, reason);
    }

    /** 局部读取上下文，只有完成持久根核对后才能创建。 */
    public static final class RootPrompts {
        private final RuntimeCallContext context;
        private final PromptRunSelection selection;
        private final DataFreshnessFacts freshness;

        private RootPrompts(RuntimeCallContext context, PromptRunSelection selection, DataFreshnessFacts freshness) {
            this.context = context;
            this.selection = selection;
            this.freshness = freshness;
        }

        public RuntimeCallContext context() { return context; }
        public PromptRunSelection selection() { return selection; }
        public LocalDate referenceDate() { return selection.referenceDate(); }
        public DataFreshnessFacts freshness() { return freshness; }
    }

    public record DataFreshnessFacts(String startDate, String endDate, String asOfDate, String description) { }
}
