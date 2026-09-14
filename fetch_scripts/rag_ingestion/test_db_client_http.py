"""DbClient HTTP 路径的单元测试。

不依赖真实 DB / frontend / 任何外部服务。
通过 monkey-patch DbClient._post 替换成 canned 响应,
验证 db_client 的所有方法把请求体拼对了、调用了正确的端点、
状态写入的 payload 也对得上。

设计要点:
- 不再依赖 psycopg2 / PG_DSN / DSN 环境变量
- service_base_url 可以是任意字符串, 路径拼接是测点
- 错误响应 (HTTP 4xx/5xx) 必须 raise RuntimeError, 不允许静默吞错
- 旧式 str / list ts_code 直接走 HTTP tsCode 字段,
  dict (type=list/select) 走 HTTP tsCode 字段 (服务端再次校验)
"""
import os
import sys
from typing import Any, Dict, List

import pytest

# 把 rag_ingestion 目录加入 path, 允许 import 同包模块
_RAG_DIR = os.path.dirname(os.path.abspath(__file__))
if _RAG_DIR not in sys.path:
    sys.path.insert(0, _RAG_DIR)

import db_client as db_client_mod
from db_client import DbClient
from config import Config
from ts_code_filter import TsCodeFilter


# ── 假响应工具 ───────────────────────────────────────────────


class _FakeResponse:
    def __init__(self, status_code: int, body: Any):
        self.status_code = status_code
        self._body = body

    def json(self):
        return self._body

    @property
    def text(self) -> str:
        return str(self._body)


class _RecorderSession:
    """模拟 requests.Session.post, 记录每次调用的 (url, json, headers, timeout)."""

    def __init__(self, response: _FakeResponse):
        self.response = response
        self.calls: List[Dict[str, Any]] = []

    def post(self, url, json=None, headers=None, timeout=None, **kwargs):
        self.calls.append({
            "url": url,
            "json": json,
            "headers": headers or {},
            "timeout": timeout,
        })
        return self.response


# ── 工具: 构造最小 Config + 替换 session ───────────────────────


def _make_config(base_url: str = "http://localhost:8090") -> Config:
    return Config(
        service_base_url=base_url,
        jina_api_key="",
        embedding_base_url="https://example.invalid/v1",
        embedding_api_key="ek",
        embedding_model="m",
        embedding_dim=1024,
        login_username="admin",
        login_password="password",
    )


def _patched_client(monkeypatch, response: _FakeResponse) -> DbClient:
    cfg = _make_config()
    client = DbClient(cfg)
    client._jwt = "jwt123"
    recorder = _RecorderSession(response)
    client.session = recorder
    return client, recorder


# ── 拉记录: 基本形态 ─────────────────────────────────────────


def test_get_unprocessed_announcements_basic_url_and_payload(monkeypatch):
    """ann 文档类型 → /rag/records/list-unprocessed, docType=announcement。"""
    body = {
        "docType": "announcement",
        "count": 2,
        "records": [
            {"id": 1, "ts_code": "000001.SZ", "ann_date": 1700000000000,
             "title": "x", "url": "u1"},
            {"id": 2, "ts_code": "000002.SZ", "ann_date": 1700001000000,
             "title": "y", "url": "u2"},
        ],
    }
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, body))

    records = client.get_unprocessed_announcements(limit=10, offset=5)

    assert len(records) == 2
    assert records[0]["id"] == 1
    assert len(rec.calls) == 1
    call = rec.calls[0]
    assert call["url"] == "http://localhost:8090/rag/records/list-unprocessed"
    assert call["json"]["docType"] == "announcement"
    assert call["json"]["limit"] == 10
    assert call["json"]["offset"] == 5
    # 没传 date/ts_code/title 时不上送这些字段
    assert "dateFrom" not in call["json"]
    assert "dateTo" not in call["json"]
    assert "tsCode" not in call["json"]
    assert "titlePatterns" not in call["json"]


def test_get_unprocessed_reports_basic_url_and_payload(monkeypatch):
    """research 文档类型 → /rag/records/list-unprocessed, docType=research_report。"""
    body = {
        "docType": "research_report",
        "count": 1,
        "records": [
            {"id": 7, "ts_code": "600519.SH", "trade_date": 1700002000000,
             "title": "t", "abstr": "a", "url": "u"},
        ],
    }
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, body))

    records = client.get_unprocessed_reports()

    assert len(records) == 1
    assert records[0]["id"] == 7
    assert rec.calls[0]["url"] == "http://localhost:8090/rag/records/list-unprocessed"
    assert rec.calls[0]["json"]["docType"] == "research_report"


# ── 拉记录: 过滤条件透传 ──────────────────────────────────────


def test_get_unprocessed_announcements_passes_date_and_titles(monkeypatch):
    body = {"docType": "announcement", "count": 0, "records": []}
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, body))

    client.get_unprocessed_announcements(
        limit=20, offset=0,
        date_from="20240101", date_to="20241231",
        title_patterns=["年度报告", "annual"],
    )

    p = rec.calls[0]["json"]
    assert p["dateFrom"] == "20240101"
    assert p["dateTo"] == "20241231"
    assert p["titlePatterns"] == ["年度报告", "annual"]


def test_get_unprocessed_announcements_passes_title_match(monkeypatch):
    body = {"docType": "announcement", "count": 0, "records": []}
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, body))

    client.get_unprocessed_announcements(
        title_match={
            "mode": "contains",
            "include": ["年度报告"],
            "exclude": ["摘要"],
        },
    )

    p = rec.calls[0]["json"]
    assert "titlePatterns" not in p
    assert p["titleMatch"] == {
        "mode": "contains",
        "includeMode": "any",
        "include": ["年度报告"],
        "exclude": ["摘要"],
    }


def test_get_unprocessed_rejects_title_patterns_and_title_match(monkeypatch):
    body = {"docType": "announcement", "count": 0, "records": []}
    client, _ = _patched_client(monkeypatch, _FakeResponse(200, body))

    with pytest.raises(Exception, match="互斥"):
        client.get_unprocessed_announcements(
            title_patterns=["年度报告"],
            title_match={"mode": "contains", "include": ["年度报告"]},
        )


def test_get_unprocessed_announcements_str_ts_code_passes_through(monkeypatch):
    body = {"docType": "announcement", "count": 0, "records": []}
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, body))

    client.get_unprocessed_announcements(ts_code="000001.SZ")

    assert rec.calls[0]["json"]["tsCode"] == "000001.SZ"


def test_get_unprocessed_announcements_list_ts_code_passes_through(monkeypatch):
    body = {"docType": "announcement", "count": 0, "records": []}
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, body))

    client.get_unprocessed_announcements(
        ts_code=["000001.SZ", "000002.SZ"]
    )

    assert rec.calls[0]["json"]["tsCode"] == ["000001.SZ", "000002.SZ"]


def test_get_unprocessed_announcements_dict_ts_code_passes_through(monkeypatch):
    """type=select 走 dict 直传, 不在客户端展开。"""
    body = {"docType": "announcement", "count": 0, "records": []}
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, body))

    client.get_unprocessed_announcements(
        ts_code={
            "type": "select",
            "conditions": {
                "index_codes": ["000300.SH"],
                "member_date_from": "20240101",
                "member_date_to": "20241231",
            },
        }
    )

    p = rec.calls[0]["json"]
    assert p["tsCode"]["type"] == "select"
    assert p["tsCode"]["conditions"]["index_codes"] == ["000300.SH"]


def test_get_unprocessed_announcements_TsCodeFilter_instance_serialized(monkeypatch):
    """TsCodeFilter 实例也支持, 拆成 dict 上送。"""
    body = {"docType": "announcement", "count": 0, "records": []}
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, body))

    f = TsCodeFilter.from_yaml(
        {"type": "list", "values": ["600519.SH", "601318.SH"]},
        scenario_name="S",
    )
    client.get_unprocessed_announcements(ts_code=f)

    assert rec.calls[0]["json"]["tsCode"] == {
        "type": "list",
        "values": ["600519.SH", "601318.SH"],
    }


def test_get_unprocessed_rejects_unknown_doc_type():
    """doc_type 传错 → ValueError (本地校验, 不发 HTTP)."""
    client = DbClient(_make_config())
    with pytest.raises(ValueError, match="doc_type 必须是"):
        client._post_unprocessed(
            doc_type="bogus", limit=1, offset=0,
            date_from=None, date_to=None,
            ts_code=None, title_patterns=None,
        )


# ── Bearer / 鉴权头 ──────────────────────────────────────────


def test_post_includes_admin_jwt_header(monkeypatch):
    body = {"docType": "announcement", "count": 0, "records": []}
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, body))

    client.get_unprocessed_announcements()

    headers = rec.calls[0]["headers"]
    assert headers["Authorization"] == "Bearer jwt123"
    assert headers["Content-Type"] == "application/json"


# ── 错误响应: 必须 raise 不吞错 ──────────────────────────────


def test_post_raises_on_4xx(monkeypatch):
    client = DbClient(_make_config())
    client._jwt = "jwt123"
    client.session = _RecorderSession(_FakeResponse(400, {"error": "docType is required"}))

    with pytest.raises(RuntimeError, match="HTTP 400"):
        client.get_unprocessed_announcements()


def test_post_raises_on_5xx(monkeypatch):
    client = DbClient(_make_config())
    client._jwt = "jwt123"
    client.session = _RecorderSession(_FakeResponse(500, "boom"))

    with pytest.raises(RuntimeError, match="HTTP 500"):
        client.get_unprocessed_announcements()


# ── 状态写入 ──────────────────────────────────────────────────


def test_update_announcement_oss_url_payload(monkeypatch):
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, {"affected": 1}))

    client.update_announcement_oss_url(record_id=42, oss_url="alphafrog-rag/ann/42.md")

    call = rec.calls[0]
    assert call["url"] == "http://localhost:8090/rag/records/mark-oss-uploaded"
    assert call["json"] == {
        "docType": "announcement",
        "recordId": 42,
        "ossKey": "alphafrog-rag/ann/42.md",
    }


def test_update_report_oss_url_payload(monkeypatch):
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, {"affected": 1}))

    client.update_report_oss_url(record_id=99, oss_url="alphafrog-rag/research/99.md")

    call = rec.calls[0]
    assert call["url"] == "http://localhost:8090/rag/records/mark-oss-uploaded"
    assert call["json"]["docType"] == "research_report"
    assert call["json"]["recordId"] == 99
    assert call["json"]["ossKey"] == "alphafrog-rag/research/99.md"


def test_mark_announcement_vectorized_payload(monkeypatch):
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, {"affected": 1}))

    client.mark_announcement_vectorized(record_id=42)

    call = rec.calls[0]
    assert call["url"] == "http://localhost:8090/rag/records/mark-vectorized"
    # mark-vectorized 不带 ossKey
    assert call["json"] == {"docType": "announcement", "recordId": 42}


def test_mark_report_vectorized_payload(monkeypatch):
    client, rec = _patched_client(monkeypatch, _FakeResponse(200, {"affected": 1}))

    client.mark_report_vectorized(record_id=99)

    assert rec.calls[0]["json"] == {"docType": "research_report", "recordId": 99}


# ── 反规范化: 服务端毫秒字段保留 ─────────────────────────────


def test_records_passthrough_preserves_bigint_ms(monkeypatch):
    """服务端返回的 ann_date BIGINT 毫秒必须原样透传给 run.py 旧调用方。"""
    body = {
        "docType": "announcement",
        "count": 1,
        "records": [
            {"id": 1, "ts_code": "000001.SZ", "ann_date": 1748736000000,
             "title": "t", "url": "u"},
        ],
    }
    client, _ = _patched_client(monkeypatch, _FakeResponse(200, body))

    records = client.get_unprocessed_announcements()

    assert records[0]["ann_date"] == 1748736000000
    assert isinstance(records[0]["ann_date"], int)


# ── 配置: 旧字段已彻底消失 ───────────────────────────────────


def test_config_has_no_db_dsn_or_legacy_endpoints():
    """新 Config 不能含 db_dsn / ingest_endpoint / upload_doc_endpoint。"""
    cfg = _make_config()
    assert not hasattr(cfg, "db_dsn")
    assert not hasattr(cfg, "ingest_endpoint")
    assert not hasattr(cfg, "upload_doc_endpoint")
    assert hasattr(cfg, "service_base_url")


def test_db_client_module_does_not_import_psycopg2():
    """db_client.py 不再依赖 psycopg2, fetch_scripts 可在无 PG 客户端环境运行。"""
    import ast
    tree = ast.parse(open(db_client_mod.__file__, "r", encoding="utf-8").read())
    imported_modules: List[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for n in node.names:
                imported_modules.append(n.name)
        elif isinstance(node, ast.ImportFrom):
            if node.module:
                imported_modules.append(node.module)
    psycopg2_imports = [m for m in imported_modules if m.startswith("psycopg2")]
    assert psycopg2_imports == [], f"db_client.py 仍 import 了 psycopg2: {psycopg2_imports}"
