package world.willfrog.alphafrogmicro.common.datasource;

/**
 * 已解析的行情读分页：页码从 1 起，{@link #offset()} / {@link #limit()} 直接用于 SQL。
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

    public int offset() {
        return (page - 1) * pageSize;
    }

    public int limit() {
        return pageSize;
    }
}
