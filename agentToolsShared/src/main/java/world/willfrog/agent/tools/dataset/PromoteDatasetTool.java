package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import world.willfrog.datafrog.idl.GenMethod;
import world.willfrog.datafrog.idl.RegisterDataObjectRequest;
import world.willfrog.datafrog.idl.RegisterDataObjectResponse;
import world.willfrog.datafrog.idl.SourceType;
import world.willfrog.datafrog.idl.Visibility;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 014-data-04：把运行内 dataset 提升为 DataFrog 平台数据对象的工具。
 *
 * <p>提升路径（方案 D4-4）：读运行内 dataset 内容（走 registry 快照的 persistedPath，与取数 /
 * 查看工具同一持久化读取通道）→ 作为 UTF-8 CSV 传 RegisterDataObject（source_type=USER、
 * gen_method=AGENT、owner=当前登录用户、visibility=PRIVATE、inputs 空，name / description
 * 走工具参数）→ 直接登记，不走 03 的管理员审核；该路径不写 continuous_complete_date 与
 * has_gap_after_watermark（freshness 按「还没有连续完整日」返回）。
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
     * @param dataset_number listMyData 返回的 run 级 dataset 编号
     * @param name           平台对象名称（登记后 listMyData 平台条目区可见）
     * @param description    平台对象描述（可空）
     */
    @Tool
    public String promoteDatasetToPlatform(
            @P(value = "listMyData 返回的 run 级 dataset 编号", required = true) Integer dataset_number,
            @P(value = "平台对象名称", required = true) String name,
            @P(value = "平台对象描述（可空）", required = false) String description
    ) {
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
                        "dataset_number must be a positive run-level number from listMyData",
                        Map.of("dataset_number", dataset_number == null ? "" : dataset_number.toString()));
            }
            String safeName = name == null ? "" : name.trim();
            if (safeName.isEmpty()) {
                return fail("INVALID_NAME", "name must not be blank", Map.of());
            }

            // 持久化读取通道与取数 / 查看工具一致：registry 快照 → persistedPath → 文件字节。
            AgentRunDatasetEntry entry = agentRunDatasetRegistry
                    .findDatasetByNumber(runId, dataset_number)
                    .orElse(null);
            if (entry == null) {
                List<Integer> legalNumbers = agentRunDatasetRegistry.listDatasetNumbers(runId);
                return fail("INVALID_DATASET_NUMBER",
                        "dataset_number is not a valid run-level dataset number; use listMyData first",
                        Map.of("dataset_number", dataset_number.toString(),
                                "legal_dataset_numbers", legalNumbers));
            }
            if (entry.isPlatformObject()) {
                // 平台对象挂载副本再提升回平台没有意义（内容已在平台上），拒绝并提示直接用原对象。
                return fail("PLATFORM_OBJECT_SELF_PROMOTE",
                        "this dataset is already a platform object mount; reference its ds_ id directly",
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

            // RegisterDataObject 直登（无审核分支）。除方案 D4-4 指定字段外其余声明字段不填，
            // 服务端约定校验（semantic_refs / primary_key / coverage 等）如不通过，
            // 错误码与 message 原样透传给调用方。
            RegisterDataObjectRequest request = RegisterDataObjectRequest.newBuilder()
                    .setName(safeName)
                    .setDescription(nvl(description))
                    .setSourceType(SourceType.USER)
                    .setOwner(userId)
                    .setVisibility(Visibility.PRIVATE)
                    .setGenMethod(GenMethod.AGENT)
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
            log.error("promoteDatasetToPlatform failed", e);
            return fail("TOOL_ERROR", "promoteDatasetToPlatform invocation error",
                    Map.of("message", nvl(e.getMessage())));
        }
    }

    private String ok(Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", "promoteDatasetToPlatform");
        payload.put("data", data == null ? Map.of() : data);
        payload.put("error", null);
        return writeJson(payload);
    }

    private String fail(String code, String message, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", "promoteDatasetToPlatform");
        payload.put("data", Map.of());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", nvl(code));
        err.put("message", nvl(message));
        err.put("details", details == null ? Map.of() : details);
        payload.put("error", err);
        return writeJson(payload);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"promoteDatasetToPlatform\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\""
                    + escapeJson(nvl(e.getMessage())) + "\"}}";
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
}
