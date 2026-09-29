"""executeQuery 固定运行器：模型只提供 SQL，本程序由平台维护、随调用一并下发。

流程：文本级禁令（多语句、ATTACH/COPY/INSTALL/LOAD）-> 锁定引擎配置 ->
只读挂载授权数据集为视图 -> EXPLAIN 准入检查 -> 执行（语句超时可中断）。
输出：stdout 最后一行打印 `__EXECUTE_QUERY_RESULT__` 前缀的 JSON 信封，Java 侧只认这一行。

档位数值（语句超时、返回行数上限、估计行数上限）全部由 Java 侧经规格下发——
容量阈值在 Java 侧是可配置项，双份硬编码必然漂移。唯一例外是估计行数上限的
兼容回落：旧锚点重放的规格里没有该字段时按当时的固定值判定（见 gate），
新渲染的规格永远带它。
"""
import base64
import datetime
import decimal
import json
import os
import re
import sys
import threading

import duckdb

RESULT_MARKER = "__EXECUTE_QUERY_RESULT__"

# 沙箱 stdout 硬上限是 1 MiB（写入瞬间截断、保留头部）。信封打在最后且包含全部结果行，
# 超过安全阈值时换成失败信封，宁可拒也不让 Java 侧拿到半截 JSON。
SAFE_ENVELOPE_BYTES = 900 * 1024


def _to_jsonable(value):
    """DuckDB 驱动的返回类型里 JSON 原生不收的，显式转换：日期/时间转 ISO 字符串，
    Decimal 转 float，二进制转 base64，其余兜底 str。"""
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, (datetime.date, datetime.datetime)):
        return value.isoformat()
    if isinstance(value, decimal.Decimal):
        return float(value)
    if isinstance(value, (bytes, bytearray)):
        return base64.b64encode(bytes(value)).decode("ascii")
    return str(value)


def emit(envelope):
    print(RESULT_MARKER + json.dumps(envelope, ensure_ascii=False, default=_to_jsonable))


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
    # allowed_directories 的值必须是 SQL 字符串字面量（单引号），双引号会被当成标识符。
    root = _resolve_root(spec).replace("'", "''")
    con.execute(f"SET temp_directory='{limits['temp_directory']}'")
    con.execute(f"SET allowed_directories=['{root}']")
    con.execute("SET enable_external_access=false")
    con.execute(f"SET memory_limit='{limits['memory_limit']}'")
    con.execute(f"SET threads={int(limits['threads'])}")
    con.execute("SET lock_configuration=true")
    return con


def _resolve_root(spec):
    """输入根目录双通道：优先兼容符号链接 /sandbox/input（逐任务建立）；
    该链接在容器并发开大时会被沙箱自动关掉，此时回落到任务工作区的 input 目录
    （bounded wrapper 以任务工作区为工作目录启动本脚本）。"""
    mount_dir = spec["mount_dir"]
    if os.path.isdir(mount_dir):
        return mount_dir
    fallback = os.path.abspath("input")
    return fallback


def _resolve_path(spec, path):
    """规格里的数据路径以 mount_dir 为前缀；按实际生效的根目录重定根。"""
    root = _resolve_root(spec)
    mount_dir = spec["mount_dir"].rstrip("/")
    if path.startswith(mount_dir + "/"):
        path = root.rstrip("/") + path[len(mount_dir):]
    return path


def mount_datasets(con, spec):
    """每个数据集注册成视图，表名是 run 级编号的确定性映射（t1、t2……）。"""
    mounted = {}
    for entry in spec["datasets"]:
        alias = entry["alias"]
        # 平台生成的文件名也按 SQL 字符串字面量规则转义，单引号双写。
        path = _resolve_path(spec, entry["path"]).replace("'", "''")
        fmt = entry.get("format", "").lower()
        if fmt == "parquet" or path.endswith(".parquet"):
            read = f"read_parquet('{path}')"
        else:
            read = f"read_csv('{path}', header=true, auto_detect=true)"
        con.execute(f"CREATE VIEW {alias} AS SELECT * FROM {read}")
        mounted[alias] = path
    return mounted


def _code_skeleton(sql):
    """剥掉字符串字面量（单/双引号）与注释（行/块），返回只剩代码骨架的文本。
    禁令检查在骨架上做，字符串里的分号和关键字不会误伤。"""
    out = []
    i = 0
    n = len(sql)
    while i < n:
        c = sql[i]
        if c == "'":
            i += 1
            while i < n:
                if sql[i] == "'":
                    if i + 1 < n and sql[i + 1] == "'":
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            out.append(" ")
        elif c == '"':
            i += 1
            while i < n and sql[i] != '"':
                i += 1
            i += 1
            out.append(" ")
        elif c == "-" and i + 1 < n and sql[i + 1] == "-":
            while i < n and sql[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and sql[i + 1] == "*":
            i += 2
            while i + 1 < n and not (sql[i] == "*" and sql[i + 1] == "/"):
                i += 1
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def preflight(sql, report):
    """文本级禁令：引擎闸管不了的两条在这里拒——多语句（引擎会照跑并只回最后一条）
    与 ATTACH（内存型 ATTACH 不受 external access 关闭影响）。COPY/INSTALL/LOAD
    引擎闸已实测拦截，这里再按文档禁令清单显式拒一次，保证不随引擎版本漂移。"""
    skeleton = _code_skeleton(sql)
    parts = [p.strip() for p in skeleton.split(";")]
    if len([p for p in parts if p]) > 1:
        report["checks"].append("multi_statement")
        plan_reject("PLAN_MULTI_STATEMENT", "只允许单条查询，多语句准入拒绝", report)
    for keyword in ("ATTACH", "COPY", "INSTALL", "LOAD"):
        if re.search(rf"\b{keyword}\b", skeleton, re.IGNORECASE):
            report["checks"].append("forbidden_keyword:" + keyword)
            plan_reject("PLAN_FORBIDDEN_STATEMENT",
                    f"查询包含被禁止的语句 {keyword}，准入拒绝", report)


def _explain_tree(con, sql):
    """EXPLAIN (FORMAT JSON) 只出计划不执行主查询；返回物理计划树列表（通常一棵树）。
    树节点带 name 与 extra_info，祖先关系精确，不靠 ASCII 框序启发式。"""
    rows = con.execute(f"EXPLAIN (FORMAT JSON) {sql}").fetchall()
    payload = rows[0][1] if len(rows[0]) > 1 else rows[0][0]
    return json.loads(payload)


def _walk(node):
    yield node
    for child in node.get("children", []):
        yield from _walk(child)


def _walk_with_ancestors(node, ancestors=()):
    yield node, ancestors
    for child in node.get("children", []):
        yield from _walk_with_ancestors(child, ancestors + (node,))


def _node_estimated_rows(node):
    """extra_info 里的 Estimated Cardinality 是字符串；有界/聚合类算子不给该值。"""
    raw = node.get("extra_info", {}).get("Estimated Cardinality")
    if raw is None:
        return None
    try:
        return int(str(raw).replace(",", ""))
    except ValueError:
        return None


# 顶层输出有界或聚合收敛的算子：LIMIT 类显式有界；聚合/GROUP BY 把结果收敛到小组
# 规模，超返回上限由截断机制承载。其余顶层算子按估计行数判定。
_BOUNDED_TOP_NAMES = frozenset({"LIMIT", "STREAMING_LIMIT", "TOP_N"})


def _is_bounded_or_aggregate_top(name):
    return name in _BOUNDED_TOP_NAMES or "AGGREGATE" in name or "GROUP_BY" in name


def gate(con, sql, tier, spec):
    """EXPLAIN 只出计划不执行主查询；任一检查不过即拒收。返回 (放行?, 报告)。

    检查顺序：文本禁令 -> 结构规则（笛卡尔积、无界全局排序）-> 规模规则（顶层输出
    规模、各算子估计行数对照档位上限）。结构规则先跑是为了让模型拿到可操作的专用
    错误码——笛卡尔积该加连接条件，加 LIMIT 是错的补救方向。"""
    limits = spec["limits"]
    report = {"estimated_rows_max": 0, "checks": []}
    preflight(sql, report)

    try:
        plan_tree = _explain_tree(con, sql)
    except Exception as exc:
        return False, {"stage": "explain", "error": str(exc)}
    nodes = [n for root in plan_tree for n in _walk(root)]
    report["estimated_rows_max"] = max(
        (e for e in (_node_estimated_rows(n) for n in nodes) if e is not None),
        default=0)

    # 结构规则一：笛卡尔积有独立算子名。
    if any(n.get("name") == "CROSS_PRODUCT" for n in nodes):
        report["checks"].append("cross_product")
        plan_reject("PLAN_CROSS_PRODUCT", "查询包含笛卡尔积连接，准入拒绝", report)

    # 结构规则二：无界全局排序。duckdb 不给排序算子印估计行数，有界重排与无界
    # 全排只能按祖先链区分：ORDER_BY 的祖先里没有 TOP_N 就是对全集排序。
    for root in plan_tree:
        for node, ancestors in _walk_with_ancestors(root):
            if node.get("name") == "ORDER_BY" and not any(
                    a.get("name") == "TOP_N" for a in ancestors):
                report["checks"].append("global_sort_without_top_n")
                plan_reject("PLAN_GLOBAL_SORT",
                            "查询包含无 LIMIT 的全局排序，准入拒绝；请加 LIMIT 做 Top-N",
                            report)

    # 规模规则一：顶层输出规模。要回答的问题只有一个——这条查询最终会返回超过
    # 返回行数上限的行吗？顶层是 LIMIT 类或聚合类算子直接放行；其余看顶层估计行数，
    # 超过上限即拒；估计缺失且不在白名单同样拒（宁可错拒也不放过）。
    row_cap = int(limits["row_cap"])
    for root in plan_tree:
        name = root.get("name", "")
        if _is_bounded_or_aggregate_top(name):
            continue
        estimated = _node_estimated_rows(root)
        if estimated is None or estimated > row_cap:
            report["checks"].append("top_output_over_row_cap")
            plan_reject("PLAN_FULL_SCAN",
                        f"查询顶层输出估计 {estimated if estimated is not None else '未知'} 行，"
                        f"超过返回行数上限 {row_cap}，准入拒绝；请加 LIMIT、加过滤条件或改用聚合",
                        report)

    # 规模规则二：各算子估计行数对照档位上限。估计行数上限与返回行数上限是两个
    # 不同口径：这里管估计处理规模。新渲染的规格永远带 estimated_row_cap（值由
    # Java 侧容量配置下发）；旧锚点重放的规格里没有该字段，回落当时的固定上限，
    # 保证老作业恢复后行为不变。两个回落值只服务兼容性，不再随版本调整。
    cap_raw = limits.get("estimated_row_cap")
    cap = int(cap_raw) if cap_raw is not None else (200_000 if tier == "INTERACTIVE" else 600_000)
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
    if tier not in ("INTERACTIVE", "BACKGROUND"):
        fail("input", "UNKNOWN_TIER", f"未知产品档位 {tier}")
    limits = spec["limits"]

    try:
        con = configure_engine(spec)
    except Exception as exc:
        fail("configure", "ENGINE_CONFIGURE_FAILED", str(exc))

    try:
        mount_datasets(con, spec)
    except Exception as exc:
        fail("mount", "DATASET_MOUNT_FAILED", str(exc))

    passed, gate_report = gate(con, spec["sql"], tier, spec)
    if not passed:
        # gate 内部已按 PLAN_REJECTED 信封退出；EXPLAIN 本身失败单独标记。
        fail("gate", "EXPLAIN_FAILED", gate_report.get("error", "explain failed"), gate=gate_report)

    statement_timeout_s = int(limits["statement_timeout_s"])
    status, cursor, error = execute_with_timeout(con, spec["sql"], statement_timeout_s)
    if status == "TIMEOUT":
        emit({"status": "STATEMENT_TIMEOUT",
              "error": {"code": "STATEMENT_TIMEOUT",
                        "message": f"语句超过 {statement_timeout_s} 秒档位上限被中断"},
              "gate": gate_report,
              "limits": {"statement_timeout_s": statement_timeout_s}})
        return
    if status == "ERROR":
        fail("execute", "QUERY_EXECUTION_FAILED", str(error), gate=gate_report)

    columns = [desc[0] for desc in cursor.description]
    cap = int(limits["row_cap"])
    rows = cursor.fetchmany(cap + 1)
    truncated = len(rows) > cap
    rows = rows[:cap]
    envelope = {
        "status": "SUCCEEDED",
        "columns": columns,
        "rows": [[_to_jsonable(v) for v in r] for r in rows],
        "row_count": len(rows),
        "truncated": truncated,
        "gate": gate_report,
    }
    # 信封体积守卫：超安全阈值时改发失败信封，不让半截 JSON 流到 Java 侧。
    payload = json.dumps(envelope, ensure_ascii=False, default=_to_jsonable)
    if len(payload.encode("utf-8")) > SAFE_ENVELOPE_BYTES:
        fail("serialize", "QUERY_RESULT_TOO_LARGE",
             "结果集超过单次返回体积上限，请收窄查询（加过滤、减少列或降低行数）后重试",
             row_count=len(rows))
    print(RESULT_MARKER + payload)


# SPEC_B64 由 Java 侧在投递前替换为 base64 编码的规格 JSON。必须先赋值再进 main()。
SPEC_B64 = "__SPEC_B64_PLACEHOLDER__"

if __name__ == "__main__":
    main()
