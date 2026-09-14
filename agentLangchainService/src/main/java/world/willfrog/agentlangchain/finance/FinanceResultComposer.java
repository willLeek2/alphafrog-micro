package world.willfrog.agentlangchain.finance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.finance.FinanceMethodResolution;
import world.willfrog.agent.platform.finance.FinanceMethodResolutionQuery;
import world.willfrog.agent.platform.finance.FinanceMetricRecord;
import world.willfrog.agent.platform.finance.FinanceRecordQuery;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.tools.finance.FinanceMethodSpecCatalog;
import world.willfrog.agent.tools.finance.FinanceResultModelProjector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * writeFinalAnswer 的服务端结果块编排器（Spec §11）。
 *
 * <p>流程：模型生成块外说明 → 按 runId 查询 renderable 记录 → 逐记录经 canonical projector
 * 投影（记录级 fail-closed 跳过）→ 追加确定性三列 Markdown 块 → 写耐久事件 → 返回最终字符串。</p>
 *
 * <p>渲染期后台核对：快照按 runId + sourceResolverToolCallId + 方法三元组精确查询；
 * 目标环境与实际环境不一致时写 FINANCE_CROSS_ENVIRONMENT 事件（稳定去重键），缺失/跨 run
 * 不猜最近一次建议。事件与内部身份永不进入 Markdown；组合器任何异常都退回模型原文
 * （普通任务仍成功）。</p>
 */
@Component
@Slf4j
public class FinanceResultComposer {

    public static final String EVENT_RESULT_BLOCK_RENDERED = "FINANCE_RESULT_BLOCK_RENDERED";
    public static final String EVENT_CROSS_ENVIRONMENT = "FINANCE_CROSS_ENVIRONMENT";

    /**
     * 三列 cell 的后台身份 denylist。命中任一 token 的投影记录 fail-closed 跳过
     * （Spec §11/§17 停止条件，codex 935bef41/b4a4d737）：CUSTOM 记录的 formulaDescription
     * 会逐字成为 howCalculated，schema 只限长度，公开渲染边界必须拦截内部身份 token。
     * 匹配口径：{@code sha256:} 原始前缀硬拦；其余 token 对 cell 做 separator-insensitive
     * compact（去非字母数字 + 小写）后 contains——camelCase/snake_case/kebab/分词形式同键
     * 一并拦截。token 均为具体身份形状，合法自然语言说明（尤其中文）不受影响。
     */
    static final List<String> CELL_DENYLIST = List.of(
            // 通用类别词（codex 52799ba0）：§11/§16.3 版本/环境/后台身份类别本身
            // 不得进最终 Markdown，无论是否带 Id/Version/Digest 复合形状
            "digest", "environment", "image", "package", "version", "evidence",
            "record", "batch", "block", "task", "dataset", "toolcall",
            "methodid", "methodversion", "specdigest",
            "recordid", "recordindex", "recorddigest", "rawdigest", "inputrefs",
            "runid", "todoid", "taskid", "batchid", "blockid", "datasetid",
            "environmentid", "actualenvironmentid", "targetenvironmentid",
            "imagedigest", "imageref", "imageid", "runtimeimage",
            "librarysetdigest", "catalogdigest", "resolutioncontentdigest",
            "packageapis", "packagename", "packageversion", "apicompatrange", "apiversion",
            "rendererversion", "resolverpromptversion", "resolverschemaversion", "schemaversion",
            "declaredevidence", "effectiveinternalevidence",
            "executepythontoolcallid", "toolcallid",
            "sourceresolvertoolcallid", "resolvertoolcallid", "sourceresolver",
            "librarycalldeclared", "customwithchecks", "customunverified",
            "financeresultblockrendered", "financecrossenvironment",
            "financeblock", "financerecord", "financeenvironment", "financerun", "financerenderer"
    );

    private final FinanceRecordQuery recordQuery;
    private final FinanceMethodResolutionQuery resolutionQuery;
    private final FinanceResultModelProjector projector;
    private final FinanceMethodSpecCatalog specCatalog;
    private final FinanceResultBlockRenderer renderer;
    private final AgentRunEventService eventService;
    private final ObjectMapper objectMapper;

    public FinanceResultComposer(FinanceRecordQuery recordQuery,
                                 FinanceMethodResolutionQuery resolutionQuery,
                                 FinanceResultModelProjector projector,
                                 FinanceMethodSpecCatalog specCatalog,
                                 FinanceResultBlockRenderer renderer,
                                 AgentRunEventService eventService,
                                 ObjectMapper objectMapper) {
        this.recordQuery = recordQuery;
        this.resolutionQuery = resolutionQuery;
        this.projector = projector;
        this.specCatalog = specCatalog;
        this.renderer = renderer;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    /**
     * 把确定性结果块追加到模型生成的最终答案之后。
     *
     * @param runId     当前 run
     * @param userId    当前用户（事件写入需要）
     * @param modelText 模型生成的块外说明
     * @return 最终字符串；无记录/无可投影记录/任何内部失败时返回模型原文
     */
    public String appendFinanceResultBlock(String runId, String userId, String modelText) {
        String text = modelText == null ? "" : modelText;
        if (runId == null || runId.isBlank() || userId == null || userId.isBlank()) {
            return text;
        }
        try {
            return doAppend(runId, userId, text);
        } catch (Exception e) {
            log.warn("Finance result block composition failed, returning model text as-is: {}", e.getMessage());
            return text;
        }
    }

    private String doAppend(String runId, String userId, String modelText) {
        List<FinanceMetricRecord> records = new ArrayList<>(recordQuery.listRenderableByRun(runId));
        if (records.isEmpty()) {
            return modelText;
        }
        records.sort(Comparator.comparing(FinanceMetricRecord::getRecordIndex,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<FinanceResultBlockRenderer.Row> rows = new ArrayList<>();
        List<String> renderedRecordIds = new ArrayList<>();
        Set<String> environmentIds = new LinkedHashSet<>();
        List<CrossEnvironmentFact> crossEnvironmentFacts = new ArrayList<>();

        for (FinanceMetricRecord record : records) {
            if (record == null) {
                continue;
            }
            CrossEnvironmentFact crossEnvironment = checkCrossEnvironment(runId, record);
            if (crossEnvironment != null) {
                crossEnvironmentFacts.add(crossEnvironment);
            }
            Optional<FinanceResultModelProjector.FinanceResultProjection> projection = project(record);
            if (projection.isEmpty()) {
                continue; // 记录级 fail-closed：不可投影即跳过，不在块中残留任何痕迹
            }
            FinanceResultModelProjector.FinanceResultProjection p = projection.get();
            String formattedValue = renderer.formatValue(p.value(), displayFormatOf(record));
            if (containsDenylistedToken(p.method(), formattedValue, p.howCalculated())) {
                continue; // cell denylist 命中（codex 935bef41）：该记录 fail-closed 跳过
            }
            rows.add(new FinanceResultBlockRenderer.Row(p.method(), formattedValue, p.howCalculated()));
            renderedRecordIds.add(record.getRecordId());
            if (record.getActualEnvironmentId() != null && !record.getActualEnvironmentId().isBlank()) {
                environmentIds.add(record.getActualEnvironmentId().trim());
            }
        }
        if (rows.isEmpty()) {
            return modelText;
        }

        String markdown = renderer.renderTable(rows);
        String blockId = renderer.stableBlockId(runId, renderedRecordIds);
        String environmentId = environmentIds.size() == 1 ? environmentIds.iterator().next() : null;
        FinanceResultBlock block = new FinanceResultBlock(
                blockId, markdown, renderedRecordIds, environmentId, FinanceResultBlockRenderer.RENDERER_VERSION);

        writeEvents(runId, userId, block, crossEnvironmentFacts);
        return modelText.isBlank() ? markdown : modelText + "\n\n" + markdown;
    }

    /**
     * 渲染期目标/实际环境核对。仅在快照精确命中且两侧环境 id 均非空时才判定不一致；
     * 快照缺失、跨 run 或字段不完整一律返回 null（保留记录、不猜最近一次建议）。
     */
    private CrossEnvironmentFact checkCrossEnvironment(String runId, FinanceMetricRecord record) {
        String sourceResolverToolCallId = record.getSourceResolverToolCallId();
        if (sourceResolverToolCallId == null || sourceResolverToolCallId.isBlank()) {
            return null;
        }
        FinanceMethodResolution snapshot = resolutionQuery.findExact(
                runId,
                sourceResolverToolCallId,
                record.getMethodId(),
                record.getMethodVersion(),
                record.getSpecDigest());
        if (snapshot == null) {
            return null;
        }
        String target = snapshot.getTargetEnvironmentId();
        String actual = record.getActualEnvironmentId();
        if (target == null || target.isBlank() || actual == null || actual.isBlank()) {
            return null;
        }
        if (target.trim().equals(actual.trim())) {
            return null;
        }
        return new CrossEnvironmentFact(record.getRecordId(), target.trim(), actual.trim());
    }

    private Optional<FinanceResultModelProjector.FinanceResultProjection> project(FinanceMetricRecord record) {
        try {
            JsonNode valueNode = objectMapper.readTree(record.getValueJson());
            JsonNode parametersNode = objectMapper.readTree(record.getParametersJson());
            if (valueNode == null || !valueNode.isNumber()
                    || parametersNode == null || !parametersNode.isObject()) {
                return Optional.empty();
            }
            Map<String, Object> parameters = objectMapper.convertValue(
                    parametersNode, new TypeReference<Map<String, Object>>() { });
            FinanceResultModelProjector.FinanceDeclaredEvidence declaredEvidence =
                    parseDeclaredEvidence(record.getDeclaredEvidence());
            if (declaredEvidence == null) {
                return Optional.empty();
            }
            return projector.project(new FinanceResultModelProjector.FinanceResultProjectionInput(
                    record.getMethodId(),
                    record.getMethodVersion(),
                    record.getSpecDigest(),
                    valueNode.numberValue(),
                    record.getUnit(),
                    parameters,
                    record.getFormulaDescription(),
                    true,
                    declaredEvidence));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String displayFormatOf(FinanceMetricRecord record) {
        try {
            if (isBlank(record.getMethodId()) || isBlank(record.getMethodVersion())
                    || isBlank(record.getSpecDigest())) {
                return null;
            }
            return specCatalog.find(record.getMethodId(), record.getMethodVersion(), record.getSpecDigest())
                    .map(spec -> spec.getOutputs() != null && spec.getOutputs().size() == 1
                            ? spec.getOutputs().get(0).getDisplayFormat()
                            : null)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private FinanceResultModelProjector.FinanceDeclaredEvidence parseDeclaredEvidence(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FinanceResultModelProjector.FinanceDeclaredEvidence.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void writeEvents(String runId, String userId, FinanceResultBlock block,
                             List<CrossEnvironmentFact> crossEnvironmentFacts) {
        for (CrossEnvironmentFact fact : crossEnvironmentFacts) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("finance.run.id", runId);
            payload.put("finance.record.id", fact.recordId());
            payload.put("finance.environment.target", fact.targetEnvironmentId());
            payload.put("finance.environment.actual", fact.actualEnvironmentId());
            safeAppendOnce(runId, userId, EVENT_CROSS_ENVIRONMENT,
                    EVENT_CROSS_ENVIRONMENT + ":" + runId + ":" + fact.recordId(), payload);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("finance.block.id", block.blockId());
        payload.put("finance.record.count", block.recordIds().size());
        payload.put("finance.record.ids", List.copyOf(block.recordIds()));
        payload.put("finance.environment.id", block.environmentId() == null ? "" : block.environmentId());
        payload.put("finance.renderer.version", block.rendererVersion());
        safeAppendOnce(runId, userId, EVENT_RESULT_BLOCK_RENDERED,
                EVENT_RESULT_BLOCK_RENDERED + ":" + block.blockId(), payload);
    }

    /** 事件写入失败只记日志：结果块交付优先于审计事件，普通任务仍成功。 */
    private void safeAppendOnce(String runId, String userId, String eventType, String dedupeKey, Object payload) {
        try {
            eventService.appendOnce(runId, userId, eventType, dedupeKey, payload);
        } catch (Exception e) {
            log.warn("Finance event append failed (type={}, dedupeKey={}): {}", eventType, dedupeKey, e.getMessage());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 三列 cell 任一命中后台身份 token 即不可公开（sha256: 原始硬拦 + compact contains）。 */
    static boolean containsDenylistedToken(String... cells) {
        for (String cell : cells) {
            if (cell == null || cell.isEmpty()) {
                continue;
            }
            if (cell.toLowerCase(java.util.Locale.ROOT).contains("sha256:")) {
                return true;
            }
            String compact = compactCell(cell);
            for (String token : CELL_DENYLIST) {
                if (compact.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** separator-insensitive 归一：去非字母数字并小写（CJK 字符保留）。 */
    private static String compactCell(String cell) {
        StringBuilder sb = new StringBuilder(cell.length());
        for (int i = 0; i < cell.length(); i++) {
            char c = cell.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private record CrossEnvironmentFact(String recordId, String targetEnvironmentId, String actualEnvironmentId) {
    }
}
