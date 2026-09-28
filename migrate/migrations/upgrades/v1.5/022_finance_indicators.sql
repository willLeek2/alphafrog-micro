-- 预设金融指标库：库结构变更（一个文件包含全部结构变更）。
--
-- 依据文档：《01304-阶段一：指标清单与数据库结构》《01304 指标库扩充·开发计划》§4。
-- 内容：股票日线表向后追加三列（复权因子、估值、股本换手）；新建财务指标、转债日线、AH 比价三张表。
--
-- 写法说明：
-- 1. 全部语句幂等（IF NOT EXISTS），重复执行不报错、不产生重复结构（仓库迁移设计要求，
--    先例 v0.4/004、v1.1/001）。
-- 2. 日线表加列放在文件末尾：执行器 migrate.py 把整个文件作为单个事务执行（一次
--    cur.execute + commit），ALTER 取得 alphafrog_stock_daily 的排它锁后要持有到事务提交；
--    放在末尾使锁持有窗口收敛到事务收尾，是「加列单独短事务」在单事务执行器下的等价实现。
--    SET LOCAL 限当前事务；锁等待超时 5 秒，超时整个事务回滚
--    （新建表一并回滚，不留半套结构），由人工重新执行。取值沿用仓库先例
--    JdbcToolJobTestStore.configureTransaction。
-- 3. 验收直跑（绕开记账）时用 psql -1 -f 单事务模式执行，保证 SET LOCAL 生效；
--    migrate.py 正常路径本身就是单事务。
-- 4. 新表只建文档写明的唯一键，不额外建单列索引（v1.0 ETF 表有 idx_ts_code/idx_trade_date
--    先例，但阶段一未对新表提索引要求，第一轮不给 JSON 键建索引；需要时另走变更）。
-- 5. 新表不挂 updated_at 触发器（v0.5 有、v1.0 起的表没有）：更新时间由写入方
--    ON CONFLICT DO UPDATE 的 SET 子句维护。
-- 6. 交易日/报告期存储与现有日线表相同：接口 YYYYMMDD，库里存毫秒时间戳（BIGINT）。

-- 1. 财务指标表（TuShare fina_indicator_vip）。同一报告期后到的公告覆盖前值（唯一键
--    ts_code + end_date，写入方 ON CONFLICT DO UPDATE）。接口字段按四类收进 JSONB 列，
--    对不上四类的键进 extended，键名沿用 TuShare 字段名，写入方式与利润表相同。
CREATE TABLE IF NOT EXISTS alphafrog_stock_fina_indicator (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    ann_date BIGINT,
    end_date BIGINT NOT NULL,
    profitability JSONB,
    per_share JSONB,
    capital_cash JSONB,
    growth JSONB,
    extended JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, end_date)
);

COMMENT ON TABLE alphafrog_stock_fina_indicator IS
    '股票财务指标（TuShare fina_indicator_vip，按报告期）：常用字段分四类 JSONB，其余进 extended；同一报告期后到的公告覆盖前值。';
COMMENT ON COLUMN alphafrog_stock_fina_indicator.profitability IS
    '盈利与回报：roe*/roa*/roic*、利润率、扣非相关（第一轮用 roe_dt、netprofit_margin）。';
COMMENT ON COLUMN alphafrog_stock_fina_indicator.per_share IS
    '每股：*ps、eps、bps。';
COMMENT ON COLUMN alphafrog_stock_fina_indicator.capital_cash IS
    '营运、偿债与现金流、资本结构（第一轮杜邦用 assets_turn、assets_to_eqt）。';
COMMENT ON COLUMN alphafrog_stock_fina_indicator.growth IS
    '增长：yoy、qoq、研发。单季 q_ 前缀字段按所属类别归入同类 JSON。';
COMMENT ON COLUMN alphafrog_stock_fina_indicator.extended IS
    '未进四类的字段与接口以后新增的字段，键值原样保存（列名与利润表 extended 相同）。';

-- 2. 转债日线表（TuShare cb_daily）。行情与涨跌幅用普通列，溢价四个字段进 premium JSONB。
CREATE TABLE IF NOT EXISTS alphafrog_cb_daily (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    trade_date BIGINT NOT NULL,
    pre_close DOUBLE PRECISION,
    open DOUBLE PRECISION,
    high DOUBLE PRECISION,
    low DOUBLE PRECISION,
    close DOUBLE PRECISION,
    change DOUBLE PRECISION,
    pct_chg DOUBLE PRECISION,
    vol DOUBLE PRECISION,
    amount DOUBLE PRECISION,
    premium JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, trade_date)
);

COMMENT ON TABLE alphafrog_cb_daily IS
    '可转债日线（TuShare cb_daily，按交易日）：行情涨跌幅用普通列，溢价进 premium JSONB。';
COMMENT ON COLUMN alphafrog_cb_daily.premium IS
    '键：bond_value、bond_over_rate、cb_value、cb_over_rate（纯债溢价率与转股溢价率，接口给出的百分比）。';

-- 3. AH 比价表（TuShare stk_ah_comparison）。字段少，全部普通列。
CREATE TABLE IF NOT EXISTS alphafrog_stk_ah (
    id BIGSERIAL PRIMARY KEY,
    ts_code VARCHAR(32) NOT NULL,
    hk_code VARCHAR(32),
    trade_date BIGINT NOT NULL,
    close DOUBLE PRECISION,
    hk_close DOUBLE PRECISION,
    pct_chg DOUBLE PRECISION,
    hk_pct_chg DOUBLE PRECISION,
    ah_comparison DOUBLE PRECISION,
    ah_premium DOUBLE PRECISION,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (ts_code, trade_date)
);

COMMENT ON TABLE alphafrog_stk_ah IS
    'AH 比价（TuShare stk_ah_comparison，按交易日）：A/H 两地收盘、涨跌幅、比价与溢价，全部普通列。';
COMMENT ON COLUMN alphafrog_stk_ah.ah_premium IS
    '溢价（A/H）%，接口给出的百分比，第一轮直接使用。';

-- 4. 股票日线表加三列（放在文件末尾，见头部写法说明 2）。新列向后追加、不带默认值、
--    不重写既有行；早于加列日期的历史行这些列为空，由回填任务按交易日补齐。
--    每日指标接口返回的 close 不入库（收盘价用已有 close 列）。
SET LOCAL lock_timeout = '5s';

ALTER TABLE alphafrog_stock_daily
    ADD COLUMN IF NOT EXISTS adj_factor DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS valuation JSONB,
    ADD COLUMN IF NOT EXISTS share_turnover JSONB;

COMMENT ON COLUMN alphafrog_stock_daily.adj_factor IS
    '复权因子（TuShare adj_factor）。接口只返回这一个指标字段，按列保存。';
COMMENT ON COLUMN alphafrog_stock_daily.valuation IS
    '估值：键 pe、pe_ttm、pb、ps、ps_ttm、dv_ratio、dv_ttm。亏损市盈率为空。';
COMMENT ON COLUMN alphafrog_stock_daily.share_turnover IS
    '股本与成交：键 turnover_rate、turnover_rate_f、volume_ratio、total_share、float_share、free_share、total_mv、circ_mv、limit_status。';
