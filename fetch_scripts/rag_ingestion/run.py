#!/usr/bin/env python3
"""
RAG Ingestion 主入口脚本。

流程：
1. 从 DB 查 vectorized=FALSE 且 oss_url IS NULL 的记录（公告/研报）
2. 对每条记录：
   a. 用 Jina Reader API 抓取 URL → Markdown 全文
      （公告为 cninfo 页面，研报为 PDF 直链；无 URL 的研报退回到摘要字段）
   b. POST 内容给服务端 /rag/upload-doc，服务端经 VPC 内网上传到 OSS，返回 object key
   c. 更新 DB：oss_url（存储 object key）
   d. 全文切块 → embedding → 调 ingestion 端点写入 Qdrant
   e. 更新 DB：vectorized = TRUE

用法（传统 CLI 模式）：
  python run.py --doc-type ann --limit 10
  python run.py --doc-type research --limit 20
  python run.py --doc-type all --limit 50

用法（YAML 任务配置模式）：
  python run.py --task-config rag-ingestion-tasks.yml
  # 每个 task 可独立指定 doc_type、date_from/date_to、ts_code、limit、offset
  # 旧式 tasks 列表 (向后兼容)

YAML 配置文件支持两种格式 (互斥, 不可同时出现):
  1) tasks 列表 (旧式, 继续支持)
     tasks:
       - name: ...
  2) scenarios 列表 (新式, 每项必含 name)
     scenarios:
       - name: <scenario_name>
         ts_code: { type: list|select, ... }  # None 时整库扫
         date_from: "20240101"
         date_to: "20241231"
         doc_type: ann|research|all
         limit: 50
         offset: 0
"""
import argparse
import sys

import yaml
from dotenv import load_dotenv, find_dotenv
from tqdm import tqdm

from config import load_config, config_with_embedding_override, config_with_auth_override
from db_client import DbClient
from jina_reader import crawl_url
from oss_uploader import upload_doc
from chunker import chunk_text
from embedder import get_embeddings
from ingest_client import ingest_vectors
from ts_code_filter import ConfigError, TsCodeFilter


def process_announcements(
    db: DbClient,
    cfg,
    limit: int,
    offset: int = 0,
    date_from: str = None,
    date_to: str = None,
    ts_code=None,
    title_patterns: list = None,
):
    records = db.get_unprocessed_announcements(
        limit=limit,
        offset=offset,
        date_from=date_from or None,
        date_to=date_to or None,
        ts_code=ts_code or None,
        title_patterns=title_patterns or None,
    )
    if not records:
        print("[run] No unprocessed announcements found.")
        return

    print(f"[run] Processing {len(records)} announcements...")
    for rec in tqdm(records, desc="Announcements"):
        record_id = rec["id"]
        ts_code_val = rec["ts_code"]
        ann_date = rec["ann_date"]
        title = rec["title"]
        url = rec["url"]

        try:
            print(f"  [ann] id={record_id} ts_code={ts_code_val} ann_date={ann_date}")
            print(f"        title={title!r}")
            if not url:
                print(f"  [skip] id={record_id} no url")
                continue
            print(f"        url={url}")

            # a. Jina 爬取公告页面 → Markdown
            markdown_text = crawl_url(url, cfg)
            if not markdown_text.strip():
                print(f"  [skip] id={record_id} empty content from Jina")
                continue
            print(f"  [jina] id={record_id} content_len={len(markdown_text)} chars")

            # b. 上传 OSS（通过服务端中转）
            oss_url = upload_doc(cfg, "ann", ts_code_val, ann_date, title, markdown_text)
            print(f"  [oss]  id={record_id} ossKey={oss_url}")

            # c. 更新 DB oss_url
            db.update_announcement_oss_url(record_id, oss_url)

            # d. 切块 + embedding + ingest
            chunks = chunk_text(markdown_text)
            if chunks:
                print(f"  [chunk] id={record_id} chunks={len(chunks)}")
                embeddings = get_embeddings(chunks, cfg)
                print(f"  [embed] id={record_id} embeddings={len(embeddings)} dim={len(embeddings[0]) if embeddings else 0}")
                metadata = {
                    "doc_type": "announcement",
                    "ts_code": ts_code_val,
                    "ann_date": ann_date,
                    "title": title,
                    "oss_url": oss_url,
                }
                success = ingest_vectors(
                    cfg,
                    doc_id=f"ann_{record_id}",
                    doc_type="announcement",
                    chunks=chunks,
                    embeddings=embeddings,
                    metadata=metadata,
                )
                if success:
                    db.mark_announcement_vectorized(record_id)
                    print(f"  [ok]   id={record_id} done (chunks={len(chunks)})")
                else:
                    print(f"  [fail] id={record_id} ingest failed")
            else:
                print(f"  [skip] id={record_id} no chunks after chunking")

        except Exception as e:
            print(f"  [error] id={record_id}: {e}")


def process_reports(
    db: DbClient,
    cfg,
    limit: int,
    offset: int = 0,
    date_from: str = None,
    date_to: str = None,
    ts_code=None,
    title_patterns: list = None,
):
    records = db.get_unprocessed_reports(
        limit=limit,
        offset=offset,
        date_from=date_from or None,
        date_to=date_to or None,
        ts_code=ts_code or None,
        title_patterns=title_patterns or None,
    )
    if not records:
        print("[run] No unprocessed research reports found.")
        return

    print(f"[run] Processing {len(records)} research reports...")
    for rec in tqdm(records, desc="Reports"):
        record_id = rec["id"]
        ts_code_val = rec.get("ts_code", "")
        trade_date = rec["trade_date"]
        title = rec["title"]
        abstr = rec.get("abstr", "")
        url = rec.get("url", "")

        try:
            print(f"  [rep] id={record_id} ts_code={ts_code_val} trade_date={trade_date}")
            print(f"        title={title!r}")
            markdown_text = ""
            used_abstract_fallback = False

            # a. 优先用 Jina 爬取 PDF/页面
            if url:
                print(f"        url={url}")
                try:
                    markdown_text = crawl_url(url, cfg)
                    print(f"  [jina] id={record_id} content_len={len(markdown_text)} chars")
                except Exception as crawl_err:
                    print(f"  [warn] id={record_id} crawl failed: {crawl_err}")

            # 退回到摘要
            if not markdown_text.strip() and abstr:
                markdown_text = abstr
                used_abstract_fallback = True
                print(f"  [fallback] id={record_id} using abstract ({len(abstr)} chars)")

            if not markdown_text.strip():
                print(f"  [skip] id={record_id} no content")
                continue

            # b. 上传 OSS（通过服务端中转）
            oss_url = upload_doc(
                cfg, "research", ts_code_val or "", trade_date, title, markdown_text,
                file_extension=".txt" if used_abstract_fallback else ".md",
            )
            print(f"  [oss]  id={record_id} ossKey={oss_url}")

            # c. 更新 DB oss_url
            db.update_report_oss_url(record_id, oss_url)

            # d. 切块 + embedding + ingest
            chunks = chunk_text(markdown_text)
            if chunks:
                print(f"  [chunk] id={record_id} chunks={len(chunks)}")
                embeddings = get_embeddings(chunks, cfg)
                print(f"  [embed] id={record_id} embeddings={len(embeddings)} dim={len(embeddings[0]) if embeddings else 0}")
                metadata = {
                    "doc_type": "research_report",
                    "ts_code": ts_code_val or "",
                    "trade_date": trade_date,
                    "title": title,
                    "oss_url": oss_url,
                }
                success = ingest_vectors(
                    cfg,
                    doc_id=f"report_{record_id}",
                    doc_type="research_report",
                    chunks=chunks,
                    embeddings=embeddings,
                    metadata=metadata,
                )
                if success:
                    db.mark_report_vectorized(record_id)
                    print(f"  [ok]   id={record_id} done (chunks={len(chunks)})")
                else:
                    print(f"  [fail] id={record_id} ingest failed")
            else:
                print(f"  [skip] id={record_id} no chunks after chunking")

        except Exception as e:
            print(f"  [error] id={record_id}: {e}")


def run_task(task: dict, db: DbClient, cfg):
    """执行单个 YAML task 配置 (旧式 tasks: 列表 路径)。"""
    name = task.get("name", "(未命名)")
    doc_type = task.get("doc_type", "all")
    date_from = str(task["date_from"]) if task.get("date_from") else None
    date_to = str(task["date_to"]) if task.get("date_to") else None
    ts_code = task.get("ts_code") or None
    limit = int(task.get("limit", 50))
    offset = int(task.get("offset", 0))
    title_patterns = task.get("title_patterns") or None

    print(f"\n{'='*60}")
    print(f"[task] {name!r}  doc_type={doc_type}  date_from={date_from}  date_to={date_to}")
    print(f"       ts_code={ts_code}  limit={limit}  offset={offset}  title_patterns={title_patterns}")
    print(f"{'='*60}")

    _run_one_doc_type(
        db, cfg, doc_type,
        limit=limit, offset=offset,
        date_from=date_from, date_to=date_to,
        ts_code=ts_code, title_patterns=title_patterns,
    )


def _run_one_doc_type(
    db: DbClient, cfg, doc_type: str, *,
    limit: int, offset: int,
    date_from, date_to, ts_code, title_patterns,
):
    """根据 doc_type 调度 process_announcements / process_reports (内部 helper, 不打印头)。"""
    if doc_type in ("ann", "all"):
        process_announcements(
            db, cfg, limit=limit, offset=offset,
            date_from=date_from, date_to=date_to, ts_code=ts_code,
            title_patterns=title_patterns,
        )
    if doc_type in ("research", "all"):
        process_reports(
            db, cfg, limit=limit, offset=offset,
            date_from=date_from, date_to=date_to, ts_code=ts_code,
            title_patterns=title_patterns,
        )


def run_scenario(
    scenario: dict, db: DbClient, cfg, *, index: int, total: int,
    global_embedding: dict = None,
):
    """执行单个 scenario 配置 (新式 scenarios: 列表 路径, name 来自 scenario['name'])。

    ts_code.type 决定 limit/offset 的语义:
    - list:   limit/offset 作用于每个 ts_code (per-ts-code 分页)
    - select: limit/offset 作用于全局 SQL 分页
    - None:   limit/offset 作用于全局 SQL 分页 (与 select 同)

    embedding 优先级: scenario 级别 > 全局级别 > env 变量。
    global_embedding 为 YAML 顶层 `embedding:` 块, scenario 级别显式声明时覆盖它。

    ts_code 配置错误时直接抛 ConfigError, 由 main() 捕获并退出码 1
    (配置错了应停下来修, 不该静默跳过继续跑)。
    """
    name = scenario.get("name", f"scenario-{index}")
    doc_type = scenario.get("doc_type", "all")
    date_from = str(scenario["date_from"]) if scenario.get("date_from") else None
    date_to = str(scenario["date_to"]) if scenario.get("date_to") else None
    ts_code_raw = scenario.get("ts_code")
    title_patterns = scenario.get("title_patterns") or None
    limit = int(scenario.get("limit", 50))
    offset = int(scenario.get("offset", 0))

    # embedding 配置：scenario 级别 > 全局级别 > env 变量
    emb_override = scenario.get("embedding") or global_embedding
    scenario_cfg = config_with_embedding_override(cfg, emb_override)
    if emb_override:
        print(f"           embedding_model={scenario_cfg.embedding_model}")
        print(f"           embedding_base_url={scenario_cfg.embedding_base_url}")
        if scenario_cfg.embedding_provider_order:
            print(f"           provider_order={scenario_cfg.embedding_provider_order}")

    # from_yaml 配置错误直接抛, 不静默跳过 (fail closed)
    ts_filter = TsCodeFilter.from_yaml(ts_code_raw, scenario_name=name)

    ts_type = ts_filter.type or "(none=全局)"
    print(f"\n{'='*60}")
    print(f"[scenario] {index}/{total}  name={name!r}  doc_type={doc_type}  ts_code_type={ts_type}")
    print(f"           date_from={date_from}  date_to={date_to}")
    print(f"           limit={limit}  offset={offset}  title_patterns={title_patterns}")
    print(f"{'='*60}")

    if ts_filter.type == "list":
        for code_idx, code in enumerate(ts_filter.values, 1):
            print(f"\n[scenario] {name!r} ── ts_code {code_idx}/{len(ts_filter.values)}: {code} ──")
            _run_one_doc_type(
                db, scenario_cfg, doc_type,
                limit=limit, offset=offset,
                date_from=date_from, date_to=date_to,
                ts_code=code, title_patterns=title_patterns,
            )
    else:
        # select / None: 全局 SQL 分页
        _run_one_doc_type(
            db, scenario_cfg, doc_type,
            limit=limit, offset=offset,
            date_from=date_from, date_to=date_to,
            ts_code=ts_code_raw, title_patterns=title_patterns,
        )


def run_from_legacy_tasks(tasks: list, db: DbClient, cfg):
    """旧式 tasks: 列表 路径, 100% 向后兼容。"""
    if not tasks:
        print("[run] task config 中没有任何 task, 退出。")
        return
    enabled = [t for t in tasks if t.get("enabled", True)]
    print(f"[run] 共 {len(tasks)} 个 task, 其中 {len(enabled)} 个已启用。")
    for i, task in enumerate(enabled, 1):
        print(f"\n[run] ── Task {i}/{len(enabled)} ──")
        run_task(task, db, cfg)
    print("\n[run] 所有 task 执行完毕。")


def run_from_scenarios(scenarios: list, db: DbClient, cfg, global_embedding: dict = None):
    """新式 scenarios: 列表 路径, 每个 item 是 dict, 必须含 'name' 字段。

    global_embedding 为 YAML 顶层 `embedding:` 块, 作为所有 scenario 的默认 embedding
    配置; 单个 scenario 显式声明 `embedding` 时可覆盖它。

    失败模式 (fail closed): item 非 dict / 缺 name → 抛 ConfigError, 整体退出码 1。
    """
    if not scenarios:
        print("[run] scenarios 配置为空, 退出。")
        return
    if not isinstance(scenarios, list):
        raise ConfigError(
            f"scenarios 必须是 list (每项是 dict, 含 'name' 字段), 收到 {type(scenarios).__name__}"
        )
    # 校验每项, 顺手把 name 提取出来
    normalized: list = []
    for i, item in enumerate(scenarios):
        if not isinstance(item, dict):
            raise ConfigError(
                f"scenarios[{i}] 必须是 dict, 收到 {type(item).__name__}"
            )
        name = item.get("name")
        if not name or not isinstance(name, str):
            raise ConfigError(
                f"scenarios[{i}] 缺少必填字段 'name' (非空字符串)"
            )
        normalized.append((name, item))

    enabled = [(n, s) for n, s in normalized if s.get("enabled", True)]
    print(f"[run] 共 {len(scenarios)} 个 scenario, 其中 {len(enabled)} 个已启用。")
    for i, (name, scenario) in enumerate(enabled, 1):
        run_scenario(
            scenario, db, cfg, index=i, total=len(enabled),
            global_embedding=global_embedding,
        )
    print("\n[run] 所有 scenario 执行完毕。")


def dispatch_from_config(config_path: str, db: DbClient, cfg):
    """加载 YAML 配置, 校验 tasks/scenarios 互斥, 分派到对应路径。

    失败时抛 ConfigError (run.py 入口捕获并退出码 1)。
    """
    with open(config_path, "r", encoding="utf-8") as f:
        config = yaml.safe_load(f) or {}

    has_tasks = "tasks" in config
    has_scenarios = "scenarios" in config

    if has_tasks and has_scenarios:
        raise ConfigError(
            f"配置文件 {config_path!r} 同时包含 'tasks' 和 'scenarios', 二者互斥, 请只保留一个"
        )
    if not has_tasks and not has_scenarios:
        raise ConfigError(
            f"配置文件 {config_path!r} 必须包含 'tasks' 或 'scenarios' 之一"
        )

    # auth 配置（全局，从 YAML 顶层读取，覆盖 env）
    global_auth = config.get("auth")
    if global_auth:
        cfg = config_with_auth_override(cfg, global_auth)
        if cfg.login_username:
            print(f"[run] auth username={cfg.login_username}")
        # db 在 main() 里用原始 cfg 创建，需同步更新凭据
        db._login_username = cfg.login_username
        db._login_password = cfg.login_password
        db._jwt_token = None

    if has_tasks:
        print(f"[run] 配置文件使用旧式 'tasks' 格式 (向后兼容)")
        run_from_legacy_tasks(config["tasks"], db, cfg)
    else:
        print(f"[run] 配置文件使用新式 'scenarios' 格式")
        global_embedding = config.get("embedding")
        run_from_scenarios(config["scenarios"], db, cfg, global_embedding=global_embedding)


def main():
    parser = argparse.ArgumentParser(description="RAG Ingestion Script")
    # YAML 任务配置模式（优先）
    parser.add_argument(
        "--task-config",
        metavar="FILE",
        help="YAML 任务配置文件路径；指定后忽略 --doc-type / --limit / --offset",
    )
    # 传统 CLI 模式（向后兼容）
    parser.add_argument(
        "--doc-type",
        choices=["ann", "research", "all"],
        default="all",
        help="文档类型（默认 all），仅在未指定 --task-config 时生效",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=50,
        help="每种类型最多处理条数（默认 50），仅在未指定 --task-config 时生效",
    )
    parser.add_argument(
        "--offset",
        type=int,
        default=0,
        help="DB 查询偏移量（默认 0），仅在未指定 --task-config 时生效",
    )
    parser.add_argument(
        "--date-from",
        metavar="YYYYMMDD",
        default=None,
        help="起始日期过滤（含），仅在未指定 --task-config 时生效",
    )
    parser.add_argument(
        "--date-to",
        metavar="YYYYMMDD",
        default=None,
        help="截止日期过滤（含），仅在未指定 --task-config 时生效",
    )
    parser.add_argument(
        "--ts-code",
        metavar="CODE",
        default=None,
        help="股票代码精确过滤（如 000001.SZ），仅在未指定 --task-config 时生效",
    )
    parser.add_argument(
        "--title-pattern",
        metavar="PATTERN",
        action="append",
        dest="title_pattern",
        default=None,
        help="title 子串过滤（可重复指定，OR 关系），仅在未指定 --task-config 时生效。例：--title-pattern 年度公告 --title-pattern 年度报告",
    )
    args = parser.parse_args()

    # override=True: .env 文件里的值优先于 shell 里已 export 的值
    # 避免 "shell 里设了 localhost 但 .env 里改了地址不生效" 的情况
    load_dotenv(find_dotenv(), override=True)
    cfg = load_config()
    db = DbClient(cfg)

    if args.task_config:
        try:
            dispatch_from_config(args.task_config, db, cfg)
        except ConfigError as e:
            print(f"[run] [fatal] {e}")
            sys.exit(1)
    else:
        # 传统 CLI 模式
        if args.doc_type in ("ann", "all"):
            process_announcements(
                db, cfg,
                limit=args.limit,
                offset=args.offset,
                date_from=args.date_from,
                date_to=args.date_to,
                ts_code=args.ts_code,
                title_patterns=args.title_pattern,
            )
        if args.doc_type in ("research", "all"):
            process_reports(
                db, cfg,
                limit=args.limit,
                offset=args.offset,
                date_from=args.date_from,
                date_to=args.date_to,
                ts_code=args.ts_code,
                title_patterns=args.title_pattern,
            )
        print("[run] Done.")


if __name__ == "__main__":
    main()
