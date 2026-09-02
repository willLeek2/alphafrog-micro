package world.willfrog.alphafrogmicro.common.datasource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 行情查询共享返回量守卫：检查行数与字节数，并解析分页。超限抛
 * {@link MarketReadResultLimitExceededException}，不截断结果冒充成功。
 *
 * <p>默认字节合同按即将返回的完整 JSON 计算（含数组括号、逗号、字符串引号与转义），
 * 不是逐行原始 UTF-8 相加。</p>
 */
public final class MarketReadResultGuard {

    private final MarketReadDataSourceProperties.Limits limits;
    private final ObjectMapper objectMapper;

    public MarketReadResultGuard(MarketReadDataSourceProperties.Limits limits) {
        this(limits, new ObjectMapper());
    }

    public MarketReadResultGuard(MarketReadDataSourceProperties.Limits limits, ObjectMapper objectMapper) {
        this.limits = limits;
        this.objectMapper = objectMapper;
    }

    public MarketReadPage resolvePage(Integer page, Integer pageSize) {
        int resolvedPage = page == null ? 1 : page;
        if (resolvedPage < 1) {
            throw new IllegalArgumentException("market-read page must be >= 1");
        }
        int resolvedSize = pageSize == null ? limits.getDefaultPageSize() : pageSize;
        if (resolvedSize < 1 || resolvedSize > limits.getMaxPageSize()) {
            throw new MarketReadResultLimitExceededException(
                    MarketReadResultLimitExceededException.LimitKind.PAGE_SIZE,
                    resolvedSize,
                    limits.getMaxPageSize());
        }
        return new MarketReadPage(resolvedPage, resolvedSize);
    }

    /**
     * 检查整表/无分页结果。行数超限或完整 JSON 超过 {@code maxBytes} 时抛错，
     * 返回原列表（不复制、不截断）。
     */
    public <T> List<T> checkRows(List<T> rows) {
        assertRowCount(rows);
        enforceJsonByteLimit(rows);
        return rows;
    }

    /**
     * 调用方自带逐行估算时，用饱和加法累计，避免 {@code long} 溢出后变成负数被当成通过。
     */
    public <T> List<T> checkRows(List<T> rows, ToLongFunction<T> byteSize) {
        assertRowCount(rows);
        enforceEstimatedByteLimit(rows, byteSize);
        return rows;
    }

    /**
     * 分页读取：调用方应按 {@code page.limit() + 1} 向数据库取行（多取一行探是否还有下一页）。
     * 取回超过 {@code limit + 1} 行视为未加分页，按行数超限处理，不会悄悄丢掉多余行。
     * 字节上限按将返回的 {@link MarketReadPageResult} 完整 JSON 计算。
     */
    public <T> MarketReadPageResult<T> checkPage(List<T> fetched, MarketReadPage page) {
        MarketReadPageResult<T> result = buildPage(fetched, page);
        enforceJsonByteLimit(result);
        return result;
    }

    public <T> MarketReadPageResult<T> checkPage(
            List<T> fetched, MarketReadPage page, ToLongFunction<T> byteSize) {
        MarketReadPageResult<T> result = buildPage(fetched, page);
        enforceEstimatedByteLimit(result.rows(), byteSize);
        return result;
    }

    private <T> MarketReadPageResult<T> buildPage(List<T> fetched, MarketReadPage page) {
        if (fetched == null) {
            throw new IllegalArgumentException("market-read rows must not be null");
        }
        if (page == null) {
            throw new IllegalArgumentException("market-read page must not be null");
        }
        int probeLimit = page.limit() + 1;
        if (fetched.size() > probeLimit) {
            throw new MarketReadResultLimitExceededException(
                    MarketReadResultLimitExceededException.LimitKind.ROWS,
                    fetched.size(),
                    page.limit());
        }
        boolean hasMore = fetched.size() > page.limit();
        List<T> pageRows = hasMore
                ? List.copyOf(fetched.subList(0, page.limit()))
                : List.copyOf(fetched);
        if (pageRows.size() > limits.getMaxRows()) {
            throw new MarketReadResultLimitExceededException(
                    MarketReadResultLimitExceededException.LimitKind.ROWS,
                    pageRows.size(),
                    limits.getMaxRows());
        }
        return new MarketReadPageResult<>(new ArrayList<>(pageRows), page.offset(), page.limit(), hasMore);
    }

    private <T> void assertRowCount(List<T> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("market-read rows must not be null");
        }
        if (rows.size() > limits.getMaxRows()) {
            throw new MarketReadResultLimitExceededException(
                    MarketReadResultLimitExceededException.LimitKind.ROWS,
                    rows.size(),
                    limits.getMaxRows());
        }
    }

    private void enforceJsonByteLimit(Object payload) {
        long bytes = jsonSize(payload);
        if (bytes > limits.getMaxBytes()) {
            throw new MarketReadResultLimitExceededException(
                    MarketReadResultLimitExceededException.LimitKind.BYTES,
                    bytes,
                    limits.getMaxBytes());
        }
    }

    private <T> void enforceEstimatedByteLimit(List<T> rows, ToLongFunction<T> byteSize) {
        long bytes = 0L;
        long maxBytes = limits.getMaxBytes();
        for (T row : rows) {
            long add = Math.max(0L, byteSize.applyAsLong(row));
            if (add > maxBytes - bytes) {
                long actual = add > Long.MAX_VALUE - bytes ? Long.MAX_VALUE : bytes + add;
                throw new MarketReadResultLimitExceededException(
                        MarketReadResultLimitExceededException.LimitKind.BYTES,
                        actual,
                        maxBytes);
            }
            bytes += add;
        }
    }

    private long jsonSize(Object payload) {
        try {
            return objectMapper.writeValueAsBytes(payload).length;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("market-read result cannot be serialized for byte limit", ex);
        }
    }

}
