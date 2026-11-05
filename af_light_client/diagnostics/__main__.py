"""诊断采集入口。创建运行仍由 af_light_client 的主命令负责。"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from .collect import collect_run
from .read_client import CollectionError, safe_error
from .verify_package import verify_package


def _build_collect_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="按已有运行编号生成诊断包")
    sub = parser.add_subparsers(dest="command", required=True)
    collect = sub.add_parser("collect")
    collect.add_argument("--run-id", required=True)
    collect.add_argument("--output-dir", required=True)
    collect.add_argument("--base-url", default=os.environ.get("ALPHAFROG_BASE_URL", ""))
    collect.add_argument("--admin-username", default=os.environ.get("ALPHAFROG_ADMIN_USERNAME", ""))
    collect.add_argument("--admin-password", default=None)
    collect.add_argument("--allow-insecure-http", action="store_true")
    verify = sub.add_parser("verify")
    verify.add_argument("--package-dir", required=True)
    return parser


def _namespace_for_collection(args: argparse.Namespace) -> argparse.Namespace:
    if not args.base_url:
        raise CollectionError("采集必须提供 --base-url 或 ALPHAFROG_BASE_URL")
    return argparse.Namespace(
        run_id=args.run_id,
        output_dir=args.output_dir,
        admin_username=args.admin_username,
        admin_password=args.admin_password,
        admin_password_stdin=False,
        base_url=args.base_url,
        allow_insecure_http=bool(args.allow_insecure_http),
        admin_login_path="/admin/login",
        logout_path="/api/auth/logout",
        force_relogin=False,
        allow_active_run=False,
        sections=None,
        exclude_sections=None,
        run_dir=None,
        repo_root=None,
        jobs=4,
        retries=2,
        timeout_seconds=30.0,
        max_part_size=262144,
        max_download_bytes=64 * 1024 * 1024,
        insecure=False,
        list_sections=False,
    )


def main(argv: list[str] | None = None) -> int:
    parser = _build_collect_parser()
    args = parser.parse_args(argv)
    if args.command == "verify":
        result = verify_package(Path(args.package_dir))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result["mismatchCount"] == 0 else 2
    try:
        outcome = collect_run(_namespace_for_collection(args))
    except CollectionError as exc:
        print(f"ERROR: {safe_error(exc)}", file=sys.stderr)
        return 1
    print(json.dumps({"outputDir": outcome["summary"]["outputDir"], "logAssociation": outcome["logAssociation"]}, ensure_ascii=False))
    return 0 if outcome["summary"].get("complete") else 2


if __name__ == "__main__":
    raise SystemExit(main())
