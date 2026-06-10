package world.willfrog.externalinfo.ingestion.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 待处理记录查询 + 状态更新服务。
 *
 * <p>对应 fetch_scripts/rag_ingestion/db_client.py 旧实现的 SQL 部分。
 * 旧实现直接 psycopg2 连库，本类把同样的 SQL 暴露为 HTTP 端点供 fetch_scripts 远程调用。
 *
 * <p>与旧 DAO 的差异：
 * <ul>
 *   <li>domesticFetchService 旧 DAO 只暴露 findUnprocessed(limit)，没有日期/ts_code/title 过滤；
 *       本服务是 fetch_scripts 全量过滤能力的 server-side 镜像</li>
 *   <li>alphafrog_index_weight 表（CSI 300 / 905 成分股）跨服务读，使用
 *       EXISTS 子查询直接命中同一库的另一张表（与外部 fetch_scripts 通信时由本服务承担 join）</li>
 *   <li>announcement / research_report 两张表走并列分支，避免运行时拼 SQL 字符串</li>
 * </ul>
 */
@Service
@Slf4j
public class RagRecordService {

    private static final String ANN_TABLE = "alphafrog_rag_announcement";
    private static final String REPORT_TABLE = "alphafrog_rag_research_report";
    private static final String WEIGHT_TABLE = "alphafrog_index_weight";

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbcTemplate;

    public RagRecordService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── 查询 ──────────────────────────────────────────────────

    /**
     * 查询待处理记录。返回 List<Map<String,Object>>，字段名以原表列名为准
     * （ann_date / trade_date / ts_code / title / url / abstr），与 fetch_scripts 旧客户端兼容。
     */
    public List<Map<String, Object>> findUnprocessed(RagRecordListRequest req) {
        String docType = req.getDocType();
        if (docType == null || docType.isBlank()) {
            throw new IllegalArgumentException("docType is required");
        }
        int limit = req.getLimit() == null ? 50 : Math.max(0, req.getLimit());
        int offset = req.getOffset() == null ? 0 : Math.max(0, req.getOffset());

        SqlAndParams built = buildUnprocessedQuery(docType, req);
        String sql = built.sql + " ORDER BY d.id LIMIT ? OFFSET ?";
        List<Object> params = new ArrayList<>(built.params);
        params.add(limit);
        params.add(offset);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        log.info("[RagRecordService] findUnprocessed docType={} rows={} sql={}",
                docType, rows.size(), sql);
        return rows;
    }

    // ── 更新 ──────────────────────────────────────────────────

    /** 写入 OSS object key。返回受影响行数（0/1）。 */
    public int markOssUploaded(RagRecordMarkRequest req) {
        String docType = requireDocType(req);
        if (req.getOssKey() == null || req.getOssKey().isBlank()) {
            throw new IllegalArgumentException("ossKey is required for mark-oss-uploaded");
        }
        if (req.getRecordId() == null) {
            throw new IllegalArgumentException("recordId is required");
        }
        String table = tableForDocType(docType);
        int n = jdbcTemplate.update(
                "UPDATE " + table + " SET oss_url = ? WHERE id = ?",
                req.getOssKey(), req.getRecordId());
        log.info("[RagRecordService] markOssUploaded docType={} recordId={} affected={}",
                docType, req.getRecordId(), n);
        return n;
    }

    /** 标记向量化完成。返回受影响行数（0/1）。 */
    public int markVectorized(RagRecordMarkRequest req) {
        String docType = requireDocType(req);
        if (req.getRecordId() == null) {
            throw new IllegalArgumentException("recordId is required");
        }
        String table = tableForDocType(docType);
        int n = jdbcTemplate.update(
                "UPDATE " + table + " SET vectorized = TRUE WHERE id = ?",
                req.getRecordId());
        log.info("[RagRecordService] markVectorized docType={} recordId={} affected={}",
                docType, req.getRecordId(), n);
        return n;
    }

    // ── 内部 ──────────────────────────────────────────────────

    private String requireDocType(RagRecordMarkRequest req) {
        String docType = req.getDocType();
        if (docType == null || docType.isBlank()) {
            throw new IllegalArgumentException("docType is required");
        }
        return docType;
    }

    private String tableForDocType(String docType) {
        if ("announcement".equals(docType)) return ANN_TABLE;
        if ("research_report".equals(docType)) return REPORT_TABLE;
        throw new IllegalArgumentException("unsupported docType: " + docType);
    }

    /** 拼装主 SQL：列选择 + WHERE 子句 + 参数。 */
    private SqlAndParams buildUnprocessedQuery(String docType, RagRecordListRequest req) {
        String table = tableForDocType(docType);
        // 公告: id, ts_code, ann_date, title, url
        // 研报: id, ts_code, trade_date, title, abstr, url
        String dateColumn = "announcement".equals(docType) ? "ann_date" : "trade_date";
        String select;
        if ("announcement".equals(docType)) {
            select = "SELECT d.id, d.ts_code, d.ann_date, d.title, d.url ";
        } else {
            select = "SELECT d.id, d.ts_code, d.trade_date, d.title, d.abstr, d.url ";
        }
        String fromClause = "FROM " + table + " d ";

        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        conditions.add("d.vectorized = FALSE");
        conditions.add("d.oss_url IS NULL");

        appendDateConditions(conditions, params, dateColumn, req.getDateFrom(), req.getDateTo());
        appendTsCodeConditions(conditions, params, req.getTsCode());
        appendTitleConditions(conditions, params, req.getTitlePatterns(), req.getTitleMatch());

        String where = "WHERE " + String.join(" AND ", conditions) + " ";
        return new SqlAndParams(select + fromClause + where, params);
    }

    private void appendDateConditions(List<String> conditions, List<Object> params,
                                      String dateColumn, String dateFrom, String dateTo) {
        if (dateFrom != null && !dateFrom.isBlank()) {
            conditions.add("d." + dateColumn + " >= ?");
            params.add(yyyymmddToMs(dateFrom));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            // 含 dateTo 当天：用下一天午夜作为严格小于的上界
            conditions.add("d." + dateColumn + " < ?");
            params.add(yyyymmddToMs(dateTo) + 86_400_000L);
        }
    }

    private void appendTsCodeConditions(List<String> conditions, List<Object> params, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.isEmpty()) return;
            conditions.add("d.ts_code = ?");
            params.add(s);
            return;
        }
        if (raw instanceof List) {
            List<?> list = (List<?>) raw;
            if (list.isEmpty()) {
                // list 但空 → fail closed: 不返回任何记录 (WHERE 1=0)
                conditions.add("1 = 0");
                return;
            }
            List<String> codes = new ArrayList<>();
            for (Object o : list) {
                if (o != null) codes.add(o.toString());
            }
            if (codes.isEmpty()) {
                conditions.add("1 = 0");
                return;
            }
            String ph = String.join(",", Collections.nCopies(codes.size(), "?"));
            conditions.add("d.ts_code IN (" + ph + ")");
            params.addAll(codes);
            return;
        }
        if (raw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            appendSelectTsCodeCondition(conditions, params, m);
            return;
        }
        throw new IllegalArgumentException(
                "tsCode must be str / list / map, got " + raw.getClass().getName());
    }

    /** type=select 走 EXISTS 子查询，与 fetch_scripts 侧 TsCodeFilter 一致。 */
    private void appendSelectTsCodeCondition(List<String> conditions, List<Object> params,
                                             Map<String, Object> raw) {
        Object typeObj = raw.get("type");
        if (!"select".equals(typeObj)) {
            throw new IllegalArgumentException("tsCode.type must be 'select', got " + typeObj);
        }
        Object condsObj = raw.get("conditions");
        if (!(condsObj instanceof Map)) {
            throw new IllegalArgumentException("tsCode.conditions must be a map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> conds = (Map<String, Object>) condsObj;
        Object idxObj = conds.get("index_codes");
        if (!(idxObj instanceof List) || ((List<?>) idxObj).isEmpty()) {
            throw new IllegalArgumentException("tsCode.conditions.index_codes must be non-empty list");
        }
        List<String> indexCodes = new ArrayList<>();
        for (Object o : (List<?>) idxObj) {
            if (o != null) indexCodes.add(o.toString());
        }

        List<String> inner = new ArrayList<>();
        List<Object> innerParams = new ArrayList<>();
        String idxPh = String.join(",", Collections.nCopies(indexCodes.size(), "?"));
        inner.add("w.index_code IN (" + idxPh + ")");
        innerParams.addAll(indexCodes);

        Object memberFrom = conds.get("member_date_from");
        if (memberFrom instanceof String && !((String) memberFrom).isBlank()) {
            inner.add("w.trade_date >= ?");
            innerParams.add(yyyymmddToMs((String) memberFrom));
        }
        Object memberTo = conds.get("member_date_to");
        if (memberTo instanceof String && !((String) memberTo).isBlank()) {
            inner.add("w.trade_date < ?");
            innerParams.add(yyyymmddToMs((String) memberTo) + 86_400_000L);
        }

        String innerWhere = String.join(" AND ", inner);
        conditions.add("EXISTS (SELECT 1 FROM " + WEIGHT_TABLE
                + " w WHERE w.con_code = d.ts_code AND " + innerWhere + ")");
        params.addAll(innerParams);
    }

    private void appendTitleConditions(List<String> conditions, List<Object> params,
                                       List<String> patterns, Map<String, Object> titleMatch) {
        if (patterns != null && titleMatch != null) {
            throw new IllegalArgumentException("titlePatterns and titleMatch are mutually exclusive");
        }
        if (patterns != null) {
            appendTitleLikeGroup(conditions, params, patterns, "OR", false);
            return;
        }
        if (titleMatch != null) {
            appendTitleMatchConditions(conditions, params, titleMatch);
        }
    }

    private void appendTitleMatchConditions(List<String> conditions, List<Object> params,
                                            Map<String, Object> titleMatch) {
        String mode = stringValue(titleMatch.getOrDefault("mode", "contains"));
        if (!"contains".equals(mode)) {
            throw new IllegalArgumentException("titleMatch.mode currently only supports 'contains', got " + mode);
        }

        Object includeModeObj = titleMatch.containsKey("includeMode")
                ? titleMatch.get("includeMode")
                : titleMatch.getOrDefault("include_mode", "any");
        String includeMode = stringValue(includeModeObj);
        String includeJoiner;
        if ("any".equals(includeMode)) {
            includeJoiner = "OR";
        } else if ("all".equals(includeMode)) {
            includeJoiner = "AND";
        } else {
            throw new IllegalArgumentException("titleMatch.includeMode must be any or all, got " + includeMode);
        }

        List<String> include = stringList(titleMatch.get("include"), "titleMatch.include");
        List<String> exclude = stringList(titleMatch.get("exclude"), "titleMatch.exclude");
        if (include.isEmpty() && exclude.isEmpty()) {
            throw new IllegalArgumentException("titleMatch.include/titleMatch.exclude must not both be empty");
        }

        if (!include.isEmpty()) {
            appendTitleLikeGroup(conditions, params, include, includeJoiner, false);
        }
        if (!exclude.isEmpty()) {
            appendTitleLikeGroup(conditions, params, exclude, "OR", true);
        }
    }

    private void appendTitleLikeGroup(List<String> conditions, List<Object> params,
                                      List<String> patterns, String joiner, boolean negated) {
        List<String> cleaned = new ArrayList<>();
        for (String p : patterns) {
            if (p != null && !p.isBlank()) {
                cleaned.add(p.trim());
            }
        }
        if (cleaned.isEmpty()) {
            return;
        }
        String word = "AND".equals(joiner) ? " AND " : " OR ";
        String joined = String.join(word, Collections.nCopies(cleaned.size(), "d.title LIKE ?"));
        conditions.add((negated ? "NOT " : "") + "(" + joined + ")");
        for (String p : cleaned) {
            params.add("%" + p + "%");
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static List<String> stringList(Object raw, String fieldName) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException(fieldName + " must be a list");
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            if (item == null) {
                throw new IllegalArgumentException(fieldName + " contains null item");
            }
            String s = String.valueOf(item).trim();
            if (s.isEmpty()) {
                throw new IllegalArgumentException(fieldName + " contains blank item");
            }
            result.add(s);
        }
        return result;
    }

    /** 与 Python 侧 date_utils.yyyymmdd_to_ms 对齐：YYYYMMDD → Asia/Shanghai 午夜 Unix 毫秒 */
    private static long yyyymmddToMs(String yyyymmdd) {
        LocalDate d = LocalDate.parse(yyyymmdd, YYYYMMDD);
        ZonedDateTime z = d.atStartOfDay(CST);
        return z.toInstant().toEpochMilli();
    }

    /** SQL + params 一次性返回，避免方法签名过于膨胀。 */
    private static class SqlAndParams {
        final String sql;
        final List<Object> params;

        SqlAndParams(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params;
        }
    }
}
