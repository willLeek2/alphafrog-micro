package world.willfrog.alphafrogmicro.domestic.fetch.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 财务指标字段分桶的单测（按【计划】§5 单测上限，本工作包只留这一个）。
 *
 * <p>最要紧的是第一组：⑥ 扣非ROE 与杜邦三组件要用的四个字段必须落在读侧约定的桶里。
 * 写侧分错桶不会编译报错、不会入库报错，只会让 ⑥ 在部署后读到空值——所以在这里钉死。</p>
 */
class FinaIndicatorFieldBucketsTest {

    @Test
    void classifyBuckets() {
        // ⑥ 扣非ROE 与杜邦三组件：知识正文 dupont_roe.md 写明这四个字段名，
        // 读侧从 profitability / capital_cash 两个 JSONB 里取。写错桶 = ⑥ 读不到值。
        assertEquals(FinaIndicatorFieldBuckets.Bucket.PROFITABILITY,
                FinaIndicatorFieldBuckets.classify("roe_dt"), "roe_dt（扣非ROE）应在 profitability");
        assertEquals(FinaIndicatorFieldBuckets.Bucket.PROFITABILITY,
                FinaIndicatorFieldBuckets.classify("netprofit_margin"), "netprofit_margin（销售净利率）应在 profitability");
        assertEquals(FinaIndicatorFieldBuckets.Bucket.CAPITAL_CASH,
                FinaIndicatorFieldBuckets.classify("assets_turn"), "assets_turn（总资产周转率）应在 capital_cash");
        assertEquals(FinaIndicatorFieldBuckets.Bucket.CAPITAL_CASH,
                FinaIndicatorFieldBuckets.classify("assets_to_eqt"), "assets_to_eqt（权益乘数）应在 capital_cash");

        // 022 迁移的四类口径：每股 *ps/eps/bps
        assertEquals(FinaIndicatorFieldBuckets.Bucket.PER_SHARE, FinaIndicatorFieldBuckets.classify("eps"));
        assertEquals(FinaIndicatorFieldBuckets.Bucket.PER_SHARE, FinaIndicatorFieldBuckets.classify("bps"));
        assertEquals(FinaIndicatorFieldBuckets.Bucket.PER_SHARE, FinaIndicatorFieldBuckets.classify("fcff_ps"));
        assertEquals(FinaIndicatorFieldBuckets.Bucket.PER_SHARE, FinaIndicatorFieldBuckets.classify("dt_eps"));

        // 增长：yoy / qoq / 研发。eps_yoy 同时像每股，但按增长走。
        assertEquals(FinaIndicatorFieldBuckets.Bucket.GROWTH, FinaIndicatorFieldBuckets.classify("netprofit_yoy"));
        assertEquals(FinaIndicatorFieldBuckets.Bucket.GROWTH, FinaIndicatorFieldBuckets.classify("q_gr_qoq"));
        assertEquals(FinaIndicatorFieldBuckets.Bucket.GROWTH, FinaIndicatorFieldBuckets.classify("rd_exp"));
        assertEquals(FinaIndicatorFieldBuckets.Bucket.GROWTH, FinaIndicatorFieldBuckets.classify("basic_eps_yoy"));

        // 单季 q_ 前缀按去掉前缀后的字段名归入同一类（022 迁移第 50 行）
        assertEquals(FinaIndicatorFieldBuckets.Bucket.PROFITABILITY, FinaIndicatorFieldBuckets.classify("q_roe"));
        assertEquals(FinaIndicatorFieldBuckets.Bucket.CAPITAL_CASH, FinaIndicatorFieldBuckets.classify("q_ocf_to_or"));
        assertEquals(FinaIndicatorFieldBuckets.Bucket.PER_SHARE, FinaIndicatorFieldBuckets.classify("q_eps"));

        // 三个有独立列的字段不进任何桶
        assertNull(FinaIndicatorFieldBuckets.classify("ts_code"));
        assertNull(FinaIndicatorFieldBuckets.classify("ann_date"));
        assertNull(FinaIndicatorFieldBuckets.classify("end_date"));

        // 对不上四类的一律进 extended（无损，不硬塞）：update_flag 是接口元信息，
        // some_future_field 是接口以后新增的字段——两者都不该被猜进某个语义桶
        assertEquals(FinaIndicatorFieldBuckets.Bucket.EXTENDED, FinaIndicatorFieldBuckets.classify("update_flag"));
        assertEquals(FinaIndicatorFieldBuckets.Bucket.EXTENDED, FinaIndicatorFieldBuckets.classify("some_future_field"));
    }
}
