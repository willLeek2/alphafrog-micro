package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 260623-harness-optimization-02: listMyData 工具。
 *
 * <p>用于 agent 不确定当前 run 有哪些可用 dataset / manifest，或 executePython 收到
 * 非法编号错误时的恢复路径。直接消费 {@link AgentRunDatasetRegistry} 的 snapshot。
 *
 * <p>输入参数：
 * <ul>
 *   <li>{@code query_type} — {@code dataset} 或 {@code manifest}，必填</li>
 *   <li>{@code from_ts_code} — 可选，过滤 ts_code 包含该子串的条目（含 {@code #} 多 ts_code）</li>
 *   <li>{@code grep} — 可选，对 {@code originalId} 做大小写不敏感子串匹配</li>
 *   <li>{@code offset} / {@code limit} — 可选，分页（默认 offset=0, limit=50，limit 上限 200）</li>
 *   <li>{@code related_dataset_ids} — 可选（manifest only），用 {@code #} 分隔，过滤 related 包含任一指定 id 的 manifest</li>
 * </ul>
 *
 * <p>输出字段：
 * <ul>
 *   <li>{@code data.query_type} / {@code data.run_id}</li>
 *   <li>{@code data.total_matched} — 过滤后总数（分页前）</li>
 *   <li>{@code data.returned_count} — 本次返回条目数</li>
 *   <li>{@code data.offset} / {@code data.limit}</li>
 *   <li>{@code data.entries[]} — 每条含 {@code number / originalId / persistedPath / fromTsCode / sortKey / artifactType}（manifest 还含 {@code relatedDatasetIds}）</li>
 * </ul>
 */
@Component
@Slf4j
public class ListMyDataTool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ObjectMapper objectMapper;

    /**
     * 260623-harness-optimization-02: 复用 {@link AgentRunDatasetRegistry} 拿 run-scope snapshot。
     * 可选注入（{@code required=false}），便于纯单元测试启动。
     */
    @Autowired(required = false)
    private AgentRunDatasetRegistry agentRunDatasetRegistry;

    public ListMyDataTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 暴露给单测 / 同包注入。
     */
    void setAgentRunDatasetRegistry(AgentRunDatasetRegistry registry) {
        this.agentRunDatasetRegistry = registry;
    }

    @Tool("""
        列出当前 agent run 已落盘的 dataset / manifest。仅依赖 in-memory registry（不消耗外部配额），用于：

        1. executePython 收到 ILLEGAL_RUN_LEVEL_IDS 错误时恢复（先 listMyData 查合法编号）
        2. 多步工作流中回顾前面步骤落下了什么数据
        3. 发现某个 manifest 包含哪些相关 dataset

        参数：
          query_type          - 必填，取值 "dataset" 或 "manifest"
          from_ts_code        - 可选，过滤 from_ts_code 包含该子串的条目（multi-ts-code 用 # 分隔）
          grep                - 可选，对 originalId 做大小写不敏感子串匹配
          offset              - 可选，分页起始（默认 0）
          limit               - 可选，单页返回上限（默认 50，上限 200）
          related_dataset_ids - 可选（manifest only），用 # 分隔，过滤 related 包含任一指定 id 的 manifest

        返回：{ ok, data: { query_type, run_id, total_matched, returned_count, offset, limit, entries: [...] }, error }
        """)
    public String listMyData(
            @P(value = "查询类型：dataset | manifest", required = true) String query_type,
            @P(value = "可选，过滤 from_ts_code 包含该子串的条目", required = false) String from_ts_code,
            @P(value = "可选，对 originalId 做大小写不敏感子串匹配", required = false) String grep,
            @P(value = "可选，分页起始（默认 0）", required = false) Integer offset,
            @P(value = "可选，单页返回上限（默认 50，上限 200）", required = false) Integer limit,
            @P(value = "可选（manifest only），用 # 分隔，过滤 related 包含任一指定 id 的 manifest", required = false) String related_dataset_ids
    ) {
        try {
            String qType = normalizeQueryType(query_type);
            if (qType == null) {
                return fail("INVALID_QUERY_TYPE",
                        "query_type must be 'dataset' or 'manifest'",
                        Map.of("query_type", nvl(query_type)));
            }

            String runId = AgentContext.getRunId();
            AgentRunDatasetRegistry registry = this.agentRunDatasetRegistry;
            if (runId == null || runId.isBlank() || registry == null) {
                return fail("RUN_LEVEL_IDS_UNAVAILABLE",
                        "listMyData requires an active run and AgentRunDatasetRegistry",
                        Map.of("runId", nvl(runId)));
            }

            int off = (offset == null || offset < 0) ? 0 : offset;
            int lim = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
            String tsFilter = from_ts_code == null ? "" : from_ts_code.trim();
            String grepFilter = grep == null ? "" : grep.trim().toLowerCase();
            List<String> relatedFilter = splitHash(related_dataset_ids);

            AgentRunDatasetSnapshot snapshot = registry.snapshot(runId);
            List<AgentRunDatasetEntry> candidates = "dataset".equals(qType)
                    ? snapshot.datasets()
                    : snapshot.manifests();

            List<AgentRunDatasetEntry> filtered = new ArrayList<>();
            for (AgentRunDatasetEntry entry : candidates) {
                if (!matches(entry, tsFilter, grepFilter, relatedFilter, "dataset".equals(qType))) {
                    continue;
                }
                filtered.add(entry);
            }

            // Q5: 稳定顺序 = 落盘时编号（已由 registry 保证）。此处仅做分页切片。
            int from = Math.min(off, filtered.size());
            int to = Math.min(from + lim, filtered.size());
            List<Map<String, Object>> entries = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                entries.add(toEntryView(filtered.get(i)));
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query_type", qType);
            data.put("run_id", runId);
            data.put("total_matched", filtered.size());
            data.put("returned_count", entries.size());
            data.put("offset", off);
            data.put("limit", lim);
            data.put("entries", entries);
            return ok(data);
        } catch (Exception e) {
            log.error("listMyData failed", e);
            return fail("TOOL_ERROR", "listMyData invocation error",
                    Map.of("message", nvl(e.getMessage())));
        }
    }

    private static String normalizeQueryType(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        if (s.equals("dataset") || s.equals("datasets")) return "dataset";
        if (s.equals("manifest") || s.equals("manifests")) return "manifest";
        return null;
    }

    private static boolean matches(AgentRunDatasetEntry entry,
                                   String tsFilter,
                                   String grepFilter,
                                   List<String> relatedFilter,
                                   boolean isDatasetQuery) {
        // from_ts_code 子串匹配（multi-ts-code 用 # 分隔，按子串而不是按完全相等）
        if (!tsFilter.isEmpty()) {
            String ftc = entry.fromTsCode() == null ? "" : entry.fromTsCode();
            if (!ftc.contains(tsFilter)) {
                return false;
            }
        }
        if (!grepFilter.isEmpty()) {
            String oid = entry.originalId() == null ? "" : entry.originalId();
            if (!oid.toLowerCase().contains(grepFilter)) {
                return false;
            }
        }
        // related_dataset_ids 只对 manifest 有意义；dataset 忽略此过滤（不报错）
        if (!isDatasetQuery && !relatedFilter.isEmpty()) {
            List<String> related = entry.relatedDatasetIds();
            if (related == null || related.isEmpty()) {
                return false;
            }
            boolean any = false;
            for (String want : relatedFilter) {
                if (related.contains(want)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> toEntryView(AgentRunDatasetEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("number", entry.number());
        view.put("originalId", entry.originalId());
        view.put("persistedPath", entry.persistedPath());
        view.put("fromTsCode", entry.fromTsCode());
        view.put("sortKey", entry.sortKey());
        view.put("artifactType", entry.artifactType().name());
        if (entry.isManifest()) {
            view.put("relatedDatasetIds", entry.relatedDatasetIds());
        }
        return view;
    }

    private static List<String> splitHash(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split("#")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private String ok(Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", "listMyData");
        payload.put("data", data == null ? Map.of() : data);
        payload.put("error", null);
        return writeJson(payload);
    }

    private String fail(String code, String message, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", "listMyData");
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
            return "{\"ok\":false,\"tool\":\"listMyData\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\""
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
