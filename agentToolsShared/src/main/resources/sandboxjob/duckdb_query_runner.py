"""executeQuery 固定运行器：模型只提供 SQL，本程序由平台维护、随调用一并下发。

流程：文本级禁令（多语句、ATTACH/COPY/INSTALL/LOAD）-> 锁定引擎配置 ->
只读挂载授权数据集为视图 -> EXPLAIN 准入检查 -> 执行（语句超时可中断）。
输出：stdout 最后一行打印 `__EXECUTE_QUERY_RESULT__` 前缀的 JSON 信封，Java 侧只认这一行。

档位数值（语句超时、返回行数上限、估计行数上限）全部由 Java 侧经规格下发——
容量阈值在 Java 侧是可配置项，双份硬编码必然漂移。两处兼容回落只服务旧锚点
重放的规格：没有估计行数上限时按当时的固定值判定（见 gate）；没有 row_count
时门全程回落引擎估计（见 _dataset_row_counts）。新渲染的规格永远带这两类字段。
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
        resolved_path = _resolve_path(spec, entry["path"])
        path = resolved_path.replace("'", "''")
        fmt = str(entry.get("format") or os.path.splitext(resolved_path)[1].lstrip(".")).lower()
        if fmt == "parquet":
            # Parquet 挂载本版本停用（无生产者）。错误信息带别名、路径与格式，
            # 便于调用方把该数据集从 dataset_ids 去掉后重试。恢复支持只需把本分支
            # 改回 read_parquet 挂载；gate 的扫描与估计逻辑继续服务 CSV/JSON，
            # 不随本停用改动（内嵌 Filters 形态是否再出现取决于未来输入，不写死）。
            raise ValueError(
                f"dataset {alias!r} ({resolved_path}) is parquet, which is disabled in this version; "
                "remove it from dataset_ids and retry with csv/json datasets")
        elif fmt == "json":
            from pathlib import Path
            from af_dataset_loader import read_dataset_file
            # Python读取也必须遵守引擎同一挂载根目录，禁止符号链接越界。
            if not Path(resolved_path).resolve().is_relative_to(Path(_resolve_root(spec)).resolve()):
                raise ValueError("JSON dataset path is outside the approved mount directory")
            frame = read_dataset_file(Path(resolved_path), entry)
            if not len(frame.columns):
                raise ValueError("JSON dataset has no record columns; provide columns metadata for an empty array")
            con.register(alias, frame)
            mounted[alias] = path
            continue
        elif fmt in ("", "csv"):
            read = f"read_csv('{path}', header=true, auto_detect=true)"
        else:
            raise ValueError(f"unsupported dataset format: {fmt!r}")
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


# 统一过滤选择性常数：与 DuckDB 1.5.6 引擎默认取值一致（本机实测两种格式分别为
# 0.199999 与 0.200000）。统一的意义在基数可靠（改用规格下发的真实行数），
# 不在改选择性常数本身。
UNIFORM_FILTER_SELECTIVITY = 0.2

# 数据集扫描算子（EXPLAIN JSON 的 name 与 extra_info.Function 同形）：
# CSV 视图、Parquet 视图、JSON 数据集注册的 pandas 帧（PANDAS_SCAN）。
_SCAN_NODE_NAMES = frozenset({"READ_CSV", "READ_CSV_AUTO", "READ_PARQUET", "PANDAS_SCAN"})


def _is_dataset_scan(node):
    return node.get("name") in _SCAN_NODE_NAMES or \
        node.get("extra_info", {}).get("Function") in _SCAN_NODE_NAMES


def _projection_columns(node):
    """扫描算子 extra_info 的 Projections：多列是列表，单列是裸字符串，缺省按空集。"""
    raw = node.get("extra_info", {}).get("Projections")
    if raw is None:
        return frozenset()
    if isinstance(raw, str):
        return frozenset({raw})
    return frozenset(str(c) for c in raw)


def _dataset_row_counts(spec):
    """从规格读每个数据集的（列集合, 真实行数），供门统一估计口径。
    必须 .get：现有规格字段是硬取键（见 mount_datasets），旧锚点重放的规格没有
    row_count，硬取键会在门阶段 KeyError。规格无 row_count 时映射为空，门全程回落
    引擎估计——这是存档旧请求可能打到新版运行器的防御性兼容，不是恢复必经路径
    （旧锚点重放的是存档完整旧请求）。本函数是行数映射的唯一构建点：
    挂载不需要该字段，门直接从规格取，不经 mount_datasets 的返回值。"""
    stats = []
    for entry in spec.get("datasets", []):
        row_count = entry.get("row_count")
        if row_count is None:
            continue
        try:
            row_count = int(row_count)
        except (TypeError, ValueError):
            continue
        stats.append((frozenset(entry.get("columns") or []), row_count))
    return stats


def _attribute_scan_rows(node, stats):
    """把扫描算子归属到唯一数据集的真实行数：单数据集直接映射；多数据集要求
    投影列集合只被一个数据集的列集合覆盖（投影是数据集合的子集也算覆盖；两个
    数据集列集合重复、或投影同时被多个数据集覆盖，都属非唯一）。归属不上返回
    None，由调用方回落引擎估计——保守方向，不多猜。
    不能按「投影列集合等于数据集列集合」匹配：投影经常是数据集列的真子集
    （如只 SELECT id），相等匹配会把这次扫描错配给列集合恰好等于该子集的另一
    张表——本机实测 window/group_by 只取 id 的查询把 150 行小表的扫描错配成
    30 万行大表，误拒放行用例。"""
    if not stats:
        return None
    if len(stats) == 1:
        return stats[0][1]
    projections = _projection_columns(node)
    if not projections:
        return None
    matches = [row_count for columns, row_count in stats if projections <= columns]
    return matches[0] if len(matches) == 1 else None


def gate(con, sql, tier, spec):
    """EXPLAIN 只出计划不执行主查询；任一检查不过即拒收。返回 (放行?, 报告)。

    检查顺序：文本禁令 -> 结构规则（笛卡尔积、无界全局排序）-> 规模规则（顶层输出
    规模、各算子估计行数对照档位上限）。结构规则先跑是为了让模型拿到可操作的专用
    错误码——笛卡尔积该加连接条件，加 LIMIT 是错的补救方向。
    规模规则的估计口径：扫描与过滤算子按规格下发的真实行数统一换算（见
    _dataset_row_counts），引擎估计只留给连接等中间算子与归属不上的数据集。"""
    limits = spec["limits"]
    report = {"estimated_rows_max": 0, "checks": []}
    preflight(sql, report)

    try:
        plan_tree = _explain_tree(con, sql)
    except Exception as exc:
        return False, {"stage": "explain", "error": str(exc)}
    nodes = [n for root in plan_tree for n in _walk(root)]

    # 估计口径统一：扫描与过滤算子的基数改用规格下发的真实行数换算，
    # 连接等中间算子保留引擎估计。机制（本机 DuckDB 1.5.6 实测并经独立复验）：
    # Parquet 扫描基数取文件脚注真实行数，CSV 由引擎按文件字节数猜测、随每行字节数
    # 漂移且方向不定；两种格式的过滤选择性常数同为 0.2，分歧全在基数来源。
    # 单表场景的估计等于真实行数乘（有过滤取 0.2、无过滤取 1）；多表场景各表同规则
    # 重算求和作输入规模上界，再与中间算子引擎估计取大。
    stats = _dataset_row_counts(spec)
    contributions = []
    absorbed_filters = set()
    attributed = 0
    unattributed = 0
    for root in plan_tree:
        for node, ancestors in _walk_with_ancestors(root):
            if not _is_dataset_scan(node):
                continue
            real_rows = _attribute_scan_rows(node, stats)
            pushdown = "Filters" in node.get("extra_info", {})
            filtered_above = bool(ancestors) and ancestors[-1].get("name") == "FILTER"
            if real_rows is None:
                unattributed += 1
                contributions.append(_node_estimated_rows(node) or 0)
                continue
            attributed += 1
            filtered = pushdown or filtered_above
            contributions.append(
                int(real_rows * UNIFORM_FILTER_SELECTIVITY) if filtered else real_rows)
            if filtered_above:
                # 独立 FILTER 节点的口径已折进该表贡献（真实行数乘 0.2），不再进
                # 中间算子取大，避免引擎猜测的过滤值混进统一口径。
                absorbed_filters.add(id(ancestors[-1]))
    others_max = max(
        (e for n in nodes
         if not _is_dataset_scan(n) and id(n) not in absorbed_filters
         for e in [_node_estimated_rows(n)] if e is not None),
        default=0)
    report["estimated_rows_max"] = max(sum(contributions), others_max)
    # 估计来源写进报告供验收检查：全部数据集扫描都归属到真实行数记 unified，
    # 部分归属记 mixed，完全回落（含旧规格无 row_count）记 engine。
    if attributed and not unattributed:
        report["estimate_source"] = "unified"
    elif attributed:
        report["estimate_source"] = "mixed"
    else:
        report["estimate_source"] = "engine"

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

    # 规模规则一：顶层输出规模 + 无过滤，两个条件同时成立才拒。「无过滤全表扫描」
    # 的关键在「整表」：过滤查询被截断仍语义正确（模型拿到的是过滤后的前 N 行），
    # 整表直出被截断会误导模型（拿到的是表开头的任意前缀）。
    # 「无过滤」是计划级判定，两个信号都要查（两种格式的过滤形态不同）：
    # 独立 FILTER 算子节点（CSV），或扫描节点 extra_info 内嵌 Filters 键（Parquet 下推）。
    row_cap = int(limits["row_cap"])
    plan_filtered = any(n.get("name") == "FILTER" for n in nodes) or any(
        "Filters" in n.get("extra_info", {}) for n in nodes)
    for root in plan_tree:
        name = root.get("name", "")
        if _is_bounded_or_aggregate_top(name):
            continue
        estimated = _node_estimated_rows(root)
        # 顶层即数据集扫描时按统一口径覆盖为真实行数（本规则只在计划无过滤时拒，
        # 无需再乘过滤系数）；多表的连接/投影顶层保留引擎估计（已知边界：
        # PLAN_FULL_SCAN 多表场景根节点不作统一口径，避免验收误判）。
        if _is_dataset_scan(root):
            real_rows = _attribute_scan_rows(root, stats)
            if real_rows is not None:
                estimated = real_rows
        if (estimated is None or estimated > row_cap) and not plan_filtered:
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
    # 适用阈值同样落报告：判定用哪条上限，验收可直接读。
    report["row_cap"] = row_cap
    report["estimated_row_cap"] = cap
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
