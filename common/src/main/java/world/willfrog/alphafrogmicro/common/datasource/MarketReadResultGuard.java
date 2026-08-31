package world.willfrog.alphafrogmicro.common.datasource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 行情查询共享返回量守卫：检查行数与字节数，并解析分页。超限抛
 * {@link MarketReadResultLimitExceededException}，不截断结果冒充成功。
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
     * 检查整表/无分页结果。{@code rows.size()} 大于 {@code maxRows} 或累计字节超过 {@code maxBytes}
     * 时抛错，返回原列表（不复制、不截断）。
     */
    public <T> List<T> checkRows(List<T> rows) {
        return checkRows(rows, this::estimateBytes);
    }

    public <T> List<T> checkRows(List<T> rows, ToLongFunction<T> byteSize) {
        if (rows == null) {
            throw new IllegalArgumentException("market-read rows must not be null");
        }
        if (rows.size() > limits.getMaxRows()) {
            throw new MarketReadResultLimitExceededException(
                    MarketReadResultLimitExceededException.LimitKind.ROWS,
                    rows.size(),
                    limits.getMaxRows());
        }
        long bytes = 0L;
        for (T row : rows) {
            bytes += Math.max(0L, byteSize.applyAsLong(row));
            if (bytes > limits.getMaxBytes()) {
                throw new MarketReadResultLimitExceededException(
                        MarketReadResultLimitExceededException.LimitKind.BYTES,
                        bytes,
                        limits.getMaxBytes());
            }
        }
        return rows;
    }

    /**
     * 分页读取：调用方应按 {@code page.limit() + 1} 向数据库取行（多取一行探是否还有下一页）。
     * 取回超过 {@code limit + 1} 行视为未加分页，按行数超限处理，不会悄悄丢掉多余行。
     */
    public <T> MarketReadPageResult<T> checkPage(List<T> fetched, MarketReadPage page) {
        return checkPage(fetched, page, this::estimateBytes);
    }

    public <T> MarketReadPageResult<T> checkPage(
            List<T> fetched, MarketReadPage page, ToLongFunction<T> byteSize) {
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
        checkRows(pageRows, byteSize);
        return new MarketReadPageResult<>(new ArrayList<>(pageRows), page.offset(), page.limit(), hasMore);
    }

    long estimateBytes(Object row) {
        if (row == null) {
            return 0L;
        }
        if (row instanceof byte[] bytes) {
            return bytes.length;
        }
        if (row instanceof CharSequence chars) {
            return chars.toString().getBytes(StandardCharsets.UTF_8).length;
        }
        if (row instanceof Number || row instanceof Boolean) {
            return 8L;
        }
        try {
            return objectMapper.writeValueAsBytes(row).length;
        } catch (JsonProcessingException ex) {
            return String.valueOf(row).getBytes(StandardCharsets.UTF_8).length;
        }
    }
}
