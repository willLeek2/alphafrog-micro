# === work-package-01304：预设金融指标库——序列方法真实样本测试 ===
"""序列输入一族（动量/均线/区间收益/回撤/转债）的真实数据样本核对。

真实样本取自生产库 2026-09-29 快照：
* 上证指数 000001.SH 2026-05-18 ~ 2026-08-28 共 70 个交易日收盘（alphafrog_index_daily）；
* 华夏成长混合 000001.OF 2026-08-17 ~ 2026-08-28 共 10 个净值日累计净值（alphafrog_fund_nav）。
期望值在测试内以独立算式直接表达（不调用被测函数）。
ETF 与可转债当前无真实数据（ETF 复权因子链路、转债表均待抓取任务灌数），
这两项与复权缺值/无行情分开计数用合成数据覆盖契约语义。
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
        with self.assertRaises(ValueError):
            metrics.price_momentum([1.0, 2.0], window=63)

    def test_moving_average_real_sh_index_50d(self):
        out = metrics.moving_average(SH_CLOSES, window=50)
        expected_sma = sum(SH_CLOSES[:50]) / 50.0
        alpha = 2.0 / (50 + 1)
        expected_ema = expected_sma
        for price in SH_CLOSES[50:]:
            expected_ema = alpha * price + (1.0 - alpha) * expected_ema
        self.assertAlmostEqual(out["sma"].value, expected_sma, places=8)
        self.assertAlmostEqual(out["ema"].value, expected_ema, places=8)
        self.assertEqual(out["sma"].unit, "price")
        self.assertEqual(out["ema"].parameters["output"], "ema")

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
