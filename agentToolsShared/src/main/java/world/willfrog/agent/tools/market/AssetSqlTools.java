package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.failure.ToolFailureCategory;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatabaseFetchedPathStrategy;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;
import world.willfrog.alphafrogmicro.domestic.idl.ExecuteAssetSqlRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ExecuteAssetSqlResponse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台资产库统一只读 SQL 取数工具（014-new-02 方案 D1/D3/D5）。
 *
 * <p>与 {@code executeQuery} 的分工：{@code queryAssetData} 是两段式取数的第一段——
 * 把对平台已落库资产表（35 张，目录见 {@code listAssetTables}）的组合查询表达为一条 SQL，
 * 经既有 {@link DomesticStockService} Dubbo 引用交给服务端守卫执行；{@code executeQuery}
 * 是第二段，对已落盘数据集做分析 SQL。超过 {@link #INLINE_ROW_LIMIT} 行的结果写入
 * kind={@code asset_sql} 的 CSV 数据集，正好作为第二段的输入，衔接既有
 * {@link DatasetWriter}→挂载链路。</p>
 *
 * <p>错误处理：服务端结构化拒绝（守卫类 / 执行类错误码）原样透传错误码与文案，
 * 失败类别按 014-opt-03 的 {@link ToolFailureCategory} 归类（守卫参数类→INVALID_ARGUMENT、
 * SERVER_BUSY→容量类、STATEMENT_TIMEOUT→上游类）。响应统一走 {@code {ok,tool,data,error}} 信封，
 * 构造方式对齐 {@link MarketDataTools} 的 ok()/fail() 模式。</p>
 */
@Slf4j
@Component
public class AssetSqlTools {

    /** queryAssetData：平台资产库只读 SQL 取数（两段式第一段）。 */
    static final String TOOL_QUERY_ASSET_DATA = "queryAssetData";

    /** listAssetTables：零参数元工具，返回 35 张可查表的目录（先例 checkParallelLimits）。 */
    static final String TOOL_LIST_ASSET_TABLES = "listAssetTables";

    /** SQL 结果数据集 kind（DatabaseFetchedPathStrategy 显式登记的 topic，不可回落 domestic_listed_asset）。 */
    static final String ASSET_SQL_DATASET_KIND = "asset_sql";

    /** 35 表目录 classpath 资源（权威资源先例 dataset_field_specs.json；由资源侧维护）。 */
    static final String ASSET_TABLES_CATALOG_RESOURCE = "prompts/tools/asset_tables_catalog.json";

    /** 行数不超过该值直接内联返回；超过则写数据集返回 dataset 信封（方案 D3）。 */
    static final int INLINE_ROW_LIMIT = 50;

    /** 数据集信封 preview_rows 行数（小样本提示，供模型判断列含义）。 */
    private static final int DATASET_PREVIEW_ROWS = 5;

    /**
     * Dubbo 引用的股票服务，与 {@link MarketDataTools} 共用同一个 market 引用
     * （{@code MarketDataRpcConfiguration#marketDomesticStockService}）；
     * {@code executeAssetSql} 是该接口新方法，直接调用即可，无需新增引用。
     */
    @Autowired
    @Qualifier("marketDomesticStockService")
    private DomesticStockService domesticStockService;

    /** Dataset 写入器：超过内联行数上限的查询结果写 CSV 数据集获得 dataset_id。 */
    private final DatasetWriter datasetWriter;

    /** Dataset 注册表：asset_sql 数据集的精确复用（复用键＝归一化 SQL 摘要）与 run 级登记。 */
    private final DatasetRegistry datasetRegistry;

    /** JSON 序列化器，用于工具返回值编码与行 JSON 数组解析。 */
    private final ObjectMapper objectMapper;

    public AssetSqlTools(DatasetWriter datasetWriter,
                         DatasetRegistry datasetRegistry,
                         ObjectMapper objectMapper) {
        this.datasetWriter = datasetWriter;
        this.datasetRegistry = datasetRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 对平台资产库执行一条只读 SQL（方案 D1/D3）。
     *
     * <p>limit/offset 由模型在 SQL 内显式书写，服务端守卫解析与强制（无 LIMIT 拒绝、超硬上限改写）；
     * 日期写法 {@code af_date('YYYY-MM-DD')}、JSONB 取键写法见 {@code listAssetTables} 目录说明。</p>
     *
     * <p>结果分流：行数 ≤ {@link #INLINE_ROW_LIMIT} 内联 {@code columns/rows/row_count/truncated}；
     * 超过则先查精确复用（归一化 SQL 相同即命中），未命中写 kind={@code asset_sql} 数据集并返回
     * datasetData() 风格信封（source=created/reused，字段对齐 {@link MarketDataTools} 的数据集响应）。</p>
     */
    @Tool
    public String queryAssetData(String sql) {
        String normalizedSql = normalizeAssetSql(sql);
        if (normalizedSql.isEmpty()) {
            return fail(TOOL_QUERY_ASSET_DATA, "MISSING_SQL",
                    "sql 不能为空：请先调 listAssetTables 查看可查表目录与写法示例，再提交一条带 LIMIT 的只读 SELECT/WITH 查询。",
                    Map.of());
        }
        String sqlDigest = DatabaseFetchedPathStrategy.sqlDigest(normalizedSql);

        // 精确复用：归一化 SQL 相同（digest 相同）即视为同一份数据，新鲜度沿用注册表 TTL 与文件在位检查。
        if (datasetWriter.isEnabled() && datasetRegistry.isEnabled()) {
            var reused = datasetRegistry.findReusableAssetSql(sqlDigest);
            if (reused.isPresent()) {
                DatasetRegistry.DatasetMeta meta = reused.get();
                List<String> reusedColumns = meta.getColumns() == null ? List.of() : meta.getColumns();
                Map<String, Object> data = datasetDataEnvelope(
                        sqlDigest,
                        reusedColumns,
                        meta.getDatasetId(),
                        meta.getRowCount(),
                        "reused",
                        true,
                        List.of(),
                        false,
                        0L);
                return ok(TOOL_QUERY_ASSET_DATA, data);
            }
        }

        ExecuteAssetSqlResponse response;
        try {
            ExecuteAssetSqlRequest request = ExecuteAssetSqlRequest.newBuilder()
                    .setSql(sql == null ? "" : sql)
                    .build();
            response = domesticStockService.executeAssetSql(request);
        } catch (Exception e) {
            return fail(TOOL_QUERY_ASSET_DATA, "TOOL_ERROR",
                    "查询失败，请重试或更换工具。如果持续失败，请换一种方式完成任务。",
                    Map.of("message", nvl(e.getMessage())));
        }
        if (response == null) {
            return fail(TOOL_QUERY_ASSET_DATA, "TOOL_ERROR", "查询服务未返回结果，请重试。", Map.of());
        }
        if (response.hasError() && !response.getError().getCode().isEmpty()) {
            // 服务端守卫/执行错误：错误码与文案透传，失败类别按码归类（D3/D7）。
            return fail(TOOL_QUERY_ASSET_DATA,
                    response.getError().getCode(),
                    response.getError().getMessage(),
                    Map.of("details_json", response.getError().getDetailsJson(),
                            "sql_digest", sqlDigest),
                    categoryForErrorCode(response.getError().getCode()));
        }

        List<String> columns = response.getColumnsList();
        int rowCount = (int) response.getRowCount();
        boolean truncated = response.getTruncated();
        long enforcedLimit = response.getEnforcedLimit();
        long elapsedMs = response.getElapsedMs();

        // 小结果内联：rows 为 JSON 数组字符串，解析成数组内联返回（≤50 行，方案 D3）。
        if (rowCount <= INLINE_ROW_LIMIT) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("columns", columns);
            data.put("rows", parseRowJsonArray(response.getRowsList()));
            data.put("row_count", rowCount);
            data.put("truncated", truncated);
            data.put("enforced_limit", enforcedLimit);
            data.put("elapsed_ms", elapsedMs);
            return ok(TOOL_QUERY_ASSET_DATA, data);
        }

        // 大结果：写 kind=asset_sql 数据集；dataset 写入未启用时退化为截断内联（对齐市场工具的 inline 降级）。
        if (datasetWriter.isEnabled()) {
            try {
                String runId = AgentContext.getRunId();
                String prefix = (runId != null ? runId : "shared") + "-asset_sql";
                String datasetId = datasetWriter.writeDataset(
                        ASSET_SQL_DATASET_KIND, prefix, sqlDigest, "", "",
                        response.getRowsList(), columns,
                        rowJson -> parseRowJson(rowJson));
                if (datasetId != null && !datasetId.isBlank()) {
                    if (datasetRegistry.isEnabled()) {
                        datasetRegistry.registerDataset(
                                ASSET_SQL_DATASET_KIND, sqlDigest, "", "", columns, datasetId, rowCount);
                    }
                    Map<String, Object> data = datasetDataEnvelope(
                            sqlDigest,
                            columns,
                            datasetId,
                            rowCount,
                            "created",
                            false,
                            parseRowJsonArray(response.getRowsList().subList(0,
                                    Math.min(DATASET_PREVIEW_ROWS, response.getRowsCount()))),
                            truncated,
                            enforcedLimit);
                    data.put("elapsed_ms", elapsedMs);
                    return ok(TOOL_QUERY_ASSET_DATA, data);
                }
            } catch (Exception e) {
                log.warn("asset_sql dataset write failed, fallback to inline: {}", e.getMessage());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", columns);
        data.put("rows", parseRowJsonArray(response.getRowsList().subList(0,
                Math.min(INLINE_ROW_LIMIT, response.getRowsCount()))));
        data.put("row_count", rowCount);
        data.put("truncated", true);
        data.put("enforced_limit", enforcedLimit);
        data.put("elapsed_ms", elapsedMs);
        data.put("inline_note", "结果超过 " + INLINE_ROW_LIMIT
                + " 行且数据集写入不可用，此处仅内联前 " + INLINE_ROW_LIMIT + " 行；请缩小结果后重试。");
        return ok(TOOL_QUERY_ASSET_DATA, data);
    }

    /**
     * 零参数元工具：返回 queryAssetData 可查询的 35 张平台资产表目录（方案 D5）。
     *
     * <p>目录来源是 classpath 权威资源 {@link #ASSET_TABLES_CATALOG_RESOURCE}
     * （与 D4 服务端表白名单同源，由资源侧维护）；本工具只读并按原样结构化返回，
     * 不在代码里内置兜底清单——资源缺失时明确失败，避免给出与白名单漂移的目录。</p>
     */
    @Tool
    public String listAssetTables() {
        String catalogJson;
        try (InputStream input = new ClassPathResource(ASSET_TABLES_CATALOG_RESOURCE).getInputStream()) {
            catalogJson = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fail(TOOL_LIST_ASSET_TABLES, "TOOL_ERROR",
                    "资产表目录资源 " + ASSET_TABLES_CATALOG_RESOURCE + " 缺失或不可读，请联系平台维护；"
                            + "在修复前不要盲目调用 queryAssetData。",
                    Map.of("resource", ASSET_TABLES_CATALOG_RESOURCE, "message", nvl(e.getMessage())));
        }
        Object catalog;
        try {
            catalog = objectMapper.readValue(catalogJson, new TypeReference<Object>() {
            });
        } catch (Exception e) {
            return fail(TOOL_LIST_ASSET_TABLES, "TOOL_ERROR",
                    "资产表目录资源不是合法 JSON，请联系平台维护。",
                    Map.of("resource", ASSET_TABLES_CATALOG_RESOURCE, "message", nvl(e.getMessage())));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("catalog", catalog);
        data.put("source", "classpath:" + ASSET_TABLES_CATALOG_RESOURCE);
        return ok(TOOL_LIST_ASSET_TABLES, data);
    }

    // ---- 数据集信封与行解析 ----

    /**
     * 数据集信封：字段对齐 {@link MarketDataTools} 的 datasetData()
     * （ts_code/start_date/end_date/rows/fields/source/cache_hit/dataset_id/dataset_ids/preview_rows），
     * 差异仅两点：ts_code 位放归一化 SQL 摘要（SQL 数据集无标的代码与日期区间语义，
     * start/end 恒为空串，注册表因此只走精确匹配复用），并额外携带 truncated/enforced_limit。
     */
    private Map<String, Object> datasetDataEnvelope(String sqlDigest,
                                                    List<String> fields,
                                                    String datasetId,
                                                    int rows,
                                                    String source,
                                                    boolean cacheHit,
                                                    List<List<Object>> previewRows,
                                                    boolean truncated,
                                                    long enforcedLimit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ts_code", nvl(sqlDigest));
        data.put("sql_digest", nvl(sqlDigest));
        data.put("start_date", "");
        data.put("end_date", "");
        data.put("rows", rows);
        data.put("fields", fields);
        data.put("source", source);
        data.put("cache_hit", cacheHit);
        data.put("dataset_id", nvl(datasetId));
        data.put("dataset_ids", datasetId == null || datasetId.isBlank() ? List.of() : List.of(datasetId));
        if (previewRows != null && !previewRows.isEmpty()) {
            data.put("preview_rows", previewRows);
        }
        data.put("truncated", truncated);
        data.put("enforced_limit", enforcedLimit);
        return data;
    }

    /** 服务端行传输是「每行一个 JSON 数组字符串」；内联与 preview 场景解析回数组。 */
    private List<List<Object>> parseRowJsonArray(List<String> rowJsonList) {
        List<List<Object>> rows = new ArrayList<>(rowJsonList.size());
        for (String rowJson : rowJsonList) {
            rows.add(parseRowJson(rowJson));
        }
        return rows;
    }

    private List<Object> parseRowJson(String rowJson) {
        try {
            return objectMapper.readValue(rowJson, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            // 单行解析失败退化为原始字符串，保证行数与列数对齐不被破坏。
            List<Object> fallback = new ArrayList<>(1);
            fallback.add(rowJson);
            return fallback;
        }
    }

    /**
     * 服务端错误码→失败类别（方案 D7：统一走 ToolFailureCategory）。
     * 守卫参数类（MISSING_LIMIT/NOT_SELECT/FORBIDDEN_KEYWORD/TABLE_NOT_ALLOWED/
     * FORBIDDEN_FUNCTION/MULTI_STATEMENT/INVALID_DATE_LITERAL）→INVALID_ARGUMENT；
     * SERVER_BUSY→容量类；STATEMENT_TIMEOUT/QUERY_FAILED 与未知码→上游类。
     */
    private static ToolFailureCategory categoryForErrorCode(String code) {
        String normalized = code == null ? "" : code.trim();
        return switch (normalized) {
            case "SERVER_BUSY" -> ToolFailureCategory.RATE_LIMITED_OR_CAPACITY;
            case "STATEMENT_TIMEOUT", "QUERY_FAILED" -> ToolFailureCategory.UPSTREAM_OR_SYSTEM;
            default -> ToolFailureCategory.INVALID_ARGUMENT;
        };
    }

    // ---- SQL 归一化（方案 D3 复用键定义）----

    /**
     * SQL 归一化＝去首尾空白＋连续空白折叠单空格＋去 {@code /* *}{@code /} 与 {@code --} 注释，
     * 标识符（双引号）与字符串字面量（单引号，含 {@code ''} 转义）原样保留。
     * LIMIT/OFFSET 数值属 SQL 文本一部分，不同 LIMIT/OFFSET 即不同归一化文本、不同数据集（D3）。
     * 注释折叠为一个空格而非删除，保证 {@code SELECT 1/*x*}{@code /FROM t} 之类写法去注释后仍有词边界。
     */
    static String normalizeAssetSql(String rawSql) {
        if (rawSql == null || rawSql.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(rawSql.length());
        int i = 0;
        int n = rawSql.length();
        boolean pendingSpace = false;
        while (i < n) {
            char c = rawSql.charAt(i);
            if (c == '-' && i + 1 < n && rawSql.charAt(i + 1) == '-') {
                // 行注释 -- 到行尾：丢弃并折叠为待补空格
                i += 2;
                while (i < n && rawSql.charAt(i) != '\n') {
                    i++;
                }
                pendingSpace = true;
                continue;
            }
            if (c == '/' && i + 1 < n && rawSql.charAt(i + 1) == '*') {
                // 块注释 /* */：丢弃并折叠为待补空格（不支持嵌套，PostgreSQL 语义）
                i += 2;
                while (i + 1 < n && !(rawSql.charAt(i) == '*' && rawSql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                pendingSpace = true;
                continue;
            }
            if (c == '\'' || c == '"') {
                // 字符串字面量 / 双引号标识符：整段原样保留（含 '' 与 "" 转义），不识别其中的注释与空白折叠
                appendPendingSpace(out, pendingSpace);
                pendingSpace = false;
                char quote = c;
                out.append(quote);
                i++;
                while (i < n) {
                    char sc = rawSql.charAt(i);
                    out.append(sc);
                    i++;
                    if (sc == quote) {
                        if (i < n && rawSql.charAt(i) == quote) {
                            out.append(quote);
                            i++;
                            continue;
                        }
                        break;
                    }
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                pendingSpace = true;
                i++;
                continue;
            }
            appendPendingSpace(out, pendingSpace);
            pendingSpace = false;
            out.append(c);
            i++;
        }
        return out.toString().trim();
    }

    private static void appendPendingSpace(StringBuilder out, boolean pendingSpace) {
        if (pendingSpace && out.length() > 0) {
            out.append(' ');
        }
    }

    // ---- 统一信封构造（对齐 MarketDataTools 的 ok()/fail() 模式）----

    private String ok(String tool, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", tool);
        payload.put("data", data == null ? Map.of() : data);
        payload.put("error", null);
        return writeJson(payload);
    }

    private String fail(String tool, String code, String message, Map<String, Object> details) {
        return fail(tool, code, message, details, null);
    }

    private String fail(String tool, String code, String message, Map<String, Object> details,
                        ToolFailureCategory category) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", tool);
        payload.put("data", Map.of());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", nvl(code));
        err.put("message", nvl(message));
        err.put("details", details == null ? Map.of() : details);
        ToolFailureCategory.putCategoryIfResolved(err, code, category);
        payload.put("error", err);
        return writeJson(payload);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"unknown\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\""
                    + nvl(e.getMessage()).replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r") + "\"}}";
        }
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }
}
