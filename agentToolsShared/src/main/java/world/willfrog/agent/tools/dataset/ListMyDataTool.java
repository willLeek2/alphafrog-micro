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

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 260623-harness-optimization-02: listMyData 工具。
 *
 * <p>用于 agent 不确定当前 run 有哪些可用 dataset / manifest，或 executePython 收到
 * 非法编号错误时的恢复路径。直接消费 {@link AgentRunDatasetRegistry} 的 snapshot。
 *
 * <p>单一 LLM-facing 签名（Cindy round 2 review MF-new-1 拍板）：
 * 8 形参 {@code @Tool} 公开方法是唯一 LLM 可见入口，同时支持：
 * <ul>
 *   <li>普通列表：按 query_type / from_ts_code / offset / limit / related_dataset_ids
 *       过滤当前 run 已落盘 dataset / manifest</li>
 *   <li>原始文件 grep（spec Q10）：当 query_type=dataset 且 grep 非空时，
 *       对每个 dataset 的原始文件全文做大小写不敏感子串搜索，
 *       返回每个命中 dataset 的匹配行数 + snippet preview，按命中次数降序</li>
 * </ul>
 *
 * <p>参数说明：
 * <ul>
 *   <li>{@code query_type} — {@code dataset} 或 {@code manifest}，必填</li>
 *   <li>{@code from_ts_code} — 可选，过滤 ts_code 包含该子串的条目（含 {@code #} 多 ts_code）</li>
 *   <li>{@code grep} — 可选（仅 dataset 模式生效），对每个 dataset 的原始文件全文做
 *       大小写不敏感子串搜索；非 dataset 模式 / 空 grep 走非 grep 路径（listMyData 老语义）</li>
 *   <li>{@code file_offset} / {@code file_limit} — 可选，仅 grep 模式生效，限定从
 *       dataset 列表第 file_offset 个开始扫描，最多 file_limit 个（默认 0 / 全部）</li>
 *   <li>{@code offset} / {@code limit} — 可选，非 grep 模式分页（默认 0/50，上限 200）</li>
 *   <li>{@code related_dataset_ids} — 可选（manifest only），用 {@code #} 分隔，
 *       过滤 related 包含任一指定 id 的 manifest</li>
 * </ul>
 *
 * <p>输出字段：
 * <ul>
 *   <li>非 grep 模式：{@code data.total_matched} / {@code data.returned_count} /
 *       {@code data.offset} / {@code data.limit} / {@code data.entries[]}</li>
 *   <li>grep 模式：{@code data.matched_count} / {@code data.file_offset} /
 *       {@code data.file_limit} / {@code data.matches[]}，每个 match 含
 *       {@code dataset_number / dataset_id / from_ts_code / match_count / snippet_preview}，
 *       按 match_count 降序、相同则按 dataset_number 升序</li>
 * </ul>
 *
 * <p>ToolRouter 入口：本类还提供一个无 {@code @Tool} 注解的 {@link #listMyData6(String, String, String, Integer, Integer, String)}
 * 旧 6 形参入口供内部 ToolRouter 调用（避免破坏既有 routing 逻辑）。两个入口最终都走
 * {@link #executeCore} 核心实现，行为一致；唯一区别是 grep 分支的 file_offset / file_limit 默认值。
 */
@Component
@Slf4j
public class ListMyDataTool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    /** 260623-harness-optimization-02: 单文件 > 10MB 时跳过并 warn，避免 OOM。 */
    private static final long GREP_MAX_FILE_BYTES = 10L * 1024L * 1024L;
    /** grep 命中后用于 snippet 预览的最大字符数（行内容截断上限）。 */
    private static final int GREP_SNIPPET_PREVIEW_MAX = 200;

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

    /**
     * 唯一 LLM-facing 入口（Cindy round 2 review MF-new-1）。
     * 8 形参 {@code @Tool} 公开方法：覆盖普通列表 + raw file grep 双模式。
     * 行为同 {@link #executeCore}；ToolRouter 内部也调用同一个底层实现。
     */
    @Tool("""
        列出当前 agent run 已落盘的 dataset / manifest，提供 raw file content grep 能力。

        用于：
        1. executePython 收到 ILLEGAL_RUN_LEVEL_IDS 错误时恢复（先 listMyData 查合法编号）
        2. 多步工作流中回顾前面步骤落下了什么数据
        3. 发现某个 manifest 包含哪些相关 dataset
        4. 在 dataset 原始文件里搜索某关键字（grep 模式）

        参数：
          query_type          - 必填，取值 "dataset" 或 "manifest"
          from_ts_code        - 可选，过滤 from_ts_code 包含该子串的条目（multi-ts-code 用 # 分隔）
          grep                - 可选（仅 dataset 模式），对 dataset 原始文件全文做大小写不敏感子串搜索
                                非 dataset 模式 / 空 grep 时走非 grep 路径
          file_offset         - 可选（仅 grep 时生效），从第 file_offset 个 dataset 开始扫描（默认 0）
          file_limit          - 可选（仅 grep 时生效），最多扫描 file_limit 个 dataset（默认全部）
          offset              - 可选，非 grep 模式分页起始（默认 0）
          limit               - 可选，非 grep 模式单页返回上限（默认 50，上限 200）
          related_dataset_ids - 可选（manifest only），用 # 分隔，过滤 related 包含任一指定 id 的 manifest

        返回（grep 模式）：{ ok, data: { query_type, run_id, matched_count, file_offset, file_limit, matches: [...] }, error }
        返回（非 grep 模式）：{ ok, data: { query_type, run_id, total_matched, returned_count, offset, limit, entries: [...] }, error }
        """)
    public String listMyData(
            @P(value = "查询类型：dataset | manifest", required = true) String query_type,
            @P(value = "可选，过滤 from_ts_code 包含该子串的条目", required = false) String from_ts_code,
            @P(value = "可选（仅 dataset 模式），对 dataset 原始文件全文做大小写不敏感子串搜索", required = false) String grep,
            @P(value = "可选（仅 grep 时生效），从第 file_offset 个 dataset 开始扫描（默认 0）", required = false) Integer file_offset,
            @P(value = "可选（仅 grep 时生效），最多扫描 file_limit 个 dataset（默认全部）", required = false) Integer file_limit,
            @P(value = "可选，分页起始（默认 0）", required = false) Integer offset,
            @P(value = "可选，单页返回上限（默认 50，上限 200）", required = false) Integer limit,
            @P(value = "可选（manifest only），用 # 分隔，过滤 related 包含任一指定 id 的 manifest", required = false) String related_dataset_ids
    ) {
        return executeCore(query_type, from_ts_code, grep, file_offset, file_limit,
                offset, limit, related_dataset_ids);
    }

    /**
     * Legacy helper（MF-new-1 后已不被 ToolRouter 使用）：保留 6 形参入口以避免破坏既有
     * 调用方（其他模块或历史测试），grep 视为对 originalId 子串匹配（与原始 listMyData 6 形参
     * 语义一致：不做 raw file grep）。不是 {@code @Tool} 注解方法。
     *
     * <p>ToolRouter 当前 route 走 8 形参 {@code @Tool} 入口（见 round 3 commit {@code d1f4440}），
     * 旧 6 形参语义由本方法 + {@link #executeLegacy6Core} 提供向后兼容，仅供其他 Java 模块
     * 或历史测试调用。
     *
     * <p>本入口与 8 形参 LLM-facing {@code @Tool} 入口
     * {@link #listMyData(String, String, String, Integer, Integer, Integer, Integer, String)}
     * 行为在「非 grep 模式」下一致；区别仅在 grep 参数：6 形参版对 originalId 子串匹配，
     * 8 形参版对原始文件全文 grep。
     */
    public String listMyData6(
            String query_type,
            String from_ts_code,
            String grep,
            Integer offset,
            Integer limit,
            String related_dataset_ids
    ) {
        return executeLegacy6Core(query_type, from_ts_code, grep, offset, limit, related_dataset_ids);
    }

    /**
     * 8 形参版的核心实现：合并「非 grep 列表」和「grep raw file content」两种语义。
     * 同时被 {@code @Tool} 公开方法和 ToolRouter 路由调用，确保两种入口行为一致。
     */
    private String executeCore(String queryType,
                               String fromTsCode,
                               String grep,
                               Integer fileOffset,
                               Integer fileLimit,
                               Integer offset,
                               Integer limit,
                               String relatedDatasetIds) {
        try {
            String qType = normalizeQueryType(queryType);
            if (qType == null) {
                return fail("INVALID_QUERY_TYPE",
                        "query_type must be 'dataset' or 'manifest'",
                        Map.of("query_type", nvl(queryType)));
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
            String tsFilter = fromTsCode == null ? "" : fromTsCode.trim();
            String grepQuery = grep == null ? "" : grep.trim();
            List<String> relatedFilter = splitHash(relatedDatasetIds);

            AgentRunDatasetSnapshot snapshot = registry.snapshot(runId);
            List<AgentRunDatasetEntry> candidates = "dataset".equals(qType)
                    ? snapshot.datasets()
                    : snapshot.manifests();

            // grep 模式：仅 dataset 模式 + grep 非空；走 raw file content 全文搜索。
            if ("dataset".equals(qType) && !grepQuery.isEmpty()) {
                int fileOff = (fileOffset == null || fileOffset < 0) ? 0 : fileOffset;
                int fileLim = (fileLimit == null || fileLimit <= 0) ? Integer.MAX_VALUE : fileLimit;
                return ok(buildGrepResult(runId, candidates, grepQuery, tsFilter, fileOff, fileLim));
            }

            // 非 grep 模式：按 (from_ts_code, related) 过滤
            String grepFilter;
            if ("dataset".equals(qType)) {
                // dataset 非 grep：8 形参版 grep 视为空（grep 已走上面 raw 分支）
                grepFilter = "";
            } else {
                // manifest 模式：8 形参版不应用 originalId 过滤（grep 在本 overload 是 raw file content，
                // 与 manifest 不适用；保留 6 形参版 ToolRouter 入口对 manifest+grep 的 originalId 过滤语义）
                grepFilter = "";
            }
            List<AgentRunDatasetEntry> filtered = new ArrayList<>();
            for (AgentRunDatasetEntry entry : candidates) {
                if (!legacyMatches(entry, tsFilter, grepFilter, relatedFilter, "dataset".equals(qType))) {
                    continue;
                }
                filtered.add(entry);
            }

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

    /**
     * 6 形参版的核心实现：保留旧 ToolRouter 入口的 originalId grep 语义，避免破坏既有 routing 逻辑。
     * 行为与原始 commit ec2b31e 完全一致：grep 对 originalId 做大小写不敏感子串匹配。
     */
    private String executeLegacy6Core(String queryType,
                                      String fromTsCode,
                                      String grep,
                                      Integer offset,
                                      Integer limit,
                                      String relatedDatasetIds) {
        try {
            String qType = normalizeQueryType(queryType);
            if (qType == null) {
                return fail("INVALID_QUERY_TYPE",
                        "query_type must be 'dataset' or 'manifest'",
                        Map.of("query_type", nvl(queryType)));
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
            String tsFilter = fromTsCode == null ? "" : fromTsCode.trim();
            String grepFilter = grep == null ? "" : grep.trim().toLowerCase();
            List<String> relatedFilter = splitHash(relatedDatasetIds);

            AgentRunDatasetSnapshot snapshot = registry.snapshot(runId);
            List<AgentRunDatasetEntry> candidates = "dataset".equals(qType)
                    ? snapshot.datasets()
                    : snapshot.manifests();

            List<AgentRunDatasetEntry> filtered = new ArrayList<>();
            for (AgentRunDatasetEntry entry : candidates) {
                if (!legacyMatches(entry, tsFilter, grepFilter, relatedFilter, "dataset".equals(qType))) {
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

    private Map<String, Object> buildGrepResult(String runId,
                                                List<AgentRunDatasetEntry> datasets,
                                                String grepQuery,
                                                String tsFilter,
                                                int fileOff,
                                                int fileLim) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query_type", "dataset");
        data.put("run_id", runId);
        data.put("file_offset", fileOff);
        data.put("file_limit", fileLim);

        int end = Math.min(fileOff + fileLim, datasets.size());
        int scanStart = Math.min(fileOff, datasets.size());
        List<AgentRunDatasetEntry> scannedDatasets = datasets.subList(scanStart, end);

        String lowerQuery = grepQuery.toLowerCase();
        List<Map<String, Object>> matches = new ArrayList<>();
        for (AgentRunDatasetEntry entry : scannedDatasets) {
            if (!tsFilter.isEmpty()) {
                String ftc = entry.fromTsCode() == null ? "" : entry.fromTsCode();
                if (!ftc.contains(tsFilter)) {
                    continue;
                }
            }
            GrepResult r = grepFile(entry.persistedPath(), lowerQuery);
            if (r.matchCount <= 0) {
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("dataset_number", entry.number());
            view.put("dataset_id", entry.originalId());
            view.put("from_ts_code", entry.fromTsCode());
            view.put("match_count", r.matchCount);
            view.put("snippet_preview", r.snippetPreview);
            matches.add(view);
        }
        // 按命中次数降序，命中次数相同则按 dataset_number 升序保证稳定
        matches.sort(Comparator
                .comparingInt((Map<String, Object> m) -> (Integer) m.get("match_count")).reversed()
                .thenComparingInt(m -> (Integer) m.get("dataset_number")));

        data.put("matched_count", matches.size());
        data.put("returned_count", matches.size());
        data.put("matches", matches);
        return data;
    }

    /**
     * 读取文件全文（行级），做大小写不敏感子串搜索。返回命中行数 + 第一条命中行作为 snippet preview。
     * 文件 >10MB 跳过 + warn（避免 OOM）。
     */
    private GrepResult grepFile(String path, String lowerQuery) {
        if (path == null || path.isBlank()) {
            return GrepResult.EMPTY;
        }
        Path p = Paths.get(path);
        if (!Files.exists(p) || !Files.isRegularFile(p)) {
            log.debug("listMyData grep skip missing file: {}", path);
            return GrepResult.EMPTY;
        }
        try {
            long size = Files.size(p);
            if (size > GREP_MAX_FILE_BYTES) {
                log.warn("listMyData grep skip oversized file: path={} size={}", path, size);
                return GrepResult.EMPTY;
            }
        } catch (IOException e) {
            log.warn("listMyData grep stat failed: path={} err={}", path, e.getMessage());
            return GrepResult.EMPTY;
        }
        int count = 0;
        String snippet = null;
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                if (line.toLowerCase().contains(lowerQuery)) {
                    count++;
                    if (snippet == null) {
                        snippet = truncateForPreview(line);
                    }
                }
            }
        } catch (CharacterCodingException e) {
            log.warn("listMyData grep decode failed (non-UTF8?): path={} err={}", path, e.getMessage());
        } catch (IOException e) {
            log.warn("listMyData grep read failed: path={} err={}", path, e.getMessage());
        }
        return new GrepResult(count, snippet == null ? "" : snippet);
    }

    private static String truncateForPreview(String line) {
        if (line.length() <= GREP_SNIPPET_PREVIEW_MAX) {
            return line;
        }
        return line.substring(0, GREP_SNIPPET_PREVIEW_MAX) + "...";
    }

    private record GrepResult(int matchCount, String snippetPreview) {
        static final GrepResult EMPTY = new GrepResult(0, "");
    }

    private static String normalizeQueryType(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        if (s.equals("dataset") || s.equals("datasets")) return "dataset";
        if (s.equals("manifest") || s.equals("manifests")) return "manifest";
        return null;
    }

    /**
     * 6 形参版（保留旧 ToolRouter 入口）的 grep 语义：对 {@code originalId} 做大小写不敏感子串匹配。
     * 8 形参版的 grep 是 raw file content 全文搜索（不走本函数）。
     */
    private static boolean legacyMatches(AgentRunDatasetEntry entry,
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
