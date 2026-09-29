"""duckdb_query_runner 本地自测（需要本机装有 duckdb 1.5 线的 Python 环境，如 conda alphafrog）。

运行方式（Maven 不会执行本文件）：
    conda run -n alphafrog python agentToolsShared/src/test/python/sandboxjob/test_duckdb_query_runner.py

覆盖：估计行数解析、引擎锁定、只读白名单、数据集挂载（CSV/Parquet）、文本级禁令
（多语句/ATTACH/字符串字面量里的分号不误伤）、EXPLAIN 准入（笛卡尔积/无界全局排序/
估计行数超档；有界排序与小的混合排序放行）、语句超时中断与信封错误码、日期/小数/
二进制类型的序列化、超大结果的体积守卫、端到端信封。
"""
import base64
import contextlib
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]
RUNNER_SRC = REPO / "agentToolsShared/src/main/resources/sandboxjob/duckdb_query_runner.py"
WORK = Path(tempfile.mkdtemp(prefix="dq-runner-test-"))
MOUNT = WORK / "mount"
MOUNT.mkdir()
(WORK := WORK)

PASS = []
FAIL = []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("PASS " if cond else "FAIL ") + name + (" | " + str(detail)[:200] if detail else ""))


def load_runner_module():
    spec = importlib.util.spec_from_file_location("dq_runner", RUNNER_SRC)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# ---------- 造数据 ----------
import duckdb  # noqa: E402

small_csv = MOUNT / "small.csv"
duckdb.execute(
    f"COPY (SELECT range AS id, range * 2 AS val FROM range(150)) TO '{small_csv}' (HEADER)"
).fetchall()
big_parquet = MOUNT / "big.parquet"
duckdb.execute(
    f"COPY (SELECT range AS id FROM range(300000)) TO '{big_parquet}' (FORMAT PARQUET)"
).fetchall()
typed_csv = MOUNT / "typed.csv"
duckdb.execute(
    f"COPY (SELECT DATE '2026-01-05' AS d, CAST(12.34 AS DECIMAL(10,2)) AS pe,"
    f" TIMESTAMP '2026-01-05 10:30:00' AS ts FROM range(3)) TO '{typed_csv}' (HEADER)"
).fetchall()

runner = load_runner_module()

LIMITS = {"memory_limit": "1GB", "threads": 2, "temp_directory": str(WORK / "tmp"),
          "statement_timeout_s": 30, "row_cap": 100, "estimated_row_cap": 200_000}
LIMITS_BG = {**LIMITS, "statement_timeout_s": 120, "row_cap": 1000, "estimated_row_cap": 600_000}

spec_base = {
    "tier": "INTERACTIVE",
    "sql": "SELECT 1",
    "mount_dir": str(MOUNT),
    "limits": LIMITS,
    "datasets": [],
}
(WORK / "tmp").mkdir()

# ---------- 单元级 ----------
# 估计行数来自 EXPLAIN (FORMAT JSON) 的 extra_info（字符串值；有界/聚合算子不给）
plan_tree_sample = [{"name": "PROJECTION", "extra_info": {"Estimated Cardinality": "20000"},
                     "children": [{"name": "READ_PARQUET",
                                   "extra_info": {"Estimated Cardinality": "1,234,567"}}]}]
_max_est = max((e for e in (runner._node_estimated_rows(n)
                            for r in plan_tree_sample for n in runner._walk(r))
                if e is not None), default=0)
check("estimated-rows parse (json tree)", _max_est == 1234567)
check("estimated-rows missing tolerated",
      runner._node_estimated_rows({"name": "TOP_N", "extra_info": {"Top": "5"}}) is None)

con = runner.configure_engine(spec_base)
locked = False
try:
    con.execute("SET memory_limit='2GB'")
except Exception:
    locked = True
check("lock_configuration blocks post-lock SET", locked)

blocked_outside = False
try:
    con.execute("SELECT * FROM read_csv('/etc/hosts', header=false)").fetchall()
except Exception:
    blocked_outside = True
check("allowed_directories blocks read outside mount", blocked_outside)

blocked_reopen = False
try:
    con.execute("SET allowed_directories=['/']")
except Exception:
    blocked_reopen = True
check("lock blocks widening allowed_directories", blocked_reopen)

spec_two = {**spec_base, "datasets": [
    {"alias": "t1", "path": str(MOUNT / "_run_dataset_1" / "small.csv")},
    {"alias": "t2", "path": str(MOUNT / "_run_dataset_2" / "big.parquet")},
]}
# 按生产路径形态摆数据：mount_dir 下的 _run_dataset_<n>/<filename>
(MOUNT / "_run_dataset_1").mkdir(exist_ok=True)
(MOUNT / "_run_dataset_2").mkdir(exist_ok=True)
import shutil  # noqa: E402
shutil.copy(small_csv, MOUNT / "_run_dataset_1" / "small.csv")
shutil.copy(big_parquet, MOUNT / "_run_dataset_2" / "big.parquet")

con2 = runner.configure_engine(spec_two)
mounted = runner.mount_datasets(con2, spec_two)
n = con2.execute("SELECT count(*) FROM t1").fetchone()[0]
check("mount csv view t1 (external access off + whitelist)", n == 150, f"count={n}")
n2 = con2.execute("SELECT count(*) FROM t2").fetchone()[0]
check("mount parquet view t2 (external access off + whitelist)", n2 == 300000, f"count={n2}")

# 内存外溢：临时目录在挂载目录之外且是 duckdb 自己写文件，不应被 external access 关闭影响。
spill_spec = {**spec_base,
              "limits": {**LIMITS, "memory_limit": "100MB"}}
con_spill = runner.configure_engine(spill_spec)
spill_ok = True
spill_detail = ""
try:
    con_spill.execute(
        f"CREATE VIEW t2 AS SELECT * FROM read_parquet('{big_parquet}')")
    got = con_spill.execute(
        "SELECT count(*) FROM (SELECT id, md5(id::VARCHAR) AS h FROM t2 ORDER BY h LIMIT 1000)"
    ).fetchone()[0]
    spill_detail = f"count={got}"
except Exception as exc:
    spill_ok = False
    spill_detail = str(exc)[:150]
check("temp spill works with external access off", spill_ok and got == 1000, spill_detail)


# ---------- 文本级禁令 ----------
def preflight_expect_reject(sql, expect_fragment):
    try:
        runner.preflight(sql, {"checks": []})
        return False, "no exit"
    except SystemExit:
        return True, expect_fragment


ok, _ = preflight_expect_reject("SELECT 1; SELECT 2", "PLAN_MULTI_STATEMENT")
check("preflight rejects multi-statement", ok)
ok, _ = preflight_expect_reject("ATTACH ':memory:' AS x", "PLAN_FORBIDDEN_STATEMENT")
check("preflight rejects in-memory ATTACH (engine gate misses it)", ok)
ok, _ = preflight_expect_reject("COPY t1 TO 'x.csv'", "PLAN_FORBIDDEN_STATEMENT")
check("preflight rejects COPY", ok)
ok, _ = preflight_expect_reject("SELECT * FROM t1 WHERE note = 'a;b'", "should-pass")
check("preflight passes semicolon inside string literal", not ok)

# ---------- EXPLAIN 准入 ----------
def gate_expect_reject(con, sql, spec, expect_code):
    """断言两件事：拒了，且是用声称的那条规则拒的（抓真实信封核对错误码——
    只断言「拒了」会让坏死规则隐身：规则不触发、拒绝来自别的规则，测试照样绿）。"""
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            runner.gate(con, sql, spec["tier"], spec)
        return False, "no exit"
    except SystemExit:
        lines = [l for l in buf.getvalue().splitlines() if l.startswith(runner.RESULT_MARKER)]
        if not lines:
            return False, "no envelope"
        env = json.loads(lines[-1][len(runner.RESULT_MARKER):])
        actual = env.get("error", {}).get("code")
        return actual == expect_code, f"expect={expect_code} actual={actual}"


ok, detail = gate_expect_reject(con2, "SELECT * FROM t1, t2", spec_two, "PLAN_CROSS_PRODUCT")
check("gate rejects cross product", ok, detail)
ok, detail = gate_expect_reject(con2, "SELECT * FROM t2 ORDER BY id", spec_two, "PLAN_GLOBAL_SORT")
check("gate rejects unbounded global sort (ORDER_BY 无 TOP_N 祖先)", ok, detail)
ok, _report = runner.gate(con2, "SELECT * FROM t1 ORDER BY id LIMIT 5", "INTERACTIVE", spec_two)
check("gate passes ORDER BY + LIMIT (TOP_N 有界重排)", ok)
# 派生表形态：外层 TOP_N 有界 + 内层小排序。内层 ORDER_BY 的祖先链上有 TOP_N
# （内外两层排序共用同一个 TOP_N 祖先判定），小表估计行数在档内，放行。
ok, _report = runner.gate(
    con2,
    "SELECT * FROM (SELECT * FROM t1 WHERE id < 50 ORDER BY id) sub ORDER BY id LIMIT 5",
    "INTERACTIVE", spec_two)
check("gate passes bounded-outer + small inner sort", ok)
# 同一形态压在大表上：排序规则因 TOP_N 祖先放行，拒绝来自估计行数规则（300k>200k）。
ok, detail = gate_expect_reject(
    con2,
    "SELECT * FROM (SELECT * FROM t2 ORDER BY id) sub ORDER BY id LIMIT 5",
    spec_two, "PLAN_ESTIMATED_ROWS_OVER_CAP")
check("gate rejects big inner sort via estimated-rows cap", ok, detail)
# 顶层输出规模规则：无过滤无 LIMIT 的整表直出，顶层算子估计行数超过返回上限即拒
# （比估计行数规则先触发，给出可操作的 PLAN_FULL_SCAN）。
ok, detail = gate_expect_reject(con2, "SELECT * FROM t2", spec_two, "PLAN_FULL_SCAN")
check("gate rejects unbounded dump via top-output rule (300k>100)", ok, detail)
# 顶层白名单：聚合顶层直接放行（count(*) 在 Parquet 上甚至是元数据扫描）。
ok, _report = runner.gate(con2, "SELECT count(*) FROM t2", "INTERACTIVE", spec_two)
check("gate passes count(*) (aggregate top whitelisted)", ok)
# 顶层有界但扫描超估计行数档：BACKGROUND 档上限放宽到 600k 后放行。
spec_two_bg = {**spec_two, "tier": "BACKGROUND", "limits": LIMITS_BG}
ok_bg, report_bg = runner.gate(con2, "SELECT * FROM t2 LIMIT 5", "BACKGROUND", spec_two_bg)
check("gate passes bounded scan at background cap (300k<600k)", ok_bg,
      f"ec={report_bg['estimated_rows_max']}")
# 同一条在 INTERACTIVE 档（200k）拒：估计行数规则对照档位上限。
ok, detail = gate_expect_reject(con2, "SELECT * FROM t2 LIMIT 5", spec_two,
                                "PLAN_ESTIMATED_ROWS_OVER_CAP")
check("gate rejects bounded scan over interactive est-cap (300k>200k)", ok, detail)

# 兼容回落：旧锚点重放的规格没有 estimated_row_cap，按当时固定值判定（INTERACTIVE 20 万）。
# 用有界顶层的查询才能走到估计行数规则（无界直出会先被顶层规模规则拦下）。
legacy_limits = {k: v for k, v in LIMITS.items() if k != "estimated_row_cap"}
ok, detail = gate_expect_reject(
    con2, "SELECT * FROM t2 LIMIT 5", {**spec_two, "limits": legacy_limits},
    "PLAN_ESTIMATED_ROWS_OVER_CAP")
check("gate falls back to legacy cap when estimated_row_cap absent (300k>200k)", ok, detail)

# ---------- 固定用例集（错拒率统计：每次改动必跑，验收以此为准） ----------
# 标签按最终口径：结构规则专用码优先（笛卡尔积、无界排序），规模规则在后
# （顶层输出规模 PLAN_FULL_SCAN -> 各算子估计行数对照档位上限）。
# 合格线是改动期防退化线：错拒率（标注口径 = 应放被拒/应放总数）不得高于基线 0/12，
# 漏放（应拒被放）必须为 0。基线数字见 C4 验收说明。
mid_csv = MOUNT / "mid.csv"
duckdb.execute(
    f"COPY (SELECT range AS id, range * 2 AS val FROM range(5000)) TO '{mid_csv}' (HEADER)")
CASE_SPEC = {**spec_base, "datasets": [
    {"alias": "t1", "path": str(MOUNT / "small.csv"), "format": "csv"},
    {"alias": "t2", "path": str(MOUNT / "big.parquet"), "format": "parquet"},
    {"alias": "t3", "path": str(MOUNT / "mid.csv"), "format": "csv"},
]}
# (名称, sql, INTERACTIVE/BACKGROUND, 应判, 应拒时的错误码, 在测哪条规则)
CASE_SET = [
    ("csv_filtered_small", "SELECT * FROM t1 WHERE id < 10", "I", "放", None, "顶层 FILTER 估计 ≤ row_cap"),
    ("csv_limit_preview", "SELECT * FROM t1 LIMIT 100", "I", "放", None, "顶层 STREAMING_LIMIT 白名单"),
    ("parquet_count", "SELECT count(*) FROM t2", "I", "放", None, "聚合顶层白名单（Parquet 元数据扫描）"),
    ("csv_count", "SELECT count(*) FROM t1", "I", "放", None, "聚合顶层白名单（CSV 真实全扫但顶层收敛）"),
    ("big_limit5_bg", "SELECT * FROM t2 LIMIT 5", "B", "放", None, "顶层有界 + 估计 300k ≤ 后台档 600k"),
    ("topn_bounded", "SELECT * FROM t1 ORDER BY id LIMIT 5", "I", "放", None, "ORDER_BY 有 TOP_N 祖先"),
    ("window_bounded", "SELECT id, row_number() OVER (ORDER BY id) rn FROM t1 LIMIT 50", "I", "放", None, "顶层 STREAMING_LIMIT 白名单"),
    ("distinct_small", "SELECT DISTINCT val % 5 FROM t1", "I", "放", None, "顶层 HASH_GROUP_BY 白名单"),
    ("group_by_small", "SELECT id % 7 g, count(*) FROM t1 GROUP BY id % 7", "I", "放", None, "顶层 HASH_GROUP_BY 白名单"),
    ("literal_no_false_positive", "SELECT ';' AS s, 'ATTACH' AS k FROM t1 LIMIT 1", "I", "放", None, "文本禁令不误伤字面量"),
    ("recursive_cte", "WITH RECURSIVE r(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM r WHERE x < 500) SELECT count(*) FROM r", "I", "放", None, "聚合顶层；慢归超时管不归准入管"),
    ("derived_small_inner_sort", "SELECT * FROM (SELECT * FROM t1 WHERE id < 50 ORDER BY id) sub ORDER BY id LIMIT 5", "I", "放", None, "TOP_N 祖先 + 小内层"),
    ("big_unfiltered_dump", "SELECT * FROM t2", "I", "拒", "PLAN_FULL_SCAN", "顶层 READ_PARQUET 估计 300k > 100"),
    ("parquet_filtered_big", "SELECT * FROM t2 WHERE id < 100", "I", "拒", "PLAN_FULL_SCAN", "顶层估计 60k > 100（估计偏高的错拒，验收量化）"),
    ("csv_unbounded_dump", "SELECT * FROM t1", "I", "拒", "PLAN_FULL_SCAN", "顶层估计 ~103 > 100"),
    ("window_unbounded", "SELECT id, row_number() OVER (ORDER BY id) rn FROM t1", "I", "拒", "PLAN_FULL_SCAN", "顶层 PROJECTION 估计 ~104 > 100"),
    ("mid_unbounded_dump", "SELECT * FROM t3", "I", "拒", "PLAN_FULL_SCAN", "顶层估计 ~5k > 100（估计行数规则不拒，顶层规则独立价值）"),
    ("unbounded_sort", "SELECT * FROM t2 ORDER BY id", "I", "拒", "PLAN_GLOBAL_SORT", "ORDER_BY 无 TOP_N 祖先（结构先于规模）"),
    ("hidden_big_inner_sort", "SELECT * FROM (SELECT * FROM t2 ORDER BY id) sub ORDER BY id LIMIT 5", "I", "拒", "PLAN_ESTIMATED_ROWS_OVER_CAP", "TOP_N 祖先放行排序规则；扫描 300k 超 200k"),
    ("big_limit5", "SELECT * FROM t2 LIMIT 5", "I", "拒", "PLAN_ESTIMATED_ROWS_OVER_CAP", "顶层有界放行；扫描框估计 300k > 200k"),
    ("cross_product", "SELECT * FROM t1, t1 b", "I", "拒", "PLAN_CROSS_PRODUCT", "结构规则"),
    ("multi_statement", "SELECT 1; SELECT 2", "I", "拒", "PLAN_MULTI_STATEMENT", "文本禁令"),
    ("attach", "ATTACH ':memory:' AS x", "I", "拒", "PLAN_FORBIDDEN_STATEMENT", "文本禁令（引擎闸漏内存 ATTACH）"),
    ("copy", "COPY t1 TO 'x.csv'", "I", "拒", "PLAN_FORBIDDEN_STATEMENT", "文本禁令"),
]
case_con = runner.configure_engine(CASE_SPEC)
runner.mount_datasets(case_con, CASE_SPEC)
case_false_reject = case_false_allow = 0
for name, sql, tier, expect, expect_code, rule in CASE_SET:
    case_spec = {**CASE_SPEC, "tier": "BACKGROUND" if tier == "B" else "INTERACTIVE",
                 "limits": LIMITS_BG if tier == "B" else LIMITS}
    if expect == "放":
        try:
            case_ok, _ = runner.gate(case_con, sql, case_spec["tier"], case_spec)
        except SystemExit:
            case_ok = False
        check(f"case {name} ({rule})", case_ok, "应放被拒")
        case_false_reject += 0 if case_ok else 1
    else:
        case_ok, detail = gate_expect_reject(case_con, sql, case_spec, expect_code)
        check(f"case {name} ({rule})", case_ok, detail)
        case_false_allow += 0 if case_ok else 1
check("case-set false-reject rate at baseline (0/12)", case_false_reject == 0,
      f"false_reject={case_false_reject}")
check("case-set false-allow is zero", case_false_allow == 0, f"false_allow={case_false_allow}")
case_con.close()

# ---------- 语句超时与执行错误 ----------
con3 = duckdb.connect(database=":memory:")
status, _, _ = runner.execute_with_timeout(
    con3, "SELECT count(*) FROM range(100000000) a, range(100000000) b", 2)
check("statement timeout interrupts slow query", status == "TIMEOUT", status)

con4 = duckdb.connect(database=":memory:")
status, _, err = runner.execute_with_timeout(con4, "SELECT CAST('abc' AS INTEGER)", 10)
check("execution error surfaces", status == "ERROR" and err is not None, status)


# ---------- 端到端（占位符替换后子进程跑真实文件） ----------
def run_e2e(name, spec, expect_status, extra_check=None):
    src = RUNNER_SRC.read_text()
    b64 = base64.b64encode(json.dumps(spec).encode()).decode()
    injected = WORK / f"runner_{name}.py"
    injected.write_text(src.replace('"__SPEC_B64_PLACEHOLDER__"', f'"{b64}"'))
    proc = subprocess.run([sys.executable, str(injected)], capture_output=True, text=True, timeout=180)
    lines = [l for l in proc.stdout.splitlines() if l.startswith(runner.RESULT_MARKER)]
    if not lines:
        check(f"e2e {name}", False, f"no envelope; rc={proc.returncode} stderr={proc.stderr[-300:]}")
        return None
    env = json.loads(lines[-1][len(runner.RESULT_MARKER):])
    ok = env.get("status") == expect_status
    detail = f"status={env.get('status')}"
    if ok and extra_check:
        ok, detail = extra_check(env)
    check(f"e2e {name}", ok, detail)
    return env


ds_t1 = [{"alias": "t1", "path": str(MOUNT / "_run_dataset_1" / "small.csv"), "format": "csv"}]
ds_typed = [{"alias": "t3", "path": str(MOUNT / "_run_dataset_3" / "typed.csv"), "format": "csv"}]
(MOUNT / "_run_dataset_3").mkdir(exist_ok=True)
shutil.copy(typed_csv, MOUNT / "_run_dataset_3" / "typed.csv")

run_e2e("happy", {
    **spec_base,
    "sql": "SELECT id, val FROM t1 WHERE id < 5 ORDER BY id LIMIT 5",
    "datasets": ds_t1,
}, "SUCCEEDED", lambda e: (e["row_count"] == 5 and e["rows"][0] == [0, 0] and not e["truncated"],
                           f"rows={e['rows'][:2]}"))

run_e2e("truncate", {
    **spec_base,
    "sql": "SELECT * FROM t1 ORDER BY id LIMIT 200",
    "datasets": ds_t1,
}, "SUCCEEDED", lambda e: (e["truncated"] and e["row_count"] == 100,
                           f"row_count={e['row_count']} truncated={e['truncated']}"))

env = run_e2e("typed_columns", {
    **spec_base,
    "sql": "SELECT d, pe, ts FROM t3 ORDER BY d LIMIT 3",
    "datasets": ds_typed,
}, "SUCCEEDED")
check("date/decimal/timestamp serialize without crash",
      env is not None and env["rows"][0][0] == "2026-01-05"
      and abs(env["rows"][0][1] - 12.34) < 1e-9
      and env["rows"][0][2].startswith("2026-01-05T10:30"),
      f"row={env['rows'][0] if env else None}")

run_e2e("reject_cross", {
    **spec_base,
    "sql": "SELECT * FROM t1, t1 b",
    "datasets": ds_t1,
}, "PLAN_REJECTED", lambda e: (e["error"]["code"] == "PLAN_CROSS_PRODUCT", e["error"]["code"]))

run_e2e("reject_multi_statement", {
    **spec_base,
    "sql": "SELECT * FROM t1; SELECT 2",
    "datasets": ds_t1,
}, "PLAN_REJECTED", lambda e: (e["error"]["code"] == "PLAN_MULTI_STATEMENT", e["error"]["code"]))

run_e2e("reject_attach", {
    **spec_base,
    "sql": "ATTACH ':memory:' AS x",
    "datasets": ds_t1,
}, "PLAN_REJECTED", lambda e: (e["error"]["code"] == "PLAN_FORBIDDEN_STATEMENT", e["error"]["code"]))

run_e2e("bad_sql", {
    **spec_base,
    "sql": "SELECT FROM WHERE",
    "datasets": [],
}, "FAILED", lambda e: (e.get("stage") == "gate" and e["error"]["code"] == "EXPLAIN_FAILED",
                        f"stage={e.get('stage')} code={e['error']['code']}"))

run_e2e("unknown_tier", {**spec_base, "tier": "TURBO", "datasets": []}, "FAILED",
        lambda e: (e["error"]["code"] == "UNKNOWN_TIER", e["error"]["code"]))

# 语句超时信封必须带独立错误码（Java 侧据此映射，而不是笼统的 QUERY_EXECUTION_FAILED）。
# 慢查询用递归 CTE：计划无笛卡尔积/无排序、估计行数远低于档位上限，能过准入但跑不完。
run_e2e("timeout_envelope", {
    **spec_base,
    "sql": "WITH RECURSIVE r(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM r WHERE x < 50000000) "
           "SELECT count(*) FROM r",
    "limits": {**LIMITS, "statement_timeout_s": 2},
    "datasets": [],
}, "STATEMENT_TIMEOUT", lambda e: (e["error"]["code"] == "STATEMENT_TIMEOUT"
                                   and e["limits"]["statement_timeout_s"] == 2,
                                   f"error={e.get('error')}"))

# 体积守卫：结果集序列化后超过安全阈值时改发 QUERY_RESULT_TOO_LARGE，不给半截 JSON
run_e2e("oversize_guard", {
    **spec_base,
    "sql": "SELECT range AS id, repeat(md5(range::VARCHAR), 40) AS pad FROM range(1000)",
    "limits": {**LIMITS, "row_cap": 1000},
    "datasets": [],
}, "FAILED", lambda e: (e.get("stage") == "serialize"
                        and e["error"]["code"] == "QUERY_RESULT_TOO_LARGE",
                        f"stage={e.get('stage')} code={e.get('error', {}).get('code')}"))

print(f"\n== {len(PASS)} passed, {len(FAIL)} failed ==")
if FAIL:
    print("FAILED:", FAIL)
    sys.exit(1)
