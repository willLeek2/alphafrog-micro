# === work-package-01304：预设金融指标库——单测（按【计划】§5 单测上限收敛为 3 个）===
"""01304 的 15 项方法只留 3 个测试方法，与【计划】§3 阶段 3 验收列的三类一一对应
（frog 2026-09-29 15:03 拍板删测试：下个版本代码治理重点是删测试）。同一类里
多个样本/多个方法用 subTest 并列，不另起测试方法。

1. ``test_real_samples_match_independent_formula``——真实样本抽样比对。生产取值 +
   方法本体 + 测试内独立算式（不调用被测函数求期望值）。样本注明取数 SQL 与取数日期。
2. ``test_boundary_cases``——边界断言：亏损时 PE 空、无负超额收益时 Sortino 空、
   零回撤时 Calmar 空，以及缺值/非正比率/非法入参/多输出键等契约语义。
3. ``test_suspension_and_missing_rows``——停牌缺行：缺复权因子与无行情日**分开计数**、
   未披露净值日与停牌日跳过并告警、窗口不足时按方法各自的形态返回（不产出记录）。

估值/财务/券商预测的生产数据尚未入库，⑥⑦ 的真实样本在 ``alphafrog_stock_report_rc``
与 022 迁移新建的 ``alphafrog_stock_fina_indicator`` 灌数后补，当前用合成数据钉契约。
方法Id↔函数 的绑定门槛由 ``test_generated_bindings.py`` / ``test_generate_method_bindings.py``
的冻结面覆盖，本文件不重复。
"""
import math
import unittest

from alphafrog_finance import metrics

# 000001.SH 收盘，日期升序（2026-05-18 → 2026-08-28，70 个交易日）。
# 来源：生产库 alphafrog_index_daily，2026-09-29 取数。
SH_CLOSES = [
    4131.5276, 4169.5378, 4162.1845, 4077.2765, 4112.8996, 4152.5686, 4145.373,
    4093.7266, 4098.6358, 4068.5691, 4057.74, 4075.1016, 4083.974, 4057.7811,
    4027.7362, 3959.3378, 4010.0307, 3993.2258, 3987.0147, 4031.5129, 4096.4717,
    4091.8917, 4108.0762, 4090.4813, 4163.0965, 4106.2517, 4110.8134, 4112.4453,
    4028.9038, 4043.6432, 4041.2382, 3990.2353, 3970.8797, 4036.5879, 3996.1616,
    3913.794, 3967.1262, 3955.5781, 3882.4126, 3764.1547, 3796.2814, 3864.3671,
    3867.0336, 3876.7774, 3814.1978, 3858.245, 3813.3146, 3828.469, 3804.6926,
    3832.2624, 3809.6629, 3822.2846, 3878.4296, 3900.3523, 3940.0371, 3966.5935,
    3934.0929, 3946.6752, 3926.9648, 3927.1764, 3982.6535, 3990.3037, 3894.4224,
    3903.721, 3905.2026, 3882.0079, 3889.4449, 3912.5235, 3956.571, 3952.179,
]

# 000001.OF 华夏成长混合 累计净值，日期升序（2026-08-17 → 2026-08-28，10 个净值日）。
# 来源：生产库 alphafrog_fund_nav，2026-09-29 取数。
OF_ACCUM_NAVS = [3.976, 3.982, 3.892, 3.896, 3.902, 3.87, 3.86, 3.879, 3.913, 3.891]

# 510300.SH 收盘与复权因子，日期升序（2025-06-09 → 2025-06-30，共 16 个交易日），
# 跨过一次分红除权（因子 1.208 → 1.235）。取数日期 2026-09-29。取数 SQL（可原样重跑）：
#   SELECT d.close, a.adj_factor FROM alphafrog_domestic_etf_daily d
#     JOIN alphafrog_domestic_etf_adj_factor a
#       ON a.ts_code = d.ts_code AND a.trade_date = d.trade_date
#    WHERE d.ts_code = '510300.SH'
#      AND d.trade_date BETWEEN 1749340800000 AND 1751241600000
#    ORDER BY d.trade_date;
# 取这段窗口而不是 2025 全年的理由：同段内既有除权前也有除权后，期望值可用一条独立
# 算式表达。复权因子表自 2026-06-03 起有缺口，缺因子日行为由合成用例覆盖。
ETF_510300_CLOSES = [
    3.995, 3.972, 4.004, 4.006, 3.98, 3.99, 3.989, 3.904,
    3.873, 3.878, 3.891, 3.936, 4.001, 3.986, 3.963, 3.982,
]
ETF_510300_FACTORS = [1.208] * 7 + [1.235] * 9

# 沪深300 2026-08-28 的 pe_ttm / pb。来源：生产库 2026-09-29 取数，取数 SQL：
#   SELECT pe_ttm, pb FROM alphafrog_index_daily_basic
#    WHERE ts_code = '000300.SH' AND pe_ttm IS NOT NULL AND pb IS NOT NULL
#    ORDER BY trade_date DESC LIMIT 2;
# 全表 64623 行无非正值，非正分支由边界用例覆盖。
CSI300_PE_TTM = 13.8377
CSI300_PB = 1.4554


class PresetFinanceMethodTests(unittest.TestCase):
    def test_real_samples_match_independent_formula(self):
        """真实样本抽样比对：期望值一律在测试内用独立算式表达。"""
        self.assertEqual(len(SH_CLOSES), 70)

        with self.subTest(sample="上证指数 000001.SH 动量 63 日"):
            r = metrics.price_momentum(SH_CLOSES, window=63)
            self.assertAlmostEqual(r.value, SH_CLOSES[-1] / SH_CLOSES[-1 - 63] - 1.0, places=10)
            self.assertEqual(r.unit, "ratio")
            self.assertEqual(r.parameters["window"], 63)
            self.assertFalse(r.warnings)

        with self.subTest(sample="上证指数 均线 50 日（SMA 与 EMA 窗口口径不同）"):
            out = metrics.moving_average(SH_CLOSES, window=50)
            expected_sma = sum(SH_CLOSES[-50:]) / 50.0
            expected_ema = sum(SH_CLOSES[:50]) / 50.0
            alpha = 2.0 / (50 + 1)
            for price in SH_CLOSES[50:]:
                expected_ema = alpha * price + (1.0 - alpha) * expected_ema
            self.assertAlmostEqual(out["sma"].value, expected_sma, places=8)
            self.assertAlmostEqual(out["ema"].value, expected_ema, places=8)
            self.assertEqual(out["sma"].unit, "price")
            self.assertEqual(out["ema"].parameters["output"], "ema")

        with self.subTest(sample="上证指数 回撤/Sortino/Calmar"):
            out = metrics.drawdown_sortino_calmar(SH_CLOSES)
            # 7 月滑落：最低点 3764.1547 相对区间峰值 4169.5378
            self.assertAlmostEqual(out["maxDrawdown"].value, 3764.1547 / 4169.5378 - 1.0, places=10)
            self.assertEqual(set(out), {"maxDrawdown", "sortino", "calmar"})
            self.assertTrue(math.isfinite(out["sortino"].value))
            self.assertTrue(math.isfinite(out["calmar"].value))

        with self.subTest(sample="华夏成长混合 000001.OF 累计净值区间收益"):
            self.assertAlmostEqual(metrics.fund_accum_nav_return(OF_ACCUM_NAVS).value,
                                   3.891 / 3.976 - 1.0, places=12)
            self.assertAlmostEqual(metrics.fund_accum_nav_return(OF_ACCUM_NAVS, window=4).value,
                                   3.891 / 3.87 - 1.0, places=12)

        with self.subTest(sample="510300.SH 跨分红除权的复权区间收益"):
            r = metrics.etf_adj_return(ETF_510300_CLOSES, adj_factors=ETF_510300_FACTORS)
            self.assertAlmostEqual(
                r.value,
                ETF_510300_CLOSES[-1] * ETF_510300_FACTORS[-1]
                / (ETF_510300_CLOSES[0] * ETF_510300_FACTORS[0]) - 1.0,
                places=12,
            )
            # 复权后收益必须高于未复权收盘比值，否则说明因子没被用上
            unadjusted = ETF_510300_CLOSES[-1] / ETF_510300_CLOSES[0] - 1.0
            self.assertLess(unadjusted, 0.0, "样本窗口未复权口径应为负（除权跳降）")
            self.assertGreater(r.value, unadjusted)
            self.assertFalse(r.warnings, "样本窗口因子全配对，不应有缺因子告警")
            win = metrics.etf_adj_return(ETF_510300_CLOSES, window=5,
                                         adj_factors=ETF_510300_FACTORS)
            self.assertAlmostEqual(
                win.value,
                (ETF_510300_CLOSES[-1] * ETF_510300_FACTORS[-1])
                / (ETF_510300_CLOSES[-6] * ETF_510300_FACTORS[-6]) - 1.0,
                places=12,
            )

        with self.subTest(sample="沪深300 指数市盈率与市净率"):
            out = metrics.index_pe_pb(CSI300_PE_TTM, CSI300_PB)
            self.assertEqual(set(out), {"peTtm", "pb"})
            self.assertAlmostEqual(out["peTtm"].value, CSI300_PE_TTM, places=10)
            self.assertAlmostEqual(out["pb"].value, CSI300_PB, places=10)
            self.assertEqual(out["pb"].parameters["output"], "pb")

    def test_boundary_cases(self):
        """边界断言：缺值/非正比率/非法入参/多输出键/窗口枚举。"""
        with self.subTest(case="滚动市盈率 亏损与全缺"):
            full = metrics.rolling_pe([10.0, 12.0, 8.0, None, 11.0], window=4)
            self.assertEqual(set(full), {"peTtm", "ep", "pePercentile"})
            self.assertAlmostEqual(full["ep"].value, 1.0 / 11.0, places=12)
            # 窗口取最后 4 个有值日 [10, 12, 8, 11] 中不高于 11 的：10、8、11 → 3/4
            self.assertAlmostEqual(full["pePercentile"].value, 0.75, places=12)
            self.assertEqual(full["ep"].parameters["output"], "ep")
            loss = metrics.rolling_pe([10.0, -5.0])  # 亏损：PE 不产出、告警
            self.assertEqual(set(loss), {"peTtm"})
            self.assertTrue(any("未输出" in w for w in loss["peTtm"].warnings))
            self.assertEqual(metrics.rolling_pe([None, None]), {})

        with self.subTest(case="标量估值：无值与非正比率都省略记录"):
            self.assertEqual(metrics.price_to_book(1.5).value, 1.5)
            self.assertEqual(metrics.dividend_yield(2.5).value, 2.5)
            self.assertEqual(metrics.free_float_turnover(1.2).value, 1.2)
            self.assertEqual(metrics.ah_premium(31.4).value, 31.4)
            for none_case in (metrics.price_to_book(None), metrics.dividend_yield(None),
                              metrics.free_float_turnover(None), metrics.ah_premium(None),
                              metrics.price_to_book(-1.0), metrics.price_to_book(0.0)):
                self.assertIsNone(none_case)

        with self.subTest(case="指数 PE/PB：非正 pb 只省略 pb 键"):
            self.assertEqual(set(metrics.index_pe_pb(14.2, 1.6)), {"peTtm", "pb"})
            only_pe = metrics.index_pe_pb(14.2, None)
            self.assertEqual(set(only_pe), {"peTtm"})
            self.assertEqual(only_pe["peTtm"].parameters["output"], "peTtm")
            non_positive = metrics.index_pe_pb(14.2, -0.5)
            self.assertEqual(set(non_positive), {"peTtm"})
            self.assertEqual(non_positive["peTtm"].value, 14.2)
            self.assertEqual(metrics.index_pe_pb(None, None), {})

        with self.subTest(case="转债双溢价：部分输出"):
            both = metrics.cb_premium(12.5, 8.0)
            self.assertEqual(set(both), {"cbOverRate", "bondOverRate"})
            self.assertEqual(set(metrics.cb_premium(12.5, None)), {"cbOverRate"})
            self.assertEqual(metrics.cb_premium(None, None), {})

        with self.subTest(case="杜邦：部分缺值与全缺"):
            out = metrics.dupont_roe(12.0, 15.0, 0.8, 1.0)
            self.assertEqual(
                set(out),
                {"roeDt", "netprofitMargin", "assetsTurn", "assetsToEqt", "dupontApprox"},
            )
            self.assertAlmostEqual(out["dupontApprox"].value, 12.0, places=12)
            self.assertTrue(out["roeDt"].checks["dupontConsistent"])
            self.assertEqual(set(metrics.dupont_roe(12.0, None, 0.8, None)), {"roeDt", "assetsTurn"})
            self.assertEqual(metrics.dupont_roe(None, None, None, None), {})

        with self.subTest(case="⑦ 预测 PE/PEG：组内配对、跨券商中位数、增速非正只出 PE"):
            rows = [
                {"org_name": "甲", "quarter": "2025Q4", "pe": 18.0, "eps": 1.0},
                {"org_name": "甲", "quarter": "2026Q1", "pe": 20.0, "eps": 1.25},  # g=25% → PEG=0.8
                {"org_name": "乙", "quarter": "2025Q4", "pe": 9.0, "eps": 2.0},
                {"org_name": "乙", "quarter": "2026Q1", "pe": 15.0, "eps": 2.2},   # g=10% → PEG=1.5
                {"org_name": "丙", "quarter": "2026Q1", "pe": 22.0, "eps": None},  # 单期 → 只出 forwardPe
            ]
            out = metrics.forward_pe_peg(rows)
            self.assertEqual(set(out), {"forwardPe", "peg"})
            self.assertEqual(out["forwardPe"].value, 20.0)   # median(20, 15, 22)
            self.assertAlmostEqual(out["peg"].value, 1.15, places=12)  # median(0.8, 1.5)
            self.assertEqual(out["forwardPe"].parameters["output"], "forwardPe")
            # rows 与 yaml 必填参数对齐（投影必检），rowCount/orgCount 为附加审计键
            self.assertEqual(len(out["forwardPe"].parameters["rows"]), 5)
            self.assertEqual(out["forwardPe"].parameters["rowCount"], 5)
            self.assertEqual(out["forwardPe"].parameters["orgCount"], 3)
            single = metrics.forward_pe_peg([{"org_name": "甲", "quarter": "2026Q1", "pe": 12.0, "eps": 1.0}])
            self.assertEqual(set(single), {"forwardPe"})
            self.assertTrue(any("PEG 未输出" in w for w in single["forwardPe"].warnings))
            # 全部无有效 pe → 空字典（null 不当 0）；增速为负 → 不产出 PEG
            self.assertEqual(
                metrics.forward_pe_peg([{"org_name": "甲", "quarter": "2026Q1", "pe": None, "eps": 1.0}]), {})
            negative = metrics.forward_pe_peg([
                {"org_name": "甲", "quarter": "2025Q4", "pe": 10.0, "eps": 2.0},
                {"org_name": "甲", "quarter": "2026Q1", "pe": 10.0, "eps": 1.0},
            ])
            self.assertEqual(set(negative), {"forwardPe"})

        with self.subTest(case="⑦ 入参校验：非列表/机构名空/pe 非数"):
            with self.assertRaises(ValueError):
                metrics.forward_pe_peg("not-a-list")
            with self.assertRaises(ValueError):
                metrics.forward_pe_peg([{"org_name": "", "quarter": "2026Q1"}])
            with self.assertRaises(ValueError):
                metrics.forward_pe_peg([{"org_name": "甲", "quarter": "2026Q1", "pe": "x"}])

        with self.subTest(case="窗口枚举只约束 ⑧：传 100 报错，其余方法合法"):
            with self.assertRaises(ValueError):
                metrics.price_momentum(SH_CLOSES, window=100)
            self.assertIsNone(metrics.etf_adj_return([1.0, 2.0], window=100))      # ⑨ 合法窗
            self.assertIsNone(metrics.fund_accum_nav_return(OF_ACCUM_NAVS, window=100))  # ⑩ 合法窗
            self.assertEqual(set(metrics.moving_average(SH_CLOSES, window=2)), {"sma", "ema"})  # ⑭ 合法窗
            with self.assertRaises(ValueError):
                metrics.moving_average(SH_CLOSES, window=1)   # ⑭ 下限是 2

        with self.subTest(case="⑬ 有效点 <2 抛错（不是「不足即不产出」）"):
            with self.assertRaises(ValueError):
                metrics.drawdown_sortino_calmar([10.0])
            # 区间单调不回撤（零回撤 → Calmar 不产出）且无负超额收益（→ Sortino 不产出）
            flat = metrics.drawdown_sortino_calmar([10.0] * 5)
            self.assertEqual(set(flat), {"maxDrawdown"})
            self.assertEqual(flat["maxDrawdown"].value, 0.0)

    def test_suspension_and_missing_rows(self):
        """停牌缺行：缺复权因子与无行情日分开计数；未披露/停牌跳过并告警；窗口不足不产出。"""
        with self.subTest(case="缺复权因子与无行情日分开计数"):
            closes = [10.0] * 64 + [None, 12.0]
            factors = [1.0] * 63 + [None, 1.0, 1.0]
            r = metrics.price_momentum(closes, window=63, adj_factors=factors)
            self.assertAlmostEqual(r.value, 12.0 / 10.0 - 1.0, places=12)
            self.assertTrue(any("缺复权因子 1" in w for w in r.warnings))
            self.assertTrue(any("无行情 1" in w for w in r.warnings))

        with self.subTest(case="复权因子与收盘长度不匹配直接拒绝"):
            with self.assertRaises(ValueError):
                metrics.price_momentum([10.0] * 64, window=63, adj_factors=[1.0, 2.0])

        with self.subTest(case="未披露净值日跳过并告警"):
            gap = metrics.fund_accum_nav_return([1.0, None, 1.2, 1.5])
            self.assertAlmostEqual(gap.value, 0.5, places=12)
            self.assertTrue(any("未披露" in w for w in gap.warnings))

        with self.subTest(case="停牌日不计入窗口（合成序列有 None 收盘）"):
            with_gap = metrics.etf_adj_return([10.0, None, 12.0, 13.0], adj_factors=[1.0] * 4)
            self.assertAlmostEqual(with_gap.value, 13.0 / 10.0 - 1.0, places=12)
            self.assertTrue(any("无行情" in w for w in with_gap.warnings))

        with self.subTest(case="转债停牌日（pct_chg 为 None）跳过并告警"):
            out = metrics.cb_daily_return([1.0, 2.0, -0.5, 3.0, None, 2.0])
            self.assertEqual(set(out), {"dailyReturn", "intervalReturn"})
            self.assertAlmostEqual(out["dailyReturn"].value, 0.02, places=12)
            self.assertAlmostEqual(out["intervalReturn"].value,
                                   1.01 * 1.02 * 0.995 * 1.03 * 1.02 - 1.0, places=12)
            self.assertTrue(any("已跳过" in w for w in out["dailyReturn"].warnings))

        with self.subTest(case="窗口不足时各方法自己的返回形态（不抛错）"):
            self.assertIsNone(metrics.price_momentum([1.0, 2.0], window=63))     # ⑧ → None
            self.assertIsNone(metrics.etf_adj_return([1.0], adj_factors=[1.0]))  # ⑨ → None
            self.assertIsNone(metrics.fund_accum_nav_return([1.0]))               # ⑩ → None
            self.assertEqual(metrics.moving_average(SH_CLOSES[:10], window=50), {})  # ⑭ → {}
            self.assertEqual(metrics.cb_daily_return([]), {})                     # ⑪ → {}
            short = metrics.cb_daily_return([1.0, 2.0], window=5)  # ⑪ 仍出日收益、只省略区间收益
            self.assertEqual(set(short), {"dailyReturn"})
            self.assertTrue(any("不足窗口" in w for w in short["dailyReturn"].warnings))


if __name__ == "__main__":
    unittest.main()
