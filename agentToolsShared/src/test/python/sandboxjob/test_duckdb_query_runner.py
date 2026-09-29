"""duckdb_query_runner 本地自测（需要本机装有 duckdb 1.5 线的 Python 环境，如 conda alphafrog）。

运行方式（Maven 不会执行本文件）：
    conda run -n alphafrog python agentToolsShared/src/test/python/sandboxjob/test_duckdb_query_runner.py

覆盖：估计行数解析、引擎锁定、只读白名单、数据集挂载（CSV/Parquet）、文本级禁令
（多语句/ATTACH/字符串字面量里的分号不误伤）、EXPLAIN 准入（笛卡尔积/无界全局排序/
估计行数超档；有界排序与小的混合排序放行）、语句超时中断与信封错误码、日期/小数/
二进制类型的序列化、超大结果的体积守卫、端到端信封。
"""
import base64
import importlib.util
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
plan_sample = "│        ~20,000 rows       │\n│    ~1,234,567 rows    │"
check("estimated-rows parse", runner._max_estimated_rows(plan_sample) == 1234567)
check("estimated-rows empty", runner._max_estimated_rows("no markers") == 0)

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
    try:
        runner.gate(con, sql, spec["tier"], spec)
        return False, "no exit"
    except SystemExit:
        return True, expect_code  # 信封内容在端到端用例里核对


ok, detail = gate_expect_reject(con2, "SELECT * FROM t1, t2", spec_two, "PLAN_CROSS_PRODUCT")
check("gate rejects cross product", ok, detail)
ok, detail = gate_expect_reject(con2, "SELECT * FROM t2 ORDER BY id", spec_two, "PLAN_GLOBAL_SORT")
check("gate rejects unbounded global sort on big table (300k>100 row_cap)", ok, detail)
ok, _report = runner.gate(con2, "SELECT * FROM t1 ORDER BY id LIMIT 5", "INTERACTIVE", spec_two)
check("gate passes ORDER BY + LIMIT (TOP_N 有界重排估计极小)", ok)
# 派生表形态（避免标量子查询被 DuckDB 1.5 展开成 CROSS_PRODUCT 干扰本检查）：
# 外层 TOP_N 有界 + 内层无界排序。基数小（过滤后约 50 行 ≤ row_cap 100）放行——
# TOP_N 的存在不再整体豁免排序检查，按每个 ORDER_BY 算子框自身的估计行数判定。
ok, _report = runner.gate(
    con2,
    "SELECT * FROM (SELECT * FROM t1 WHERE id < 50 ORDER BY id) sub ORDER BY id LIMIT 5",
    "INTERACTIVE", spec_two)
check("gate passes bounded-outer + small inner sort", ok)
# 同一形态但内层无界排序压在大表上（约 30 万行 > row_cap 100）：拒。
ok, detail = gate_expect_reject(
    con2,
    "SELECT * FROM (SELECT * FROM t2 ORDER BY id) sub ORDER BY id LIMIT 5",
    spec_two, "PLAN_GLOBAL_SORT")
check("gate rejects bounded-outer hiding big inner sort (TOP_N 不再整体豁免)", ok, detail)
ok, detail = gate_expect_reject(con2, "SELECT * FROM t2", spec_two, "PLAN_ESTIMATED_ROWS_OVER_CAP")
check("gate rejects EC over interactive cap (300k>200k)", ok, detail)
spec_two_bg = {**spec_two, "tier": "BACKGROUND", "limits": LIMITS_BG}
ok_bg, report_bg = runner.gate(con2, "SELECT * FROM t2", "BACKGROUND", spec_two_bg)
check("gate passes same scan at background cap (300k<600k)", ok_bg,
      f"ec={report_bg['estimated_rows_max']}")

# 兼容回落：旧锚点重放的规格没有 estimated_row_cap，按当时固定值判定（INTERACTIVE 20 万）。
legacy_limits = {k: v for k, v in LIMITS.items() if k != "estimated_row_cap"}
ok, detail = gate_expect_reject(
    con2, "SELECT * FROM t2", {**spec_two, "limits": legacy_limits},
    "PLAN_ESTIMATED_ROWS_OVER_CAP")
check("gate falls back to legacy cap when estimated_row_cap absent (300k>200k)", ok, detail)

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
