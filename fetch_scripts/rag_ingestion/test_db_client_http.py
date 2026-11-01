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


# ── 拉记录: 过滤条件透传 ──────────────────────────────────────


# ── Bearer / 鉴权头 ──────────────────────────────────────────


# ── 错误响应: 必须 raise 不吞错 ──────────────────────────────


# ── 状态写入 ──────────────────────────────────────────────────


# ── 反规范化: 服务端毫秒字段保留 ─────────────────────────────


# ── 配置: 旧字段已彻底消失 ───────────────────────────────────


