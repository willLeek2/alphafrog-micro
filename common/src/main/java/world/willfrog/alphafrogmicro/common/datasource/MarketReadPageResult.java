package world.willfrog.alphafrogmicro.common.datasource;

import java.util.List;

/**
 * 一页行情读结果。{@code hasMore=true} 表示调用方用 limit+1 探到后续页，不是静默截断。
 */
public record MarketReadPageResult<T>(List<T> rows, long offset, int limit, boolean hasMore) {
}
