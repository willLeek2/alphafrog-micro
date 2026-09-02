package world.willfrog.alphafrogmicro.common.datasource;

/**
 * 已解析的行情读分页：页码从 1 起，{@link #offset()} 为 {@code long}，{@link #limit()} 直接用于 SQL。
 */
public final class MarketReadPage {

    private final int page;
    private final int pageSize;

    MarketReadPage(int page, int pageSize) {
        this.page = page;
        this.pageSize = pageSize;
    }

    public int page() {
        return page;
    }

    public int pageSize() {
        return pageSize;
    }

    public long offset() {
        return Math.multiplyExact((long) page - 1L, pageSize);
    }

    public int limit() {
        return pageSize;
    }
}
