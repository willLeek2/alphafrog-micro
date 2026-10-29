package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.agent.tools.prompt.ProducerPromptTexts;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import com.google.protobuf.ByteString;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.ToolDescriptionTexts;
import world.willfrog.agent.tools.datafrog.DataFrogObjectClient;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.datafrog.idl.DataObjectCard;
import world.willfrog.datafrog.idl.Frequency;
import world.willfrog.datafrog.idl.GenMethod;
import world.willfrog.datafrog.idl.RegisterDataObjectRequest;
import world.willfrog.datafrog.idl.RegisterDataObjectResponse;
import world.willfrog.datafrog.idl.SemanticRef;
import world.willfrog.datafrog.idl.SourceType;
import world.willfrog.datafrog.idl.Visibility;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 014-data-04：把运行内 dataset 提升为 DataFrog 平台数据对象的工具。
 *
 * <p>提升路径（方案 D4-4）：读运行内 dataset 内容（走 registry 快照的 persistedPath，与取数 /
 * 查看工具同一持久化读取通道）→ 作为 UTF-8 CSV 传 RegisterDataObject（source_type=USER、
 * gen_method=AGENT、owner=当前登录用户、visibility=PRIVATE、inputs 空）→ 直接登记，不走
 * 03 的管理员审核；该路径不写 continuous_complete_date 与 has_gap_after_watermark
 * （freshness 按「还没有连续完整日」返回）。
 *
 * <p>必填约定字段（bob 节点 c 裁定 d61398da：不放宽 01 的 ConventionValidator，也不为
 * USER+AGENT 开例外，请求里把字段带齐）：description 非空、semantic_refs（name@version
 * 引用列表，逗号分隔）、primary_key（列名列表，逗号分隔）、coverage_scope、
 * coverage_start / coverage_end（YYYY-MM-DD）全部为工具必填参数。模型给不出已登记的
 * 语义定义时服务端登记失败，现有错误码与报文原样透传（本类不做第二份约定校验）。
 *
 * <p>写给模型的完整说明只维护在 classpath 权威文件（照 PythonSandboxTools 的做法：
 * 说明文件缺失即加载失败，Java 里不放第二份正文）。
 */
@Component
@Slf4j
public class PromoteDatasetTool {

    /** 权威说明文件入口（prompts/tools/descriptions/promoteDatasetToPlatform.txt）。 */
    public static String loadToolDescription() {
        return ToolDescriptionTexts.require("promoteDatasetToPlatform");
    }

    private final ObjectMapper objectMapper;

    @Autowired
    private ProducerPromptTexts promptTexts;

    /** 可选注入（required=false），便于纯单元测试启动。 */
    @Autowired(required = false)
    private AgentRunDatasetRegistry agentRunDatasetRegistry;

    /** 可选注入：DataFrog 平台数据对象消费端。 */
    @Autowired(required = false)
    private DataFrogObjectClient dataFrogObjectClient;

    @Autowired
    public PromoteDatasetTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 暴露给单测 / 同包注入。 */
    void setAgentRunDatasetRegistry(AgentRunDatasetRegistry registry) {
        this.agentRunDatasetRegistry = registry;
    }

    void setDataFrogObjectClient(DataFrogObjectClient client) {
        this.dataFrogObjectClient = client;
    }

    /**
     * 把当前 run 的一个 dataset 提升为平台数据对象（直接登记，无审核分支）。
     *
     * <p>除 dataset_number / name 外，服务端约定校验要求的字段全部为必填工具参数：
     * description（非空）、semantic_refs（逗号分隔 name@version）、primary_key（逗号分隔
     * 列名）、coverage_scope、coverage_start / coverage_end（YYYY-MM-DD）。本方法只做
     * 「能不能拼出合法请求」的格式校验，约定语义（语义定义是否已登记等）交给服务端
     * ConventionValidator，失败错误码与报文原样透传。
     *
     * @param dataset_number listMyData 返回的 run 级 dataset 编号
     * @param name           平台对象名称（登记后 listMyData 平台条目区可见）
     * @param description    平台对象描述（必填，非空）
     * @param semantic_refs  语义定义引用列表，逗号分隔，每项 name@version
     * @param primary_key    主键列名列表，逗号分隔
     * @param coverage_scope 覆盖范围说明（必填，非空）
     * @param coverage_start 数据覆盖起始日期，YYYY-MM-DD
     * @param coverage_end   数据覆盖结束日期，YYYY-MM-DD
     */
    @Tool
    public String promoteDatasetToPlatform(
            @P(value = "listMyData 返回的 run 级 dataset 编号", required = true) Integer dataset_number,
            @P(value = "平台对象名称", required = true) String name,
            @P(value = "平台对象描述（必填，非空）", required = true) String description,
            @P(value = "必填，逗号分隔的语义定义引用列表，每项 name@version，如 \"price_daily@1,adj_factor@2\"；"
                    + "语义定义必须已在平台登记，否则登记失败", required = true) String semantic_refs,
            @P(value = "必填，逗号分隔的主键列名列表，如 \"trade_date,ts_code\"", required = true) String primary_key,
            @P(value = "必填，覆盖范围说明，如 \"A股股票日线\"", required = true) String coverage_scope,
            @P(value = "必填，数据覆盖起始日期，格式 YYYY-MM-DD", required = true) String coverage_start,
            @P(value = "必填，数据覆盖结束日期，格式 YYYY-MM-DD", required = true) String coverage_end,
            @P(value = "必填，更新频率：daily（每日更新）或 single_period（一次性单期）", required = true) String frequency
    ) {
        return promoteDatasetToPlatformPrompt(dataset_number, name, description, semantic_refs, primary_key, coverage_scope, coverage_start, coverage_end, frequency).text();
    }

    public PromptText promoteDatasetToPlatformPrompt(Integer dataset_number, String name, String description, String semantic_refs, String primary_key, String coverage_scope, String coverage_start, String coverage_end, String frequency) {
        try {
            String runId = AgentContext.getRunId();
            String userId = AgentContext.getUserId();
            if (runId == null || runId.isBlank() || agentRunDatasetRegistry == null) {
                return fail("RUN_LEVEL_IDS_UNAVAILABLE",
                        "promoteDatasetToPlatform requires an active run and AgentRunDatasetRegistry",
                        Map.of("runId", nvl(runId)));
            }
            if (userId == null || userId.isBlank()) {
                return fail("USER_CONTEXT_UNAVAILABLE",
                        "promoteDatasetToPlatform requires the current user context",
                        Map.of());
            }
            if (dataset_number == null || dataset_number <= 0) {
                return fail("INVALID_DATASET_NUMBER",
                        policy("promoteDatasetNumberAdvice"),
                        Map.of("dataset_number", dataset_number == null ? "" : dataset_number.toString()));
            }
            String safeName = name == null ? "" : name.trim();
            if (safeName.isEmpty()) {
                return fail("INVALID_NAME", "name must not be blank", Map.of());
            }
            String safeDescription = description == null ? "" : description.trim();
            if (safeDescription.isEmpty()) {
                return fail("INVALID_DESCRIPTION", "description must not be blank", Map.of());
            }
            List<SemanticRef> semanticRefs = parseSemanticRefs(semantic_refs);
            if (semanticRefs == null) {
                return fail("INVALID_SEMANTIC_REFS",
                        policy("promoteSemanticRefsAdvice"),
                        Map.of("semantic_refs", nvl(semantic_refs)));
            }
            List<String> primaryKeyColumns = splitCsvNames(primary_key);
            if (primaryKeyColumns == null) {
                return fail("INVALID_PRIMARY_KEY",
                        policy("promotePrimaryKeyAdvice"),
                        Map.of("primary_key", nvl(primary_key)));
            }
            String safeCoverageScope = coverage_scope == null ? "" : coverage_scope.trim();
            if (safeCoverageScope.isEmpty()) {
                return fail("INVALID_COVERAGE_SCOPE", "coverage_scope must not be blank", Map.of());
            }
            if (parseIsoDate(coverage_start) == null) {
                return fail("INVALID_COVERAGE_DATE", policy("promoteCoverageStartAdvice"),
                        Map.of("coverage_start", nvl(coverage_start)));
            }
            if (parseIsoDate(coverage_end) == null) {
                return fail("INVALID_COVERAGE_DATE", policy("promoteCoverageEndAdvice"),
                        Map.of("coverage_end", nvl(coverage_end)));
            }
            // 261028 泳道实测修：frequency 是库侧 NOT NULL 声明字段，此前不传，服务端
            // 按未声明炸成 INTERNAL+SQL 原文；现在工具侧必收、本地先校验词表。
            Frequency parsedFrequency = parseFrequency(frequency);
            if (parsedFrequency == null) {
                return fail("INVALID_FREQUENCY",
                        policy("promoteFrequencyAdvice"),
                        Map.of("frequency", nvl(frequency)));
            }

            // 持久化读取通道与取数 / 查看工具一致：registry 快照 → persistedPath → 文件字节。
            AgentRunDatasetEntry entry = agentRunDatasetRegistry
                    .findDatasetByNumber(runId, dataset_number)
                    .orElse(null);
            if (entry == null) {
                List<Integer> legalNumbers = agentRunDatasetRegistry.listDatasetNumbers(runId);
                return fail("INVALID_DATASET_NUMBER",
                        policy("promoteDatasetLookupAdvice"),
                        Map.of("dataset_number", dataset_number.toString(),
                                "legal_dataset_numbers", legalNumbers));
            }
            if (entry.isPlatformObject()) {
                // 平台对象挂载副本再提升回平台没有意义（内容已在平台上），拒绝并提示直接用原对象。
                return fail("PLATFORM_OBJECT_SELF_PROMOTE",
                        policy("promoteMountedObjectAdvice"),
                        Map.of("dataset_number", dataset_number.toString(),
                                "platform_object_id", entry.originalId()));
            }

            byte[] csvBytes;
            try {
                csvBytes = Files.readAllBytes(Paths.get(entry.persistedPath()));
            } catch (Exception readFailure) {
                return fail("DATASET_FILE_UNREADABLE",
                        "cannot read the dataset file behind this run-level number: "
                                + readFailure.getMessage(),
                        Map.of("dataset_number", dataset_number.toString()));
            }
            if (csvBytes.length == 0) {
                return fail("EMPTY_DATASET",
                        "the dataset file is empty; nothing to promote",
                        Map.of("dataset_number", dataset_number.toString()));
            }

            if (dataFrogObjectClient == null) {
                return fail("PLATFORM_OBJECT_CHANNEL_UNAVAILABLE",
                        "Platform object channel is not wired; promotion is unavailable in this deployment",
                        Map.of());
            }

            // RegisterDataObject 直登（无审核分支）。方案 D4-4 固定字段 + 必填约定字段
            // （description / semantic_refs / primary_key / coverage_scope / coverage_start /
            // coverage_end / frequency，bob d61398da：字段带齐，不放宽服务端 ConventionValidator；
            // frequency 261028 补——库侧 NOT NULL，首态遗漏，属约定字段清单补齐）外
            // 其余声明字段不填；服务端约定校验如不通过，错误码与 message 原样透传给调用方。
            RegisterDataObjectRequest request = RegisterDataObjectRequest.newBuilder()
                    .setName(safeName)
                    .setDescription(safeDescription)
                    .setSourceType(SourceType.USER)
                    .setOwner(userId)
                    .setVisibility(Visibility.PRIVATE)
                    .setGenMethod(GenMethod.AGENT)
                    .setFrequency(parsedFrequency)
                    .addAllSemanticRefs(semanticRefs)
                    .setCoverageScope(safeCoverageScope)
                    .addAllPrimaryKey(primaryKeyColumns)
                    .setCoverageStart(coverage_start.trim())
                    .setCoverageEnd(coverage_end.trim())
                    .setCsvContent(ByteString.copyFrom(csvBytes))
                    .build();
            RegisterDataObjectResponse response = dataFrogObjectClient.registerDataObject(request);
            if (response.getResult() == null || !response.getResult().getOk()) {
                String code = response.getResult() == null
                        ? "NULL_RESULT" : response.getResult().getCode().name();
                String message = response.getResult() == null
                        ? "" : response.getResult().getMessage();
                return fail("PLATFORM_REGISTER_REJECTED",
                        "DataFrog rejected the registration: code=" + code + " message=" + message,
                        Map.of("code", code, "message", nvl(message)));
            }

            DataObjectCard card = response.getCard();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("platform_object_id", card.getId());
            data.put("name", card.getName());
            data.put("version", card.getCurrentVersion());
            data.put("row_count", card.getRowCount());
            data.put("dataset_number", entry.number());
            data.put("note", "对象已登记为当前用户私有平台对象（不走审核）；listMyData 的平台数据对象区可见");
            return ok(data);
        } catch (Exception e) {
            ModelSendBlocks.rethrowIfBlocked(e);
            log.error("promoteDatasetToPlatform failed", e);
            return fail("TOOL_ERROR", "promoteDatasetToPlatform invocation error",
                    Map.of("message", nvl(e.getMessage())));
        }
    }

    /**
     * 解析 semantic_refs 工具参数：逗号分隔的 name@version 列表（如 price_daily@1,adj_factor@2）。
     * 任一项缺 @、名字空白或版本号不是正整数都返回 null，由调用方转 INVALID_SEMANTIC_REFS。
     * 语义定义是否已在平台登记不在本地判断，交给服务端约定校验并原样透传错误。
     */
    private static List<SemanticRef> parseSemanticRefs(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<SemanticRef> refs = new ArrayList<>();
        for (String token : raw.split(",")) {
            String item = token.trim();
            int at = item.lastIndexOf('@');
            if (at <= 0 || at == item.length() - 1) {
                return null;
            }
            String name = item.substring(0, at).trim();
            String versionText = item.substring(at + 1).trim();
            int version;
            try {
                version = Integer.parseInt(versionText);
            } catch (NumberFormatException e) {
                return null;
            }
            if (name.isEmpty() || version <= 0) {
                return null;
            }
            refs.add(SemanticRef.newBuilder().setName(name).setVersion(version).build());
        }
        return refs.isEmpty() ? null : refs;
    }

    /**
     * 解析 primary_key 工具参数：逗号分隔的列名列表。空白、含空项返回 null，
     * 由调用方转 INVALID_PRIMARY_KEY。列名是否真实存在于 dataset 不在本地判断。
     */
    private static List<String> splitCsvNames(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String token : raw.split(",")) {
            String name = token.trim();
            if (name.isEmpty()) {
                return null;
            }
            names.add(name);
        }
        return names.isEmpty() ? null : names;
    }

    /** 词表校验：daily→DAILY、single_period→SINGLE_PERIOD（大小写不敏感），其余 null。 */
    private static Frequency parseFrequency(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "daily" -> Frequency.DAILY;
            case "single_period" -> Frequency.SINGLE_PERIOD;
            default -> null;
        };
    }

    /** 严格 YYYY-MM-DD 日期解析（LocalDate.parse 不接受 2026-1-1 这类非补零形态）；不合法返回 null。 */
    private static LocalDate parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private PromptText ok(Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", "promoteDatasetToPlatform");
        payload.put("data", data == null ? Map.of() : data);
        payload.put("error", null);
        return writeJson(payload);
    }

    private PromptText fail(String code, Object message, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", "promoteDatasetToPlatform");
        payload.put("data", Map.of());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", nvl(code));
        err.put("message", message == null ? "" : message);
        err.put("details", details == null ? Map.of() : details);
        payload.put("error", err);
        return writeJson(payload);
    }

    private PromptText writeJson(Object payload) {
        try {
            return ProducerPromptTexts.json(objectMapper, payload);
        } catch (Exception e) {
            ModelSendBlocks.rethrowIfBlocked(e);
            return PromptText.runtimeData("{\"ok\":false,\"tool\":\"promoteDatasetToPlatform\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\""
                    + escapeJson(nvl(e.getMessage())) + "\"}}");
        }
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private String escapeJson(String text) {
        return nvl(text)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
    private PromptText policy(String fragment) {
        if (promptTexts == null) throw new world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException(
                world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_SOURCE_MISSING, "producer-policy");
        return promptTexts.policy(fragment);
    }

}
