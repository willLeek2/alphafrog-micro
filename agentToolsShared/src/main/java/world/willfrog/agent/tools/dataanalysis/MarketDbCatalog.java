package world.willfrog.agent.tools.dataanalysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行情库对管理员开放的业务表。
 * 第一次调用只使用类别和表名，第二次调用才读取一个类别的列。
 * 列名来自迁移脚本里的建表语句，以及其后追加的列。
 * 股票资料、股票日线、指数资料、指数日线、指数周线、指数权重、基金资料、基金净值、基金持仓这 9 张表的主键列，按生产库 alphafrog-v3 的实际列名。
 */
public final class MarketDbCatalog {

    public static final List<String> CATEGORIES = List.of(
            "ETF", "股票", "指数", "基金");

    private static final Map<String, List<String>> TABLES_BY_CATEGORY = new LinkedHashMap<>();
    private static final Map<String, List<String>> COLUMNS_BY_TABLE = new LinkedHashMap<>();

    static {
        TABLES_BY_CATEGORY.put("ETF", List.of(
                "alphafrog_domestic_etf",
                "alphafrog_domestic_etf_daily",
                "alphafrog_fund_etf_share_size"
        ));
        TABLES_BY_CATEGORY.put("股票", List.of(
                "alphafrog_stock_info",
                "alphafrog_stock_daily",
                "alphafrog_stock_fina_indicator",
                "alphafrog_stock_income",
                "alphafrog_stock_balancesheet",
                "alphafrog_stock_cashflow",
                "alphafrog_stock_forecast",
                "alphafrog_stock_express",
                "alphafrog_stock_report_rc",
                "alphafrog_stock_moneyflow",
                "alphafrog_stock_top10_holders",
                "alphafrog_stock_share_float"
        ));
        TABLES_BY_CATEGORY.put("指数", List.of(
                "alphafrog_index_info",
                "alphafrog_index_daily",
                "alphafrog_index_weekly",
                "alphafrog_index_weight",
                "alphafrog_index_daily_basic",
                "alphafrog_index_ci_daily",
                "alphafrog_index_ci_member",
                "alphafrog_index_sw_classify",
                "alphafrog_index_sw_daily",
                "alphafrog_index_sw_member"
        ));
        TABLES_BY_CATEGORY.put("基金", List.of(
                "alphafrog_fund_info",
                "alphafrog_fund_nav",
                "alphafrog_fund_portfolio",
                "alphafrog_fund_company",
                "alphafrog_fund_manager",
                "alphafrog_fund_share"
        ));
        COLUMNS_BY_TABLE.put("alphafrog_domestic_etf", List.of("id", "ts_code", "name", "full_name", "exchange", "mgr_name", "list_status", "etf_type", "index_code", "index_name", "list_date", "setup_date", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_domestic_etf_daily", List.of("id", "ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "change", "pct_chg", "vol", "amount", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_fund_etf_share_size", List.of("id", "trade_date", "ts_code", "etf_name", "total_share", "total_size", "nav", "close", "exchange", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_stock_info", List.of("stock_info_id", "ts_code", "symbol", "name", "area", "industry", "fullname", "enname", "cnspell", "market", "exchange", "curr_type", "list_status", "list_date", "delist_date", "is_hs", "act_name", "act_ent_type"));
        COLUMNS_BY_TABLE.put("alphafrog_stock_daily", List.of("stock_daily_id", "ts_code", "trade_date", "close", "open", "high", "low", "pre_close", "change", "pct_chg", "vol", "amount", "adj_factor"));
        COLUMNS_BY_TABLE.put("alphafrog_stock_fina_indicator", List.of("id", "ts_code", "ann_date", "end_date", "profitability", "per_share", "capital_cash", "growth", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_stock_income", List.of("id", "ts_code", "ann_date", "f_ann_date", "end_date", "report_type", "comp_type", "end_type", "basic_eps", "diluted_eps", "total_revenue", "revenue", "total_cogs", "operate_profit", "total_profit", "n_income", "n_income_attr_p", "ebit", "ebitda", "rd_exp", "update_flag", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_stock_balancesheet", List.of("id", "ts_code", "ann_date", "f_ann_date", "end_date", "report_type", "comp_type", "end_type", "money_cap", "accounts_receiv", "inventories", "total_cur_assets", "fix_assets", "goodwill", "intan_assets", "r_and_d", "total_nca", "total_assets", "st_borr", "acct_payable", "total_cur_liab", "lt_borr", "bond_payable", "total_ncl", "total_liab", "total_hldr_eqy_exc_min_int", "total_hldr_eqy_inc_min_int", "minority_int", "update_flag", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_stock_cashflow", List.of("id", "ts_code", "ann_date", "f_ann_date", "end_date", "comp_type", "report_type", "end_type", "c_fr_sale_sg", "n_cashflow_act", "n_cashflow_inv_act", "n_cash_flows_fnc_act", "free_cashflow", "c_cash_equ_end_period", "n_incr_cash_cash_equ", "update_flag", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_stock_forecast", List.of("id", "ts_code", "ann_date", "end_date", "type", "p_change_min", "p_change_max", "net_profit_min", "net_profit_max", "last_parent_net", "first_ann_date", "summary", "change_reason", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_stock_express", List.of("id", "ts_code", "ann_date", "end_date", "revenue", "operate_profit", "total_profit", "n_income", "total_assets", "total_hldr_eqy_exc_min_int", "diluted_eps", "diluted_roe", "yoy_net_profit", "bps", "yoy_sales", "yoy_op", "yoy_tp", "yoy_dedu_np", "yoy_eps", "yoy_roe", "perf_summary", "is_audit", "remark", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_stock_report_rc", List.of("id", "ts_code", "name", "report_date", "report_title", "report_type", "classify", "org_name", "author_name", "quarter", "op_rt", "op_pr", "tp", "np", "eps", "pe", "rd", "roe", "ev_ebitda", "rating", "max_price", "min_price", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_stock_moneyflow", List.of("id", "ts_code", "trade_date", "buy_sm_vol", "buy_sm_amount", "sell_sm_vol", "sell_sm_amount", "buy_md_vol", "buy_md_amount", "sell_md_vol", "sell_md_amount", "buy_lg_vol", "buy_lg_amount", "sell_lg_vol", "sell_lg_amount", "buy_elg_vol", "buy_elg_amount", "sell_elg_vol", "sell_elg_amount", "net_mf_vol", "net_mf_amount", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_stock_top10_holders", List.of("id", "ts_code", "ann_date", "end_date", "holder_name", "hold_amount", "hold_ratio", "hold_float_ratio", "hold_change", "holder_type", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_stock_share_float", List.of("id", "ts_code", "ann_date", "float_date", "float_share", "float_ratio", "holder_name", "share_type", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_index_info", List.of("index_info_id", "ts_code", "name", "fullname", "market", "publisher", "index_type", "category", "base_date", "base_point", "list_date", "weight_rule", "desc", "exp_date"));
        COLUMNS_BY_TABLE.put("alphafrog_index_daily", List.of("index_daily_id", "ts_code", "trade_date", "close", "open", "high", "low", "pre_close", "change", "pct_chg", "vol", "amount"));
        COLUMNS_BY_TABLE.put("alphafrog_index_weekly", List.of("index_weekly_id", "ts_code", "trade_date", "close", "open", "high", "low", "pre_close", "change", "pct_chg", "vol", "amount"));
        COLUMNS_BY_TABLE.put("alphafrog_index_weight", List.of("index_weight_id", "index_code", "con_code", "trade_date", "weight"));
        COLUMNS_BY_TABLE.put("alphafrog_index_daily_basic", List.of("id", "ts_code", "trade_date", "total_mv", "float_mv", "total_share", "float_share", "free_share", "turnover_rate", "turnover_rate_f", "pe", "pe_ttm", "pb", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_index_ci_daily", List.of("id", "ts_code", "trade_date", "name", "open", "low", "high", "close", "pre_close", "change_val", "pct_change", "vol", "amount", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_index_ci_member", List.of("id", "l1_code", "l1_name", "l2_code", "l2_name", "l3_code", "l3_name", "ts_code", "name", "in_date", "out_date", "is_new", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_index_sw_classify", List.of("id", "index_code", "industry_name", "parent_code", "level", "industry_code", "is_pub", "src", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_index_sw_daily", List.of("id", "ts_code", "trade_date", "name", "open", "low", "high", "close", "change_val", "pct_change", "vol", "amount", "pe", "pb", "float_mv", "total_mv", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_index_sw_member", List.of("id", "l1_code", "l1_name", "l2_code", "l2_name", "l3_code", "l3_name", "ts_code", "name", "in_date", "out_date", "is_new", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_fund_info", List.of("fund_info_id", "ts_code", "name", "management", "custodian", "fund_type", "found_date", "due_date", "list_date", "issue_date", "delist_date", "issue_amount", "m_fee", "c_fee", "duration_year", "p_value", "min_amount", "exp_return", "benchmark", "status", "invest_type", "type", "trustee", "purc_startdate", "redm_startdate", "market"));
        COLUMNS_BY_TABLE.put("alphafrog_fund_nav", List.of("fund_nav_id", "ts_code", "ann_date", "nav_date", "unit_nav", "accum_nav", "accum_div", "net_asset", "total_net_asset", "adj_nav"));
        COLUMNS_BY_TABLE.put("alphafrog_fund_portfolio", List.of("fund_portfolio_id", "ts_code", "ann_date", "end_date", "symbol", "mkv", "amount", "stk_mkv_ratio", "stk_float_ratio"));
        COLUMNS_BY_TABLE.put("alphafrog_fund_company", List.of("id", "name", "shortname", "short_enname", "province", "city", "address", "phone", "office", "website", "chairman", "manager", "reg_capital", "setup_date", "end_date", "employees", "main_business", "org_code", "credit_code", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_fund_manager", List.of("id", "ts_code", "ann_date", "name", "gender", "birth_year", "edu", "nationality", "begin_date", "end_date", "resume", "extended", "created_at", "updated_at"));
        COLUMNS_BY_TABLE.put("alphafrog_fund_share", List.of("id", "ts_code", "trade_date", "fd_share", "extended", "created_at", "updated_at"));
    }

    private MarketDbCatalog() {
    }

    public static List<String> tables(String category) {
        List<String> tables = TABLES_BY_CATEGORY.get(category);
        return tables == null ? List.of() : tables;
    }

    public static List<String> columns(String table) {
        List<String> columns = COLUMNS_BY_TABLE.get(table);
        return columns == null ? List.of() : columns;
    }

    public static boolean hasTable(String table) {
        return COLUMNS_BY_TABLE.containsKey(table);
    }

}
