package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import java.util.Set;

/**
 * 财务指标字段分桶（TuShare fina_indicator_vip → 022 迁移建的五列 JSONB）。
 *
 * <p>接口一个报告期返回约 170 个字段，表里没有对应列，按 022 迁移第 43-52 行的注释分四类收进
 * JSONB，对不上四类的进 {@code extended}。分桶规则就是那四条注释的原文口径：</p>
 * <ul>
 *   <li>每股：{@code *ps}、{@code eps}、{@code bps}；</li>
 *   <li>增长：{@code yoy}、{@code qoq}、研发；</li>
 *   <li>盈利与回报：{@code roe*}/{@code roa*}/{@code roic*}、利润率、扣非相关；</li>
 *   <li>营运偿债现金流与资本结构：周转、偿债、现金流、资本结构；</li>
 *   <li>单季 {@code q_} 前缀字段按去掉前缀后的字段名归入同一类（022 迁移第 50 行）。</li>
 * </ul>
 *
 * <p><b>归不进四类的一律进 extended，不硬塞。</b> extended 是无损的（键值原样保存），而错分桶
 * 会让读侧按错误的语义取数；所以宁可留 extended。这条也让接口以后新增字段时不必改这里——
 * 新键自动落 extended。</p>
 *
 * <p>归属有歧义的字段本类不做判断，也不写死猜测：能明确归类的进集合，不能的留 extended。
 * 两组字段由 {@code FinaIndicatorFieldBucketsTest} 逐个钉住：① ⑥ 扣非ROE 与杜邦三组件要用的
 * 四个字段（{@code roe_dt}、{@code netprofit_margin} 进 profitability；{@code assets_turn}、
 * {@code assets_to_eqt} 进 capital_cash）——写侧分错桶，⑥ 就会读到空值，而且要到部署后才发现；
 * ② 七个非经营性损益构成字段（{@code op_income}、{@code opincome}、{@code valuechange_income}、
 * {@code investincome}、{@code interst_income}、{@code opincome_of_ebt}、{@code investincome_of_ebt}）
 * 留在 extended——它们既不是营运、偿债与现金流，也不是资本结构，硬塞进 capital_cash 会让
 * 以后按桶名取数的人拿错语义。</p>
 */
public final class FinaIndicatorFieldBuckets {

    /** 五个 JSONB 桶。 */
    public enum Bucket {
        PROFITABILITY,
        PER_SHARE,
        CAPITAL_CASH,
        GROWTH,
        EXTENDED
    }

    /** 三个有独立列的字段，不进任何 JSONB 桶。 */
    public static final Set<String> COLUMN_FIELDS = Set.of("ts_code", "ann_date", "end_date");

    /**
     * 盈利与回报：回报率、利润率与费用率、扣非与利润构成。
     * 口径出自 022 迁移的「roe / roa / roic 系列、利润率、扣非相关」。
     */
    private static final Set<String> PROFITABILITY = Set.of(
            // 扣非与利润构成
            "extra_item", "profit_dedt", "gross_margin",
            // 利润率与费用率
            "netprofit_margin", "grossprofit_margin", "gsprofit_margin", "cogs_of_sales",
            "expense_of_sales", "exp_to_sales", "profit_to_gr", "saleexp_to_gr", "adminexp_of_gr",
            "finaexp_of_gr", "impai_ttm", "impair_to_gr_ttm", "gc_of_gr", "op_of_gr", "ebit_of_gr",
            "profit_to_op",
            // 回报率
            "roe", "roe_waa", "roe_dt", "roa", "npta", "roic", "roe_yearly", "roa2_yearly",
            "roe_avg", "roa_yearly", "roa_dp", "roic_yearly",
            // 息税前利润族
            "ebit", "ebitda",
            // 利润总额相关
            "n_op_profit_of_ebt", "tax_to_ebt", "dtprofit_to_profit", "profit_prefin_exp",
            "non_op_profit", "op_to_ebt", "nop_to_ebt", "dtprofit"
    );

    /**
     * 营运、偿债与现金流、资本结构。
     * 口径出自 022 迁移「营运、偿债与现金流、资本结构（第一轮杜邦用 assets_turn、assets_to_eqt）」。
     */
    private static final Set<String> CAPITAL_CASH = Set.of(
            // 流动性与营运
            "current_ratio", "quick_ratio", "cash_ratio",
            "invturn_days", "arturn_days", "inv_turn", "ar_turn", "ca_turn", "fa_turn",
            "assets_turn", "turn_days", "total_fa_trun", "working_capital", "networking_capital",
            "fixed_assets",
            // 债务与资本结构
            "debt_to_assets", "assets_to_eqt", "dp_assets_to_eqt", "ca_to_assets", "nca_to_assets",
            "tbassets_to_totalassets", "int_to_talcap", "eqt_to_talcapital", "currentdebt_to_debt",
            "longdeb_to_debt", "debt_to_eqt", "eqt_to_debt", "eqt_to_interestdebt", "ebit_to_interest",
            "ebitda_to_debt", "longdebt_to_workingcapital",
            "current_exint", "noncurrent_exint", "interestdebt", "netdebt", "tangible_asset",
            "tangibleasset_to_debt", "tangasset_to_intdebt", "tangibleasset_to_netdebt",
            "invest_capital", "retained_earnings",
            // 现金流
            "daa", "fcff", "fcfe", "capitalized_to_da",
            "salescash_to_or", "ocf_to_or", "ocf_to_sales", "ocf_to_opincome", "ocf_to_profit",
            "ocf_to_shortdebt", "ocf_to_debt", "ocf_to_interestdebt", "ocf_to_netdebt",
            "cash_to_liqdebt", "cash_to_liqdebt_withinterest", "op_to_liqdebt", "op_to_debt"
    );

    /** 进 growth 的研发字段。口径出自 022 迁移「增长：yoy、qoq、研发」。 */
    private static final Set<String> GROWTH_EXTRA = Set.of("rd_exp");

    private FinaIndicatorFieldBuckets() {
    }

    /**
     * 判定一个接口字段进哪个 JSONB 桶。三个有独立列的字段（ts_code/ann_date/end_date）
     * 返回 {@code null}，调用方按列处理。
     */
    public static Bucket classify(String field) {
        if (field == null || field.isBlank() || COLUMN_FIELDS.contains(field)) {
            return null;
        }
        // 增长优先：yoy/qoq 是 022 注释里点名的口径，eps_yoy 这类同时像每股的按增长走。
        if (field.contains("yoy") || field.contains("qoq") || GROWTH_EXTRA.contains(field)) {
            return Bucket.GROWTH;
        }
        // 单季 q_ 前缀按去掉前缀后的字段名归类（022 迁移第 50 行）
        String base = field.startsWith("q_") ? field.substring(2) : field;

        // 每股：*ps / *eps / bps / ocfps / retainedps / cfps（022 迁移「每股：*ps、eps、bps」）
        if (base.endsWith("_ps") || base.endsWith("_eps")
                || base.equals("eps") || base.equals("bps")
                || base.equals("ocfps") || base.equals("retainedps") || base.equals("cfps")) {
            return Bucket.PER_SHARE;
        }
        if (PROFITABILITY.contains(base)) {
            return Bucket.PROFITABILITY;
        }
        if (CAPITAL_CASH.contains(base)) {
            return Bucket.CAPITAL_CASH;
        }
        // 对不上四类、以及接口以后新增的字段，一律进 extended（无损，不硬塞）
        return Bucket.EXTENDED;
    }
}
