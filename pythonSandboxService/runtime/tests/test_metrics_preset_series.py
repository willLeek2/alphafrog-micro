# === work-package-01304：预设金融指标库——序列方法真实样本测试 ===
"""序列输入一族（动量/均线/区间收益/回撤/转债）的真实数据样本核对。

真实样本取自生产库 2026-09-29 快照：
* 上证指数 000001.SH 2026-05-18 ~ 2026-08-28 共 70 个交易日收盘（alphafrog_index_daily）；
* 华夏成长混合 000001.OF 2026-08-17 ~ 2026-08-28 共 10 个净值日累计净值（alphafrog_fund_nav）；
* 沪深 300 ETF 510300.SH 2025-06-09 ~ 2025-06-30 共 16 个交易日的收盘与复权因子
  （alphafrog_domestic_etf_daily / alphafrog_domestic_etf_adj_factor，跨一次分红除权）。
期望值在测试内以独立算式直接表达（不调用被测函数）。
可转债当前无真实数据（转债表待抓取任务灌数），其契约语义与复权缺值/无行情的
分开计数用合成数据覆盖。
"""
import math
import unittest

from alphafrog_finance import metrics

# 000001.SH 收盘，日期升序（2026-05-18 → 2026-08-28），来源：生产 alphafrog_index_daily
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

# 000001.OF 累计净值，日期升序（2026-08-17 → 2026-08-28），来源：生产 alphafrog_fund_nav
OF_ACCUM_NAVS = [3.976, 3.982, 3.892, 3.896, 3.902, 3.87, 3.86, 3.879, 3.913, 3.891]

# 510300.SH 收盘与复权因子，日期升序（2025-06-09 → 2025-06-30，共 16 个交易日），
# 跨过一次分红除权（因子 1.208 → 1.235）。
# 来源：生产库 2026-09-29 取数。取数 SQL（可原样重跑）：
#   SELECT d.close, a.adj_factor FROM alphafrog_domestic_etf_daily d
#     JOIN alphafrog_domestic_etf_adj_factor a
#       ON a.ts_code = d.ts_code AND a.trade_date = d.trade_date
#    WHERE d.ts_code = '510300.SH'
#      AND d.trade_date BETWEEN 1749340800000 AND 1751241600000
#    ORDER BY d.trade_date;
# 取这段窗口而不是 2025 全年（243 个交易日全配对）的理由：同段内既有除权前也有除权后，
# 期望值可用一条独立算式表达；全量数据留待按需扩展。取数日期 2026-09-29——
# 复权因子表自 2026-06-03 起存在缺口（510300.SH 缺到 2026-08-27、31 个交易日），
# 缺因子日的行为由合成测试覆盖，不受生产数据后续补跑影响。
ETF_510300_CLOSES = [
    3.995, 3.972, 4.004, 4.006, 3.98, 3.99, 3.989, 3.904,
    3.873, 3.878, 3.891, 3.936, 4.001, 3.986, 3.963, 3.982,
]
ETF_510300_FACTORS = [1.208] * 7 + [1.235] * 9


class SeriesRealSampleTests(unittest.TestCase):
    def setUp(self):
        self.assertEqual(len(SH_CLOSES), 70)

    def test_price_momentum_real_sh_index_63d(self):
        result = metrics.price_momentum(SH_CLOSES, window=63)
        expected = SH_CLOSES[-1] / SH_CLOSES[-1 - 63] - 1.0
        self.assertAlmostEqual(result.value, expected, places=10)
        self.assertEqual(result.unit, "ratio")
        self.assertEqual(result.parameters["window"], 63)
        self.assertFalse(result.warnings)

    def test_price_momentum_window_enum_enforced(self):
        with self.assertRaises(ValueError):
            metrics.price_momentum(SH_CLOSES, window=100)
        # 有效日不足窗口：不产出记录（返回 None），调用方换较短窗口
        self.assertIsNone(metrics.price_momentum([1.0, 2.0], window=63))

    def test_moving_average_real_sh_index_50d(self):
        out = metrics.moving_average(SH_CLOSES, window=50)
        # SMA 取最近 50 日；EMA 以最早 50 日均值为种子迭代到末端（两窗口口径不同）
        expected_sma = sum(SH_CLOSES[-50:]) / 50.0
        expected_ema = sum(SH_CLOSES[:50]) / 50.0
        alpha = 2.0 / (50 + 1)
        for price in SH_CLOSES[50:]:
            expected_ema = alpha * price + (1.0 - alpha) * expected_ema
        self.assertAlmostEqual(out["sma"].value, expected_sma, places=8)
        self.assertAlmostEqual(out["ema"].value, expected_ema, places=8)
        self.assertEqual(out["sma"].unit, "price")
        self.assertEqual(out["ema"].parameters["output"], "ema")
        # 有效日不足窗口：空字典，不产出记录
        self.assertEqual(metrics.moving_average(SH_CLOSES[:10], window=50), {})

    def test_drawdown_real_sh_index(self):
        out = metrics.drawdown_sortino_calmar(SH_CLOSES)
        # 7 月滑落：最低点 3764.1547 相对区间峰值 4169.5378
        self.assertAlmostEqual(
            out["maxDrawdown"].value, 3764.1547 / 4169.5378 - 1.0, places=10
        )
        # 该窗口负超额充足，三输出齐全；Sortino/Calmar 为有限数
        self.assertEqual(set(out), {"maxDrawdown", "sortino", "calmar"})
        self.assertTrue(math.isfinite(out["sortino"].value))
        self.assertTrue(math.isfinite(out["calmar"].value))

    def test_fund_accum_nav_return_real(self):
        result = metrics.fund_accum_nav_return(OF_ACCUM_NAVS)
        self.assertAlmostEqual(result.value, 3.891 / 3.976 - 1.0, places=12)
        with_window = metrics.fund_accum_nav_return(OF_ACCUM_NAVS, window=4)
        self.assertAlmostEqual(
            with_window.value, 3.891 / 3.87 - 1.0, places=12
        )
        # 未披露净值日跳过并告警
        gap = metrics.fund_accum_nav_return([1.0, None, 1.2, 1.5])
        self.assertAlmostEqual(gap.value, 0.5)
        self.assertTrue(any("未披露" in w for w in gap.warnings))

    def test_adjusted_series_split_counts(self):
        closes = [10.0] * 64 + [None, 12.0]
        factors = [1.0] * 63 + [None, 1.0, 1.0]
        r = metrics.price_momentum(closes, window=63, adj_factors=factors)
        self.assertAlmostEqual(r.value, 12.0 / 10.0 - 1.0, places=12)
        self.assertTrue(any("缺复权因子 1" in w for w in r.warnings))
        self.assertTrue(any("无行情 1" in w for w in r.warnings))
        # 长度不匹配直接拒绝
        with self.assertRaises(ValueError):
            metrics.price_momentum(closes, window=63, adj_factors=[1.0, 2.0])

    def test_etf_adj_return_synthetic_with_factors(self):
        closes = [10.0, 11.0, 12.0, 13.0]
        factors = [1.0, 1.0, 1.1, 1.0]
        whole = metrics.etf_adj_return(closes, adj_factors=factors)
        self.assertAlmostEqual(whole.value, 13.0 / 10.0 - 1.0, places=12)
        win = metrics.etf_adj_return(closes, window=2, adj_factors=factors)
        self.assertAlmostEqual(win.value, 13.0 / 11.0 - 1.0, places=12)
        # 有效日不足：不产出记录（None）
        self.assertIsNone(metrics.etf_adj_return([1.0], adj_factors=[1.0]))
        self.assertIsNone(
            metrics.etf_adj_return(closes, window=9, adj_factors=factors)
        )

    def test_etf_adj_return_real_510300_with_dividend(self):
        # 真实样本跨一次分红除权：复权后序列的区间收益必须高于未复权的收盘比值，
        # 否则说明复权因子没被用上（除权缺口正是这个口径要挡的错误）。
        result = metrics.etf_adj_return(
            ETF_510300_CLOSES, adj_factors=ETF_510300_FACTORS
        )
        expected = (
            ETF_510300_CLOSES[-1] * ETF_510300_FACTORS[-1]
            / (ETF_510300_CLOSES[0] * ETF_510300_FACTORS[0])
            - 1.0
        )
        self.assertAlmostEqual(result.value, expected, places=12)
        unadjusted = ETF_510300_CLOSES[-1] / ETF_510300_CLOSES[0] - 1.0
        self.assertLess(unadjusted, 0.0, "样本窗口未复权口径应为负（除权跳降）")
        self.assertGreater(result.value, unadjusted, "复权后区间收益应高于未复权比值")
        self.assertFalse(result.warnings, "样本窗口因子全配对，不应有缺因子告警")
        # 指定窗口取最后 N+1 个有效日两端
        win = metrics.etf_adj_return(
            ETF_510300_CLOSES, window=5, adj_factors=ETF_510300_FACTORS
        )
        self.assertAlmostEqual(
            win.value,
            (ETF_510300_CLOSES[-1] * ETF_510300_FACTORS[-1])
            / (ETF_510300_CLOSES[-6] * ETF_510300_FACTORS[-6])
            - 1.0,
            places=12,
        )

    def test_fund_and_cb_insufficient_inputs(self):
        # 基金净值有效日不足：None（换较短窗口），不足 2 日无区间收益定义
        self.assertIsNone(metrics.fund_accum_nav_return([1.0]))
        self.assertIsNone(metrics.fund_accum_nav_return(OF_ACCUM_NAVS, window=99))
        # 转债：空序列空字典；窗口大于有效日时只出 dailyReturn 并告警
        self.assertEqual(metrics.cb_daily_return([]), {})
        short = metrics.cb_daily_return([1.0, 2.0], window=5)
        self.assertEqual(set(short), {"dailyReturn"})
        self.assertTrue(
            any("不足窗口" in w for w in short["dailyReturn"].warnings)
        )

    def test_cb_daily_return_synthetic(self):
        pct = [1.0, 2.0, -0.5, 3.0, None, 2.0]
        out = metrics.cb_daily_return(pct)
        self.assertEqual(set(out), {"dailyReturn", "intervalReturn"})
        self.assertAlmostEqual(out["dailyReturn"].value, 0.02, places=12)
        self.assertAlmostEqual(
            out["intervalReturn"].value,
            1.01 * 1.02 * 0.995 * 1.03 * 1.02 - 1.0,
            places=12,
        )
        win = metrics.cb_daily_return(pct, window=3)
        self.assertAlmostEqual(
            win["intervalReturn"].value, 0.995 * 1.03 * 1.02 - 1.0, places=12
        )
        self.assertTrue(any("已跳过" in w for w in win["dailyReturn"].warnings))


if __name__ == "__main__":
    unittest.main()
