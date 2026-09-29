"""executeQuery 固定运行器：模型只提供 SQL，本程序由平台维护、随调用一并下发。

流程：锁定引擎配置 -> 挂载授权数据集为视图 -> EXPLAIN 三项准入检查 -> 执行（语句超时可中断）。
输出：stdout 最后一行打印 `__EXECUTE_QUERY_RESULT__` 前缀的 JSON 信封，Java 侧只认这一行。
"""
import base64
import json
import sys
import threading

import duckdb

RESULT_MARKER = "__EXECUTE_QUERY_RESULT__"

TIER_LIMITS = {
    # 语句超时（秒）与返回行数上限按产品档位固定，模型不能自定义。
    "INTERACTIVE": {"statement_timeout_s": 30, "row_cap": 100},
    "BACKGROUND": {"statement_timeout_s": 120, "row_cap": 1000},
}

# 估计行数档位上限：标准档 20 万行，HEAVY（BACKGROUND 一律按它申请）60 万行硬上限。
TIER_ESTIMATED_ROW_CAP = {
    "INTERACTIVE": 200_000,
    "BACKGROUND": 600_000,
}


def emit(envelope):
    print(RESULT_MARKER + json.dumps(envelope, ensure_ascii=False))


def fail(stage, code, message, **extra):
    envelope = {"status": "FAILED", "stage": stage, "error": {"code": code, "message": message}}
    envelope.update(extra)
    emit(envelope)
    sys.exit(0)  # 运行器正常退出；失败语义全在信封里，退出码留给真正的运行器崩溃。


def plan_reject(reason_code, message, gate_report):
    emit({
        "status": "PLAN_REJECTED",
        "error": {"code": reason_code, "message": message},
        "gate": gate_report,
    })
    sys.exit(0)


def parse_spec():
    # 规格由 Java 侧 base64 内嵌在本文件末尾占位处，避免引号转义问题。
    raw = base64.b64decode(SPEC_B64).decode("utf-8")
    return json.loads(raw)


def configure_engine(spec):
    """资源上限先设后锁；只读授权目录；锁死之后查询里的 SET/PRAGMA 改不回来。"""
    # 顺序敏感，依据 duckdb 1.5 的实际约束：
    # 1) allowed_directories 只能在数据库启动后改（connect config 阶段会被拒）；
    # 2) enable_external_access 在运行中只允许从 true 收紧为 false，收紧之后
    #    temp_directory / allowed_directories 立即不可再改。
    # 所以先设临时目录与白名单，再关 external access（read_csv/COPY/INSTALL/LOAD/
    # ATTACH 文件全部失效，白名单把授权挂载目录单独加回——duckdb 文档的只读数据
    # 目录模式），最后设资源上限并整体锁定。
    con = duckdb.connect(database=":memory:")
    limits = spec["limits"]
    con.execute(f"SET temp_directory='{limits['temp_directory']}'")
    con.execute(f"SET allowed_directories=['{spec['mount_dir']}']")
    con.execute("SET enable_external_access=false")
    con.execute(f"SET memory_limit='{limits['memory_limit']}'")
    con.execute(f"SET threads={int(limits['threads'])}")
    con.execute("SET lock_configuration=true")
    return con


def mount_datasets(con, datasets):
    """每个数据集注册成视图，表名是 run 级编号的确定性映射（t1、t2……）。"""
    mounted = {}
    for entry in datasets:
        alias = entry["alias"]
        path = entry["path"]
        fmt = entry.get("format", "").lower()
        if fmt == "parquet" or path.endswith(".parquet"):
            read = f"read_parquet('{path}')"
        else:
            read = f"read_csv('{path}', header=true, auto_detect=true)"
        con.execute(f"CREATE VIEW {alias} AS SELECT * FROM {read}")
        mounted[alias] = path
    return mounted


def _plan_text(plan_rows):
    return "\n".join(str(row[1]) for row in plan_rows)


def _max_estimated_rows(plan_text):
    """EXPLAIN 文本里每个算子框底部标注估计行数（如 `~20,000 rows`），取最大值对照档位上限。"""
    import re
    estimated = [int(m.replace(",", "")) for m in re.findall(r"~([\d,]+)\s*rows", plan_text)]
    return max(estimated) if estimated else 0


def gate(con, sql, tier):
    """EXPLAIN 只出计划不执行主查询；三项检查任一不过即拒收。返回 (放行?, 报告)。"""
    try:
        plan_rows = con.execute(f"EXPLAIN {sql}").fetchall()
    except Exception as exc:
        return False, {"stage": "explain", "error": str(exc)}
    plan_text = _plan_text(plan_rows)
    report = {"estimated_rows_max": _max_estimated_rows(plan_text), "checks": []}

    # 检查一：结构规则——笛卡尔积、无 LIMIT 的全局排序在计划里有独立算子名。
    if "CROSS_PRODUCT" in plan_text:
        report["checks"].append("cross_product")
        plan_reject("PLAN_CROSS_PRODUCT", "查询包含笛卡尔积连接，准入拒绝", report)
    if "ORDER_BY" in plan_text and "TOP_N" not in plan_text:
        report["checks"].append("global_sort_without_limit")
        plan_reject("PLAN_GLOBAL_SORT", "全局排序缺少 LIMIT，准入拒绝", report)

    # 检查二：估计行数对照档位上限。
    cap = TIER_ESTIMATED_ROW_CAP[tier]
    if report["estimated_rows_max"] > cap:
        report["checks"].append("estimated_rows_over_cap")
        plan_reject("PLAN_ESTIMATED_ROWS_OVER_CAP",
                    f"估计行数 {report['estimated_rows_max']} 超过档位上限 {cap}，准入拒绝", report)
    return True, report


def execute_with_timeout(con, sql, timeout_s):
    """语句超时：定时器到点 interrupt()；中断后本连接丢弃，外层负责重建。"""
    outcome = {}

    def run():
        try:
            outcome["result"] = con.execute(sql)
        except Exception as exc:  # 含中断异常
            outcome["error"] = exc

    worker = threading.Thread(target=run, daemon=True)
    worker.start()
    worker.join(timeout=timeout_s)
    if worker.is_alive():
        con.interrupt()
        worker.join(timeout=5)
        return "TIMEOUT", None, None
    if "error" in outcome:
        return "ERROR", None, outcome["error"]
    return "OK", outcome["result"], None


def main():
    spec = parse_spec()
    tier = spec.get("tier", "INTERACTIVE")
    if tier not in TIER_LIMITS:
        fail("input", "UNKNOWN_TIER", f"未知产品档位 {tier}")
    limits = TIER_LIMITS[tier]

    try:
        con = configure_engine(spec)
    except Exception as exc:
        fail("configure", "ENGINE_CONFIGURE_FAILED", str(exc))

    try:
        mount_datasets(con, spec["datasets"])
    except Exception as exc:
        fail("mount", "DATASET_MOUNT_FAILED", str(exc))

    passed, gate_report = gate(con, spec["sql"], tier)
    if not passed:
        # gate 内部已按 PLAN_REJECTED 信封退出；EXPLAIN 本身失败单独标记。
        fail("gate", "EXPLAIN_FAILED", gate_report.get("error", "explain failed"), gate=gate_report)

    status, cursor, error = execute_with_timeout(con, spec["sql"], limits["statement_timeout_s"])
    if status == "TIMEOUT":
        emit({"status": "STATEMENT_TIMEOUT", "gate": gate_report,
              "limits": {"statement_timeout_s": limits["statement_timeout_s"]}})
        return
    if status == "ERROR":
        fail("execute", "QUERY_EXECUTION_FAILED", str(error), gate=gate_report)

    columns = [desc[0] for desc in cursor.description]
    cap = limits["row_cap"]
    rows = cursor.fetchmany(cap + 1)
    truncated = len(rows) > cap
    rows = rows[:cap]
    emit({
        "status": "SUCCEEDED",
        "columns": columns,
        "rows": [list(r) for r in rows],
        "row_count": len(rows),
        "truncated": truncated,
        "gate": gate_report,
    })


# SPEC_B64 由 Java 侧在投递前替换为 base64 编码的规格 JSON。必须先赋值再进 main()。
SPEC_B64 = "__SPEC_B64_PLACEHOLDER__"

if __name__ == "__main__":
    main()
