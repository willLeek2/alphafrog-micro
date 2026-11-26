package world.willfrog.agent.tools.dataanalysis;

import java.util.HashMap;
import java.util.Map;

/**
 * market_schema 附在列名旁边的解释。
 * 文字来自实体字段上的中文注释，以及迁移脚本里的 COMMENT ON COLUMN。
 * 这两处都没有文字的列不出现在这里。
 */
public final class MarketDbColumnNotes {

    private static final Map<String, Map<String, String>> NOTES = new HashMap<>();

    static {
        Map<String, String> n_alphafrog_stock_info = new HashMap<>();
        n_alphafrog_stock_info.put("ts_code", "TS代码");
        n_alphafrog_stock_info.put("symbol", "股票代码");
        n_alphafrog_stock_info.put("name", "股票名称");
        n_alphafrog_stock_info.put("area", "地域");
        n_alphafrog_stock_info.put("industry", "所属行业");
        n_alphafrog_stock_info.put("fullname", "股票全称");
        n_alphafrog_stock_info.put("enname", "英文全称");
        n_alphafrog_stock_info.put("cnspell", "拼音缩写");
        n_alphafrog_stock_info.put("market", "市场类型（主板/创业板/科创板/CDR）");
        n_alphafrog_stock_info.put("exchange", "交易所代码");
        n_alphafrog_stock_info.put("curr_type", "交易货币");
        n_alphafrog_stock_info.put("list_status", "上市状态 L上市 D退市 P暂停上市");
        n_alphafrog_stock_info.put("list_date", "上市日期");
        n_alphafrog_stock_info.put("delist_date", "退市日期");
        n_alphafrog_stock_info.put("is_hs", "是否沪深港通标的，N否 H沪股通 S深股通");
        n_alphafrog_stock_info.put("act_name", "实控人名称");
        n_alphafrog_stock_info.put("act_ent_type", "实控人企业性质");
        NOTES.put("alphafrog_stock_info", Map.copyOf(n_alphafrog_stock_info));
        Map<String, String> n_alphafrog_stock_daily = new HashMap<>();
        n_alphafrog_stock_daily.put("adj_factor", "复权因子（TuShare adj_factor）。接口只返回这一个指标字段，按列保存。");
        NOTES.put("alphafrog_stock_daily", Map.copyOf(n_alphafrog_stock_daily));
        Map<String, String> n_alphafrog_stock_fina_indicator = new HashMap<>();
        n_alphafrog_stock_fina_indicator.put("profitability", "盈利与回报：roe*/roa*/roic*、利润率、扣非相关（第一轮用 roe_dt、netprofit_margin）。");
        n_alphafrog_stock_fina_indicator.put("per_share", "每股：*ps、eps、bps。");
        n_alphafrog_stock_fina_indicator.put("capital_cash", "营运、偿债与现金流、资本结构（第一轮杜邦用 assets_turn、assets_to_eqt）。");
        n_alphafrog_stock_fina_indicator.put("growth", "增长：yoy、qoq、研发。单季 q_ 前缀字段按所属类别归入同类 JSON。");
        n_alphafrog_stock_fina_indicator.put("extended", "未进四类的字段与接口以后新增的字段，键值原样保存（列名与利润表 extended 相同）。");
        NOTES.put("alphafrog_stock_fina_indicator", Map.copyOf(n_alphafrog_stock_fina_indicator));
        Map<String, String> n_alphafrog_stock_balancesheet = new HashMap<>();
        n_alphafrog_stock_balancesheet.put("money_cap", "流动资产");
        n_alphafrog_stock_balancesheet.put("fix_assets", "非流动资产");
        n_alphafrog_stock_balancesheet.put("st_borr", "流动负债");
        n_alphafrog_stock_balancesheet.put("lt_borr", "非流动负债");
        n_alphafrog_stock_balancesheet.put("total_hldr_eqy_exc_min_int", "股东权益");
        NOTES.put("alphafrog_stock_balancesheet", Map.copyOf(n_alphafrog_stock_balancesheet));
        Map<String, String> n_alphafrog_stock_cashflow = new HashMap<>();
        n_alphafrog_stock_cashflow.put("c_fr_sale_sg", "经营活动");
        n_alphafrog_stock_cashflow.put("n_cashflow_inv_act", "投资活动");
        n_alphafrog_stock_cashflow.put("n_cash_flows_fnc_act", "筹资活动");
        n_alphafrog_stock_cashflow.put("free_cashflow", "汇总");
        NOTES.put("alphafrog_stock_cashflow", Map.copyOf(n_alphafrog_stock_cashflow));
        Map<String, String> n_alphafrog_stock_moneyflow = new HashMap<>();
        n_alphafrog_stock_moneyflow.put("buy_sm_vol", "小单");
        n_alphafrog_stock_moneyflow.put("buy_md_vol", "中单");
        n_alphafrog_stock_moneyflow.put("buy_lg_vol", "大单");
        n_alphafrog_stock_moneyflow.put("buy_elg_vol", "特大单");
        n_alphafrog_stock_moneyflow.put("net_mf_vol", "净流入");
        NOTES.put("alphafrog_stock_moneyflow", Map.copyOf(n_alphafrog_stock_moneyflow));
        Map<String, String> n_alphafrog_index_info = new HashMap<>();
        n_alphafrog_index_info.put("ts_code", "指数代码");
        n_alphafrog_index_info.put("name", "指数名称");
        n_alphafrog_index_info.put("fullname", "指数全名");
        n_alphafrog_index_info.put("market", "指数所属市场");
        n_alphafrog_index_info.put("publisher", "指数发布者");
        n_alphafrog_index_info.put("index_type", "指数风格");
        n_alphafrog_index_info.put("category", "指数类别");
        n_alphafrog_index_info.put("base_date", "基期");
        n_alphafrog_index_info.put("base_point", "基点");
        n_alphafrog_index_info.put("list_date", "发布日期");
        n_alphafrog_index_info.put("weight_rule", "加权方式");
        n_alphafrog_index_info.put("desc", "描述");
        n_alphafrog_index_info.put("exp_date", "终止日期");
        NOTES.put("alphafrog_index_info", Map.copyOf(n_alphafrog_index_info));
        Map<String, String> n_alphafrog_fund_info = new HashMap<>();
        n_alphafrog_fund_info.put("ts_code", "基金代码");
        n_alphafrog_fund_info.put("name", "简称");
        n_alphafrog_fund_info.put("management", "管理人");
        n_alphafrog_fund_info.put("custodian", "托管人");
        n_alphafrog_fund_info.put("fund_type", "投资类型");
        n_alphafrog_fund_info.put("found_date", "成立日期");
        n_alphafrog_fund_info.put("due_date", "到期日期");
        n_alphafrog_fund_info.put("list_date", "上市时间");
        n_alphafrog_fund_info.put("issue_date", "发行日期");
        n_alphafrog_fund_info.put("delist_date", "退市日期");
        n_alphafrog_fund_info.put("issue_amount", "发行份额（亿份）");
        n_alphafrog_fund_info.put("m_fee", "管理费");
        n_alphafrog_fund_info.put("c_fee", "托管费");
        n_alphafrog_fund_info.put("duration_year", "存续期");
        n_alphafrog_fund_info.put("p_value", "面值");
        n_alphafrog_fund_info.put("min_amount", "起点金额（万元）");
        n_alphafrog_fund_info.put("exp_return", "预期收益率");
        n_alphafrog_fund_info.put("benchmark", "业绩比较基准");
        n_alphafrog_fund_info.put("status", "存续状态或摘牌");
        n_alphafrog_fund_info.put("invest_type", "投资风格");
        n_alphafrog_fund_info.put("type", "基金类型");
        n_alphafrog_fund_info.put("trustee", "受托人");
        n_alphafrog_fund_info.put("purc_startdate", "日常申购起始日");
        n_alphafrog_fund_info.put("redm_startdate", "日常赎回起始日");
        n_alphafrog_fund_info.put("market", "E场内O场外");
        NOTES.put("alphafrog_fund_info", Map.copyOf(n_alphafrog_fund_info));
        Map<String, String> n_alphafrog_fund_nav = new HashMap<>();
        n_alphafrog_fund_nav.put("ts_code", "基金代码");
        n_alphafrog_fund_nav.put("ann_date", "公告日期");
        n_alphafrog_fund_nav.put("nav_date", "净值日期");
        n_alphafrog_fund_nav.put("unit_nav", "单位净值");
        n_alphafrog_fund_nav.put("accum_nav", "累计净值");
        n_alphafrog_fund_nav.put("net_asset", "资产净值");
        NOTES.put("alphafrog_fund_nav", Map.copyOf(n_alphafrog_fund_nav));
        Map<String, String> n_alphafrog_fund_portfolio = new HashMap<>();
        n_alphafrog_fund_portfolio.put("ts_code", "基金代码");
        n_alphafrog_fund_portfolio.put("ann_date", "持仓公告日期");
        n_alphafrog_fund_portfolio.put("end_date", "持仓截止日期");
        n_alphafrog_fund_portfolio.put("symbol", "持仓股票代码");
        n_alphafrog_fund_portfolio.put("mkv", "持有股票市值");
        n_alphafrog_fund_portfolio.put("amount", "持有股票数量");
        n_alphafrog_fund_portfolio.put("stk_mkv_ratio", "占股票市值比");
        n_alphafrog_fund_portfolio.put("stk_float_ratio", "占流通股本比例");
        NOTES.put("alphafrog_fund_portfolio", Map.copyOf(n_alphafrog_fund_portfolio));
    }

    private MarketDbColumnNotes() {
    }

    public static String explanation(String table, String column) {
        Map<String, String> tableNotes = NOTES.get(table);
        if (tableNotes == null) {
            return null;
        }
        return tableNotes.get(column);
    }
}
