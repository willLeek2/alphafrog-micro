package world.willfrog.alphafrogmicro.domestic.stock.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * 统一 SQL 资产取数工具的服务端执行器（方案 D3 数字与 D4 第 8 条、D7 跨运行并发）。
 *
 * <p>只执行 {@link AssetSqlGuard} 放行后的 SQL，四层约束：</p>
 * <ul>
 *   <li>只读事务：@Transactional(readOnly=true)，PG 侧按只读会话执行（写路径直接被库拒绝，
 *       兜住关键字黑名单外的写形态，例如 SELECT … INTO）；</li>
 *   <li>语句超时：JdbcTemplate queryTimeout＝30 秒（可配置），超时拒绝 STATEMENT_TIMEOUT，
 *       不给部分行；</li>
 *   <li>行/字节双硬上限：20 万行 / 4MiB（均可配置），逐行累计、超即停并标记 truncated，
 *       不包括越限那一行；</li>
 *   <li>实例级并发：Semaphore(4)（可配置）跨运行共限同一服务实例上的并发查询——权重限流
 *       只对登记了热配置条目的工具生效、且只管单轮调用预算（方案事实 7），跨运行并发必须
 *       由服务实例自己限制；获取不到立即拒 SERVER_BUSY（可重试）。</li>
 * </ul>
 *
 * <p>拒绝（SERVER_BUSY/STATEMENT_TIMEOUT/QUERY_FAILED）经返回对象的错误字段传递，
 * 不靠异常跨 RPC 传递。行传输＝每行一个 JSON 数组字符串（列序与元数据一致）；
 * 只读事务内 fetchSize&gt;0 才能让 PG 走游标分批拉取，避免大结果一次性进服务端内存。</p>
 */
@Service
@Slf4j
public class AssetSqlExecutor {

    // ---- 执行类稳定错误码（守卫类错误码见 AssetSqlGuard） ----
    public static final String CODE_SERVER_BUSY = "SERVER_BUSY";
    public static final String CODE_STATEMENT_TIMEOUT = "STATEMENT_TIMEOUT";
    public static final String CODE_QUERY_FAILED = "QUERY_FAILED";

    // ---- 配置键与默认值（方案 D3/D7；缺省 20 万行 / 4MiB / 30 秒 / 并发 4，可用配置覆盖） ----
    static final String MAX_ROWS_PROP = "asset-sql.max-rows";
    static final String MAX_BYTES_PROP = "asset-sql.max-bytes";
    static final String QUERY_TIMEOUT_PROP = "asset-sql.query-timeout-seconds";
    static final String MAX_CONCURRENCY_PROP = "asset-sql.max-concurrency";
    private static final long DEFAULT_MAX_ROWS = 200_000L;
    // 字节缺省压在 Dubbo Triple 传输默认响应缓冲 8MiB 之下（留一倍余量），保证触顶时
    // 拿到的是 truncated 结果而不是传输层失败的 RPC；上调超过 8MiB 的注意事项见 application.yml。
    private static final long DEFAULT_MAX_BYTES = 4L * 1024 * 1024;
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_CONCURRENCY = 4;
    private static final int FETCH_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final long maxRows;
    private final long maxBytes;
    private final int queryTimeoutSeconds;
    private final int maxConcurrency;
    private final Semaphore executionSlots;

    public AssetSqlExecutor(DataSource dataSource, ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.maxRows = environment.getProperty(MAX_ROWS_PROP, Long.class, DEFAULT_MAX_ROWS);
        this.maxBytes = environment.getProperty(MAX_BYTES_PROP, Long.class, DEFAULT_MAX_BYTES);
        this.queryTimeoutSeconds = environment.getProperty(QUERY_TIMEOUT_PROP, Integer.class, DEFAULT_QUERY_TIMEOUT_SECONDS);
        this.maxConcurrency = environment.getProperty(MAX_CONCURRENCY_PROP, Integer.class, DEFAULT_MAX_CONCURRENCY);
        this.executionSlots = new Semaphore(this.maxConcurrency);
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(this.queryTimeoutSeconds);
        template.setFetchSize(FETCH_SIZE);
        this.jdbcTemplate = template;
    }

    /**
     * 只读执行守卫放行后的 SQL（方案 D4 第 8 条）。成功返回列名/行 JSON/是否截断；
     * 并发满/超时/执行失败返回对应错误码的结构化拒绝，绝不给部分行。
     */
    @Transactional(readOnly = true)
    public ExecutionOutcome execute(String sql) {
        // 实例级并发限制：获取不到立即拒（单轮预算与权重限流覆盖不了跨运行并发，方案 D7）
        if (!executionSlots.tryAcquire()) {
            return ExecutionOutcome.rejected(CODE_SERVER_BUSY,
                    "服务端并发已达上限（" + maxConcurrency + "），请稍后重试",
                    AssetSqlGuard.details("concurrency", maxConcurrency));
        }
        try {
            return jdbcTemplate.query(sql, rs -> {
                ResultSetMetaData metadata = rs.getMetaData();
                int columnCount = metadata.getColumnCount();
                List<String> columns = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(metadata.getColumnLabel(i));
                }
                List<String> rows = new ArrayList<>();
                long totalBytes = 0;
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= maxRows) {
                        // 行数触顶：停在硬上限处，不含越限行
                        truncated = true;
                        break;
                    }
                    List<Object> cells = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        cells.add(normalizeCell(rs.getObject(i)));
                    }
                    String rowJson = serializeRow(cells);
                    long rowBytes = rowJson.getBytes(StandardCharsets.UTF_8).length;
                    if (totalBytes + rowBytes > maxBytes) {
                        // 字节触顶：同样停在限内，不含越限行
                        truncated = true;
                        break;
                    }
                    rows.add(rowJson);
                    totalBytes += rowBytes;
                }
                return ExecutionOutcome.success(columns, rows, truncated);
            });
        } catch (DataAccessException e) {
            // 超时不给部分行（游标中止，已累计行整体作废），映射 STATEMENT_TIMEOUT
            if (isStatementTimeout(e)) {
                log.warn("资产 SQL 查询超时（{} 秒）被中止: {}", queryTimeoutSeconds, abbreviateForLog(sql));
                return ExecutionOutcome.rejected(CODE_STATEMENT_TIMEOUT,
                        "查询超过 " + queryTimeoutSeconds + " 秒被服务端中止，未返回部分行；请增加过滤条件或缩小查询范围",
                        AssetSqlGuard.details("timeout_seconds", queryTimeoutSeconds));
            }
            log.warn("资产 SQL 查询执行失败: {}", e.getMessage());
            return ExecutionOutcome.rejected(CODE_QUERY_FAILED,
                    "查询执行失败：" + rootMessage(e),
                    AssetSqlGuard.details("exception", e.getClass().getSimpleName()));
        } catch (RuntimeException e) {
            // 行序列化等意外错误：同样整体拒绝，不给半截结果
            log.error("资产 SQL 执行出现未预期错误", e);
            return ExecutionOutcome.rejected(CODE_QUERY_FAILED,
                    "查询执行失败：" + rootMessage(e),
                    AssetSqlGuard.details("exception", e.getClass().getSimpleName()));
        } finally {
            executionSlots.release();
        }
    }

    /**
     * 单元格值归一：jsonb 等 PG 专有类型取其文本值（JSONB 列以原始 JSON 字符串返回），
     * 二进制转 Base64，数值/布尔/字符串保持原生类型交给 JSON 序列化。
     */
    private Object normalizeCell(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof PGobject pgObject) {
            return pgObject.getValue();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return value;
    }

    private String serializeRow(List<Object> cells) {
        try {
            return objectMapper.writeValueAsString(cells);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("行序列化失败", e);
        }
    }

    /** PG 语句超时的识别：Spring 翻译的 QueryTimeoutException、SQLState 57014（query_canceled）、取消消息。 */
    private boolean isStatementTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof QueryTimeoutException) {
                return true;
            }
            if (t instanceof SQLException sqlException
                    && "57014".equals(sqlException.getSQLState())) {
                return true;
            }
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("canceling statement due to")
                        || lower.contains("cancelling statement due to")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return Objects.requireNonNullElse(root.getMessage(), root.getClass().getSimpleName());
    }

    private String abbreviateForLog(String sql) {
        return sql.length() <= 200 ? sql : sql.substring(0, 200) + "…";
    }

    // ==================== 内部载体 ====================

    /** 执行结果：成功（列名＋行 JSON＋是否截断）或结构化拒绝（错误码＋中文说明＋机读详情）。 */
    public static final class ExecutionOutcome {

        private final boolean rejected;
        private final String errorCode;
        private final String errorMessage;
        private final String errorDetailsJson;
        private final List<String> columns;
        private final List<String> rows;
        private final boolean truncated;

        private ExecutionOutcome(boolean rejected, String errorCode, String errorMessage, String errorDetailsJson,
                                 List<String> columns, List<String> rows, boolean truncated) {
            this.rejected = rejected;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.errorDetailsJson = errorDetailsJson;
            this.columns = columns;
            this.rows = rows;
            this.truncated = truncated;
        }

        static ExecutionOutcome success(List<String> columns, List<String> rows, boolean truncated) {
            return new ExecutionOutcome(false, null, null, null, columns, rows, truncated);
        }

        static ExecutionOutcome rejected(String errorCode, String errorMessage, String errorDetailsJson) {
            return new ExecutionOutcome(true, errorCode, errorMessage,
                    errorDetailsJson == null ? "" : errorDetailsJson, List.of(), List.of(), false);
        }

        public boolean isRejected() {
            return rejected;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getErrorDetailsJson() {
            return errorDetailsJson;
        }

        public List<String> getColumns() {
            return columns;
        }

        public List<String> getRows() {
            return rows;
        }

        public boolean isTruncated() {
            return truncated;
        }
    }
}
