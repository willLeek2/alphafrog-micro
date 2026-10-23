package world.willfrog.alphafrogmicro.domestic.stock.service;

import java.util.Set;

/**
 * 统一 SQL 资产取数工具的显式表白名单（方案 D4 第 4 条）。
 *
 * <p>为什么必须显式列清单而不是按 {@code alphafrog_} 前缀放行：资产表与平台业务表
 * （用户/组合/Agent 运行/计费/配置等）同库同 schema、同为 {@code alphafrog_} 前缀，
 * 按前缀放行会把业务表一并放出（方案事实 1）。本清单共 35 张资产数据表，
 * 逐张核对自 migrate/migrations 的建表语句（与表清单底稿一致）。</p>
 *
 * <p>与模型侧目录同源：agentPlatformShared 的目录资源（asset_tables_catalog.json）
 * 按同一份 35 表清单生成，agent 侧启动期一致性断言防两边漂移；服务端这里再以
 * 静态断言固定「恰好 35 张」，改错张数（漏加/多加）会在类初始化时立即暴露。</p>
 *
 * <p>注意：{@code alphafrog_index_weekly} 是僵尸表（有表有 POJO，但全仓无写入线、无数据），
 * 白名单按底稿口径仍收录，目录侧对该表标注「无写入线、无数据」。</p>
 */
public final class AssetSqlAllowedTables {

    private AssetSqlAllowedTables() {
    }

    /**
     * 35 张资产数据表，统一小写（PG 未加引号的标识符折叠为小写，守卫比对前同样转小写）。
     */
    public static final Set<String> ALLOWED_TABLES = Set.of(
            // 股票类（14 张）
            "alphafrog_stock_info",
            "alphafrog_stock_daily",
            "alphafrog_stock_income",
            "alphafrog_stock_balancesheet",
            "alphafrog_stock_cashflow",
            "alphafrog_stock_forecast",
            "alphafrog_stock_express",
            "alphafrog_stock_report_rc",
            "alphafrog_stock_fina_indicator",
            "alphafrog_stock_moneyflow",
            "alphafrog_stock_top10_holders",
            "alphafrog_stock_share_float",
            "alphafrog_cb_daily",
            "alphafrog_stk_ah",
            // 指数类（10 张）
            "alphafrog_index_info",
            "alphafrog_index_daily",
            "alphafrog_index_weekly",
            "alphafrog_index_weight",
            "alphafrog_index_daily_basic",
            "alphafrog_index_ci_member",
            "alphafrog_index_ci_daily",
            "alphafrog_index_sw_classify",
            "alphafrog_index_sw_daily",
            "alphafrog_index_sw_member",
            // ETF（3 张）
            "alphafrog_domestic_etf",
            "alphafrog_domestic_etf_daily",
            "alphafrog_domestic_etf_adj_factor",
            // 场外基金（7 张）
            "alphafrog_fund_info",
            "alphafrog_fund_nav",
            "alphafrog_fund_portfolio",
            "alphafrog_fund_share",
            "alphafrog_fund_company",
            "alphafrog_fund_manager",
            "alphafrog_fund_etf_share_size",
            // 交易日历（1 张）
            "alphafrog_trade_calendar");

    static {
        // 与模型侧目录的同源防漂移断言（方案 D4 第 4 条）：清单必须恰好 35 张。
        if (ALLOWED_TABLES.size() != 35) {
            throw new IllegalStateException("资产表白名单应为 35 张，当前为 " + ALLOWED_TABLES.size() + " 张");
        }
    }
}
