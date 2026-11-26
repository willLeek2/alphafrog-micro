#!/usr/bin/env python3
"""按明确清单读取已有检查报告，保留覆盖缺口及版本比较依据。"""

import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


STATES = ("passed", "failed", "skipped", "not_executed")
LABELS = dict(zip(STATES, ("通过", "失败", "跳过", "未执行")))
COMPARISONS = {
    "new_failure": "新增失败", "existing_failure": "已有失败",
    "recovered": "恢复通过", "unchanged_pass": "保持通过",
    "candidate_only": "候选新增检查", "incomparable": "无法比较",
}


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def nonempty_string(value):
    return isinstance(value, str) and bool(value.strip())


def match_key(item):
    if item.get("format") == "pass_fail_text_v1":
        return ("pass_fail_text_v1", item.get("report_module"), item.get("report_name"))
    return ("junit_xml",) + tuple(item.get(k) for k in ("report_module", "report_class", "report_case"))


def load_manifest(path):
    """清单提供定义身份和逐项报告对应关系，不猜测参数化名称。"""
    manifest = read_json(path)
    if not isinstance(manifest, dict) or manifest.get("schema_version") != 1 or not isinstance(manifest.get("checks"), list):
        raise ValueError("清单需要 schema_version=1 与 checks 数组")
    ids, definitions, matches = set(), set(), set()
    for check in manifest["checks"]:
        if not isinstance(check, dict):
            raise ValueError("每条检查必须是对象")
        if not all(nonempty_string(check.get(k)) for k in ("check_id", "repo", "module")):
            raise ValueError("检查缺少 check_id、repo 或 module 字符串")
        identity = check["check_id"]
        if identity in ids:
            raise ValueError("清单检查编号重复: " + identity)
        ids.add(identity)
        source = check.get("source")
        if not isinstance(source, dict) or not nonempty_string(source.get("file")):
            raise ValueError(identity + ": source 需要 file 字符串")
        named = "check_name" in source
        if named:
            if not nonempty_string(source["check_name"]) or any(k in source for k in ("class", "method")):
                raise ValueError(identity + ": 具名定义需要 check_name，不填写 class/method")
            definition = (check["repo"], check["module"], "named", source["file"], source["check_name"])
        else:
            if not all(nonempty_string(source.get(k)) for k in ("class", "method")):
                raise ValueError(identity + ": XML源定义需要 class/method 字符串")
            definition = (check["repo"], check["module"], "junit_xml", source["class"], source["method"])
        if definition in definitions:
            raise ValueError(identity + ": 相同源定义重复，请合并到同一条 report_matches")
        definitions.add(definition)
        if any(type(check.get(k)) is not bool for k in ("exists_in_baseline", "exists_in_candidate")):
            raise ValueError(identity + ": 存在标记必须是布尔值")
        if check.get("definition_count") != 1 or type(check.get("definition_count")) is not int:
            raise ValueError(identity + ": definition_count 必须是整数1")
        if "expanded_count" in check:
            raise ValueError(identity + ": expanded_count 只在输出中统计")
        report_matches = check.get("report_matches")
        if not isinstance(report_matches, list) or not report_matches:
            raise ValueError(identity + ": 需要非空 report_matches 数组")
        for item in report_matches:
            if not isinstance(item, dict):
                raise ValueError(identity + ": 报告对应项必须是对象")
            report_format = item.get("format", "junit_xml")
            if report_format not in ("junit_xml", "pass_fail_text_v1"):
                raise ValueError(identity + ": 不支持的报告格式")
            if (report_format == "pass_fail_text_v1") != named:
                raise ValueError(identity + ": 具名源定义与文本报告格式必须对应")
            forbidden = ("report_class", "report_case") if named else ("report_name",)
            if any(k in item for k in forbidden):
                raise ValueError(identity + ": 报告对应项混用了两种格式的身份字段")
            if not all(nonempty_string(x) for x in match_key(item)):
                required = "report_module/report_name" if named else "模块、类、用例"
                raise ValueError(identity + ": 每项报告对应关系需要 " + required + " 字符串")
            if item["report_module"] != check["module"]:
                raise ValueError(identity + ": 清单模块与报告对应模块不一致")
            key = (check["repo"],) + match_key(item)
            if key in matches:
                raise ValueError(identity + ": 报告对应关系重复或属于多个检查")
            matches.add(key)
        for name in ("fixed_inputs", "result_params"):
            if name in check and not isinstance(check[name], dict):
                raise ValueError(identity + ": " + name + " 需要按 baseline/candidate 保存的对象")
    return manifest


def load_execution_record(path):
    """仅保存维护者提供的执行资料，缺失资料不会被补造。"""
    path = Path(path).resolve()
    data = read_json(path)
    if not isinstance(data, dict):
        raise ValueError("执行记录顶层必须是对象")
    repositories = data.get("repositories")
    if not isinstance(repositories, list):
        raise ValueError("执行记录需要 repositories 数组")
    output = {}
    for item in repositories:
        if not isinstance(item, dict) or not nonempty_string(item.get("repo")):
            raise ValueError("执行记录缺少 repo 字符串")
        repo = item["repo"]
        if repo in output:
            raise ValueError("执行记录仓库重复: " + repo)
        value = dict(item)
        root = item.get("root")
        value["root"] = str((path.parent / root).resolve()) if nonempty_string(root) else None
        issues = []
        for field in ("commit", "command", "compile_status", "package_status"):
            if not nonempty_string(item.get(field)):
                issues.append("执行资料缺失: " + field)
        environment = item.get("environment")
        if not isinstance(environment, dict) or not all(
                nonempty_string(environment.get(k)) for k in ("runtime", "runner")):
            issues.append("执行资料缺失: environment.runtime/runner")
        if "selection" not in item:
            issues.append("执行资料缺失: selection")
        if value["root"] is None:
            issues.append("报告根目录缺失: root")
        if not isinstance(item.get("reports"), list):
            issues.append("报告列表缺失: reports")
            value["reports"] = []
        value["metadata_issues"] = issues
        output[repo] = value
    return output


def module_for_report(path, root):
    """只使用保留了 Maven 模块层级的报告路径，不读取 XML suite 名猜模块。"""
    if root is None:
        return None
    try:
        parts = path.relative_to(Path(root)).parts
    except ValueError:
        return None
    if len(parts) < 3 or parts[-3:-1] != ("target", "surefire-reports"):
        return None
    return "/".join(parts[:-3]) or "."


def local_tag(element):
    return element.tag.rsplit("}", 1)[-1]


def read_pass_fail_text(raw, repo, module, path, digest):
    """只按明确的逐行记录取值；辅助输出不补齐检查状态。"""
    records, issues, auxiliary, summaries = [], [], [], []
    issue = {"repo": repo, "report": str(path)}
    try:
        lines = raw.decode("utf-8").splitlines()
    except UnicodeError as error:
        return [], [dict(issue, reason="文本报告不是有效UTF-8: " + str(error))], []
    for line_number, line in enumerate(lines, 1):
        original = {"line_number": line_number, "raw_line": line}
        if line.startswith(("PASS ", "FAIL ")):
            name, delimiter, detail = line[5:].partition(" | ")
            if not nonempty_string(name) or name.startswith(" "):
                issues.append(dict(issue, **original, reason="文本记录缺少原名或前缀后不止一个空格"))
                continue
            failed = line.startswith("FAIL ")
            records.append({
                "repo": repo, "format": "pass_fail_text_v1", "report_module": module,
                "report_name": name, "status": "failed" if failed else "passed",
                "report": str(path), "report_sha256": digest, **original,
                "detail": detail if delimiter else None, "input_issues": [],
                "failure_originals": [{"kind": "FAIL", **original,
                                       "detail": detail if delimiter else None}] if failed else [],
            })
            continue
        summary = re.fullmatch(r"== ([0-9]+) passed, ([0-9]+) failed ==", line)
        valid_auxiliary = not line.strip() or summary is not None
        if summary:
            summaries.append((int(summary[1]), int(summary[2]), original))
        if line.startswith("__EXECUTE_QUERY_RESULT__"):
            try:
                valid_auxiliary = isinstance(json.loads(line[len("__EXECUTE_QUERY_RESULT__"):]), dict)
            except ValueError:
                valid_auxiliary = False
        if line.startswith("FAILED: "):
            try:
                value = ast.literal_eval(line[len("FAILED: "):])
                valid_auxiliary = isinstance(value, list) and all(isinstance(x, str) for x in value)
            except (ValueError, SyntaxError):
                valid_auxiliary = False
        if valid_auxiliary:
            auxiliary.append(original)
        else:
            issues.append(dict(issue, **original, reason="无法识别的文本报告行"))
    actual = (sum(r["status"] == "passed" for r in records),
              sum(r["status"] == "failed" for r in records))
    for passed, failed, original in summaries:
        if (passed, failed) != actual:
            issues.append(dict(issue, **original, reason="文本汇总与逐行记录数量不一致"))
    return records, issues, auxiliary


def read_reports(executions):
    records, issues, files = [], [], []
    for repo, execution in executions.items():
        seen_paths = set()
        for supplied in execution["reports"]:
            issue = {"repo": repo, "report": supplied}
            text_report = isinstance(supplied, dict)
            if text_report:
                if supplied.get("format") != "pass_fail_text_v1" or not all(
                        nonempty_string(supplied.get(k)) for k in ("path", "module")):
                    issues.append(dict(issue, reason="文本报告需要明确的 format、path、module"))
                    continue
                supplied_path = supplied["path"]
            else:
                supplied_path = supplied
            if not nonempty_string(supplied_path):
                issues.append(dict(issue, reason="报告路径必须是字符串"))
                continue
            path = Path(supplied_path)
            if not path.is_absolute():
                if execution["root"] is None:
                    issues.append(dict(issue, reason="相对报告路径缺少 root"))
                    continue
                path = Path(execution["root"]) / path
            path = path.resolve()
            if path in seen_paths:
                issues.append(dict(issue, reason="同一报告路径重复提供"))
                continue
            seen_paths.add(path)
            if text_report:
                if execution["root"] is None or not path.is_relative_to(Path(execution["root"])):
                    issues.append(dict(issue, reason="文本报告必须位于声明的 root 内"))
                    continue
                module = supplied["module"]
            else:
                module = module_for_report(path, execution["root"])
            try:
                raw = path.read_bytes()
            except OSError as error:
                issues.append(dict(issue, reason="报告无法读取或解析: " + str(error)))
                continue
            digest = hashlib.sha256(raw).hexdigest()
            if text_report:
                text_records, text_issues, auxiliary = read_pass_fail_text(raw, repo, module, path, digest)
                records.extend(text_records)
                issues.extend(text_issues)
                files.append({"repo": repo, "path": str(path), "sha256": digest, "bytes": len(raw),
                              "format": "pass_fail_text_v1", "module": module, "auxiliary_lines": auxiliary})
                continue
            try:
                root = ET.fromstring(raw)
            except ET.ParseError as error:
                issues.append(dict(issue, reason="报告无法读取或解析: " + str(error)))
                continue
            files.append({"repo": repo, "path": str(path), "sha256": digest, "bytes": len(raw)})
            if local_tag(root) not in ("testsuite", "testsuites"):
                issues.append(dict(issue, reason="不支持的 XML 根元素: " + local_tag(root)))
                continue
            if module is None:
                issues.append(dict(issue, reason="无法从 Maven 报告目录确定模块"))
            for suite in root.iter():
                if local_tag(suite) != "testsuite":
                    continue
                cases = [x for x in suite if local_tag(x) == "testcase"]
                nested = any(local_tag(x) == "testsuite" for x in suite)
                if not nested:
                    actual_counts = {"tests": len(cases), **{
                        key: sum(any(local_tag(x) == tag for x in case) for case in cases)
                        for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped"))}}
                    for key, actual in actual_counts.items():
                        if key not in suite.attrib:
                            continue
                        try:
                            if int(suite.get(key)) != actual:
                                raise ValueError()
                        except ValueError:
                            issues.append(dict(issue, reason="suite 汇总与逐条记录不一致: " + key))
                unsupported = [local_tag(x) for x in suite if local_tag(x) not in
                               ("testcase", "testsuite", "properties", "system-out", "system-err")]
                if unsupported:
                    issues.append(dict(issue, reason="存在无法解释的 suite 元素: " + ", ".join(unsupported)))
                for case in cases:
                    children = list(case)
                    failures = [x for x in children if local_tag(x) in ("failure", "error")]
                    skips = [x for x in children if local_tag(x) == "skipped"]
                    unknown = [local_tag(x) for x in children if local_tag(x) not in
                               ("failure", "error", "skipped", "system-out", "system-err", "properties")]
                    problems = []
                    if not nonempty_string(case.get("classname")) or not nonempty_string(case.get("name")):
                        problems.append("报告记录缺少类名或用例名")
                    if module is None:
                        problems.append("报告模块无法确定")
                    if unknown:
                        problems.append("存在无法解释的结果元素: " + ", ".join(unknown))
                    if failures and skips:
                        problems.append("同一记录同时包含失败与跳过")
                    if case.get("status") not in (None, "run"):
                        problems.append("无法解释的 testcase status: " + case.get("status"))
                    status = "failed" if failures else "skipped" if skips else "passed"
                    if problems and status == "passed":
                        status = "not_executed"
                    records.append({
                        "repo": repo, "report_module": module,
                        "report_class": case.get("classname"), "report_case": case.get("name"),
                        "status": status, "report": str(path), "report_sha256": digest,
                        "attributes": dict(case.attrib), "input_issues": problems,
                        "failure_originals": [{"kind": local_tag(x), "attributes": dict(x.attrib),
                                               "text": "".join(x.itertext()),
                                               "xml": ET.tostring(x, encoding="unicode")} for x in failures],
                        "raw_xml": ET.tostring(case, encoding="unicode"),
                    })
    return {"records": records, "issues": issues, "files": files}


def summarize_coverage(manifest, executions, reports, side):
    index = {}
    for record in reports["records"]:
        index.setdefault((record["repo"],) + match_key(record), []).append(record)
    results, mapped = {}, set()
    for check in manifest["checks"]:
        repo = check["repo"]
        records, missing, issues = [], [], []
        duplicate_text = False
        for match in check["report_matches"]:
            key = (repo,) + match_key(match)
            found = index.get(key, [])
            if not found:
                missing.append(dict(match))
            if len(found) > 1:
                issues.append("同一报告对应项出现重复记录: " + repr(match_key(match)))
                duplicate_text = duplicate_text or match.get("format") == "pass_fail_text_v1"
            records.extend(found)
            mapped.add(key)
        for record in records:
            issues.extend(record["input_issues"])
        exists = check["exists_in_" + side]
        if not exists and records:
            issues.append("清单声明定义不存在，但报告含对应记录")
        # 报告无法解析或重复时，不能确定它是否另含相同检查。
        issues.extend(x["reason"] for x in reports["issues"] if x["repo"] == repo)
        if duplicate_text:
            status = "not_executed"
        elif any(x["status"] == "failed" for x in records):
            status = "failed"
        elif any(x["status"] == "skipped" for x in records):
            status = "skipped"
        elif missing or not exists or any(x["status"] == "not_executed" for x in records):
            status = "not_executed"
        else:
            status = "passed"
        results[check["check_id"]] = {
            "status": status, "definition_exists": exists, "records": records,
            "expanded_count": len(records), "missing_cases": missing,
            "coverage_complete": exists and not missing and not issues,
            "input_issues": list(dict.fromkeys(issues)),
        }
    unmapped = [r for r in reports["records"] if (r["repo"],) + match_key(r) not in mapped]
    return results, unmapped


def comparable_value(check, field):
    value = check.get(field, {})
    if not all(k in value and value[k] is not None for k in ("baseline", "candidate")):
        return False
    # JSON 的布尔值与数字不能按 Python 的 True == 1 判断为相同输入。
    return json.dumps(value["baseline"], sort_keys=True, ensure_ascii=False) == json.dumps(
        value["candidate"], sort_keys=True, ensure_ascii=False)


def compare_checks(check, baseline, candidate, executions):
    conflicts = [side + " 清单声明定义不存在，但报告含对应记录"
                 for side, result in (("baseline", baseline), ("candidate", candidate))
                 if not check["exists_in_" + side] and result["records"]]
    if conflicts:
        return {"kind": "incomparable", "reasons": conflicts}
    if not check["exists_in_baseline"] and check["exists_in_candidate"]:
        return {"kind": "candidate_only", "reasons": ["候选新增定义，不属于同一检查的失败变化"]}
    reasons = []
    for side, result in (("baseline", baseline), ("candidate", candidate)):
        if result["status"] not in ("passed", "failed"):
            reasons.append(("基础未覆盖" if side == "baseline" and result["status"] == "not_executed"
                            else side + " 为" + LABELS[result["status"]]))
        if not result["coverage_complete"]:
            reasons.append(side + " 对应记录不完整或存在输入问题")
        execution = executions[side].get(check["repo"])
        if execution is None:
            reasons.append(side + " 缺少仓库执行记录")
        elif execution["metadata_issues"]:
            reasons.extend(side + " " + x for x in execution["metadata_issues"])
    for field in ("fixed_inputs", "result_params"):
        if not comparable_value(check, field):
            reasons.append(field + " 两侧缺值或不一致")
    old = executions["baseline"].get(check["repo"], {})
    new = executions["candidate"].get(check["repo"], {})
    if old.get("environment") != new.get("environment"):
        reasons.append("运行时或执行器环境不同")
    if reasons:
        return {"kind": "incomparable", "reasons": list(dict.fromkeys(reasons))}
    kinds = {("passed", "failed"): "new_failure", ("failed", "failed"): "existing_failure",
             ("failed", "passed"): "recovered", ("passed", "passed"): "unchanged_pass"}
    return {"kind": kinds[(baseline["status"], candidate["status"])], "reasons": []}


def build_report(manifest, executions, reports):
    coverage, unmapped = {}, {}
    for side in ("baseline", "candidate"):
        coverage[side], unmapped[side] = summarize_coverage(manifest, executions[side], reports[side], side)
    checks, summary = [], {}
    for check in manifest["checks"]:
        identity, repo = check["check_id"], check["repo"]
        baseline, candidate = coverage["baseline"][identity], coverage["candidate"][identity]
        checks.append({"check_id": identity, "repo": repo, "module": check["module"],
                       "source": check["source"], "baseline": baseline, "candidate": candidate,
                       "comparison": compare_checks(check, baseline, candidate, executions)})
        if repo not in summary:
            summary[repo] = {"manifest_definitions": 0, **{
                side: {"definition_count": 0, "existing_definition_count": 0, "expanded_count": 0,
                       "states": dict.fromkeys(STATES, 0)} for side in ("baseline", "candidate")}}
        summary[repo]["manifest_definitions"] += 1
        for side, result in (("baseline", baseline), ("candidate", candidate)):
            total = summary[repo][side]
            total["definition_count"] += 1
            total["existing_definition_count"] += int(result["definition_exists"])
            total["expanded_count"] += result["expanded_count"]
            total["states"][result["status"]] += 1
    return {"schema_version": 1, "manifest": manifest, "checks": checks, "summary": summary,
            "executions": executions, "report_files": {s: reports[s]["files"] for s in reports},
            "unmapped_records": unmapped, "input_issues": {s: reports[s]["issues"] for s in reports},
            "evidence_boundary": "未执行表示未取得完整可信的对应记录；执行来源由维护者声明，报告本身不能证明提交或实际业务可用性。"}


def markdown_report(report):
    def cell(value):
        return str(value).replace("|", "\\|").replace("\n", " ")
    lines = ["# 静态检查覆盖与版本比较", "", report["evidence_boundary"], "",
             "| 仓库 | 检查 | 基准 | 候选 | 比较 | 原因 |", "| --- | --- | --- | --- | --- | --- |"]
    for item in report["checks"]:
        lines.append("| " + " | ".join(cell(x) for x in (
            item["repo"], item["check_id"], LABELS[item["baseline"]["status"]],
            LABELS[item["candidate"]["status"]], COMPARISONS[item["comparison"]["kind"]],
            "；".join(item["comparison"]["reasons"]))) + " |")
    for repo, total in report["summary"].items():
        lines.extend(["", "## " + repo, "", "清单定义数：" + str(total["manifest_definitions"]) + "。"])
        for side, label in (("baseline", "基准"), ("candidate", "候选")):
            count = total[side]
            lines.append("\n" + label + "：" + "，".join(LABELS[s] + str(count["states"][s]) for s in STATES)
                         + "；实际展开记录" + str(count["expanded_count"]) + "。")
    lines.extend(["", "## 原始失败与输入问题", ""])
    for item in report["checks"]:
        for side in ("baseline", "candidate"):
            for record in item[side]["records"]:
                for failure in record["failure_originals"]:
                    raw = failure["raw_line"] if record.get("format") == "pass_fail_text_v1" else failure["xml"]
                    language = "text" if record.get("format") == "pass_fail_text_v1" else "xml"
                    fence = "```"
                    while fence in raw:
                        fence += "`"
                    lines.extend([item["check_id"] + " / " + side + " / " + record["report"],
                                  "", fence + language, raw, fence, ""])
    for side in ("baseline", "candidate"):
        lines.append(side + "：未对应记录" + str(len(report["unmapped_records"][side])) + "。")
        for issue in report["input_issues"][side]:
            lines.append("- " + cell(issue))
    lines.extend(["", "逐条原始记录、缺少的对应项、执行资料与文件摘要保存在JSON输出中。", ""])
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, help="检查定义与报告对应清单JSON")
    parser.add_argument("--baseline", required=True, help="基准执行记录JSON")
    parser.add_argument("--candidate", required=True, help="候选执行记录JSON")
    parser.add_argument("--json-output", help="明确指定时写出JSON，否则输出到标准输出")
    parser.add_argument("--markdown-output", help="明确指定时写出Markdown")
    args = parser.parse_args(argv)
    try:
        manifest = load_manifest(args.manifest)
        executions = {s: load_execution_record(getattr(args, s)) for s in ("baseline", "candidate")}
        reports = {s: read_reports(e) for s, e in executions.items()}
        report = build_report(manifest, executions, reports)
        payload = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.json_output:
            Path(args.json_output).write_text(payload, encoding="utf-8")
        else:
            print(payload, end="")
        if args.markdown_output:
            Path(args.markdown_output).write_text(markdown_report(report), encoding="utf-8")
    except (OSError, ValueError, TypeError) as error:
        print("无法生成报告: " + str(error), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
