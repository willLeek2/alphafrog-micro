# === work-package-01304：预设金融指标库——估值/预测/溢价契约测试 ===
"""15 项新方法中「标量与行列表输入」一族的契约测试：输出键集合、每条
parameters 的 output 键、缺值输出的省略（禁 NaN）、null 不当 0、错误路径。

估值（pe/pb/dv/换手）与财务（roe/杜邦）、券商预测（report_rc）的生产数据尚未
入库，真实样本核对在这些数据灌库后补充；本文件先用合成数据钉住契约语义。
序列输入一族（动量/均线/区间收益/回撤）的真实样本在
test_metrics_preset_series.py。
"""
import unittest

from alphafrog_finance import metrics


class ValuationAndForecastContractTests(unittest.TestCase):
    def test_rolling_pe_full_and_loss_making(self):
        full = metrics.rolling_pe([10.0, 12.0, 8.0, None, 11.0], window=4)
        self.assertEqual(set(full), {"peTtm", "ep", "pePercentile"})
        self.assertEqual(full["peTtm"].value, 11.0)
        self.assertEqual(full["ep"].value, 1.0 / 11.0)
        # 窗口取最后 4 个有值日 [10, 12, 8, 11] 中不高于 11 的：10、8、11 → 3/4
        self.assertAlmostEqual(full["pePercentile"].value, 0.75)
        self.assertEqual(full["ep"].parameters["output"], "ep")

        loss = metrics.rolling_pe([10.0, -5.0])
        self.assertEqual(set(loss), {"peTtm"})
        self.assertTrue(any("未输出" in w for w in loss["peTtm"].warnings))
        self.assertEqual(metrics.rolling_pe([None, None]), {})

    def test_scalar_valuation_direct_use_and_null(self):
        self.assertEqual(metrics.price_to_book(1.5).value, 1.5)
        self.assertIsNone(metrics.price_to_book(None))
        self.assertEqual(metrics.dividend_yield(2.5).value, 2.5)
        self.assertIsNone(metrics.dividend_yield(None))
        self.assertEqual(metrics.free_float_turnover(1.2).value, 1.2)
        self.assertIsNone(metrics.free_float_turnover(None))
        self.assertEqual(metrics.ah_premium(31.4).value, 31.4)
        self.assertIsNone(metrics.ah_premium(None))
        # 负值不合法：市净率非正直接拒绝
        with self.assertRaises(ValueError):
            metrics.price_to_book(-1.0)

    def test_index_pe_pb_partial_outputs(self):
        both = metrics.index_pe_pb(14.2, 1.6)
        self.assertEqual(set(both), {"peTtm", "pb"})
        only_pe = metrics.index_pe_pb(14.2, None)
        self.assertEqual(set(only_pe), {"peTtm"})
        self.assertEqual(only_pe["peTtm"].parameters["output"], "peTtm")
        self.assertEqual(metrics.index_pe_pb(None, None), {})

    def test_dupont_consistency_check(self):
        out = metrics.dupont_roe(12.0, 15.0, 0.8, 1.0)
        self.assertEqual(
            set(out),
            {"roeDt", "netprofitMargin", "assetsTurn", "assetsToEqt", "dupontApprox"},
        )
        self.assertAlmostEqual(out["dupontApprox"].value, 12.0)
        self.assertTrue(out["roeDt"].checks["dupontConsistent"])
        partial = metrics.dupont_roe(12.0, None, 0.8, None)
        self.assertEqual(set(partial), {"roeDt", "assetsTurn"})
        self.assertEqual(metrics.dupont_roe(None, None, None, None), {})

    def test_forward_pe_peg_pairing_and_median(self):
        rows = [
            {"org_name": "甲", "quarter": "2025Q4", "pe": 18.0, "eps": 1.0},
            {"org_name": "甲", "quarter": "2026Q1", "pe": 20.0, "eps": 1.25},  # g=25% → PEG=0.8
            {"org_name": "乙", "quarter": "2025Q4", "pe": 9.0, "eps": 2.0},
            {"org_name": "乙", "quarter": "2026Q1", "pe": 15.0, "eps": 2.2},   # g=10% → PEG=1.5
            {"org_name": "丙", "quarter": "2026Q1", "pe": 22.0, "eps": None},  # 单期/eps 缺 → 只进 forwardPe
        ]
        out = metrics.forward_pe_peg(rows)
        self.assertEqual(set(out), {"forwardPe", "peg"})
        self.assertEqual(out["forwardPe"].value, 20.0)  # median(20, 15, 22)
        self.assertAlmostEqual(out["peg"].value, 1.15)  # median(0.8, 1.5)
        self.assertEqual(out["forwardPe"].parameters["output"], "forwardPe")
        self.assertEqual(out["forwardPe"].parameters["rowCount"], 5)
        self.assertEqual(out["forwardPe"].parameters["orgCount"], 3)

        single = metrics.forward_pe_peg(
            [{"org_name": "甲", "quarter": "2026Q1", "pe": 12.0, "eps": 1.0}]
        )
        self.assertEqual(set(single), {"forwardPe"})
        self.assertTrue(any("PEG 未输出" in w for w in single["forwardPe"].warnings))
        # 全部无有效 pe → 空字典（null 不当 0）
        self.assertEqual(
            metrics.forward_pe_peg(
                [{"org_name": "甲", "quarter": "2026Q1", "pe": None, "eps": 1.0}]
            ),
            {},
        )
        # 增速为负 → 不产出 PEG
        neg = metrics.forward_pe_peg(
            [
                {"org_name": "甲", "quarter": "2025Q4", "pe": 10.0, "eps": 2.0},
                {"org_name": "甲", "quarter": "2026Q1", "pe": 10.0, "eps": 1.0},
            ]
        )
        self.assertEqual(set(neg), {"forwardPe"})

    def test_forward_pe_peg_input_validation(self):
        with self.assertRaises(ValueError):
            metrics.forward_pe_peg("not-a-list")
        with self.assertRaises(ValueError):
            metrics.forward_pe_peg([{"org_name": "", "quarter": "2026Q1"}])
        with self.assertRaises(ValueError):
            metrics.forward_pe_peg([{"org_name": "甲", "quarter": "2026Q1", "pe": "x"}])

    def test_cb_premium_partial(self):
        both = metrics.cb_premium(12.5, 8.0)
        self.assertEqual(set(both), {"cbOverRate", "bondOverRate"})
        self.assertEqual(both["bondOverRate"].value, 8.0)
        self.assertEqual(set(metrics.cb_premium(12.5, None)), {"cbOverRate"})
        self.assertEqual(metrics.cb_premium(None, None), {})


class IdentityTriplesTests(unittest.TestCase):
    """15 项新方法的身份三元组来自生成绑定：methodId 与函数一一对应。"""

    def test_method_ids_resolve(self):
        pairs = {
            "rolling_pe": "finance.valuation.rolling_pe",
            "price_to_book": "finance.valuation.price_to_book",
            "index_pe_pb": "finance.valuation.index_pe_pb",
            "dividend_yield": "finance.valuation.dividend_yield",
            "free_float_turnover": "finance.valuation.free_float_turnover",
            "dupont_roe": "finance.quality.dupont_roe",
            "forward_pe_peg": "finance.forecast.forward_pe_peg",
            "price_momentum": "finance.momentum.price_momentum",
            "etf_adj_return": "finance.return.etf_adj_return",
            "fund_accum_nav_return": "finance.return.fund_accum_nav_return",
            "cb_daily_return": "finance.return.cb_daily_return",
            "cb_premium": "finance.valuation.cb_premium",
            "drawdown_sortino_calmar": "finance.risk.drawdown_sortino_calmar",
            "moving_average": "finance.trend.moving_average",
            "ah_premium": "finance.crossover.ah_premium",
        }
        from alphafrog_finance import bindings

        for function, method_id in pairs.items():
            self.assertEqual(bindings.method_id_for_function(function), method_id)


if __name__ == "__main__":
    unittest.main()
