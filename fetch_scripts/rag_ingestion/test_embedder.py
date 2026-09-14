"""embedder.get_embeddings 的单元测试。

不依赖真实 OpenRouter / Fireworks / 任何外部服务。
通过 monkey-patch embedder.httpx.post 替换成 canned 响应,
验证 embedder 拼的 URL / headers / payload 正确, 响应解析与 index 排序也对。

覆盖矩阵（与 ccmax review S1 一致）：
  1. OpenRouter + provider.order      → payload 含 provider.order
  2. Fireworks   + provider.order 误配 → payload 不含 provider（endpoint 校验拦截）
  3. 无 provider.order                  → payload 不含 provider 字段
  4. dim=0                              → payload 不含 dimensions 字段
  5. 基本 URL / headers / payload 字段
  6. 空 texts 立即返回，不调 API
  7. 响应按 index 排序
  8. base_url 末尾 / 归一化
"""
import os
import sys
from typing import Any, Dict, List

import pytest

# 把 rag_ingestion 目录加入 path，允许 import 同包模块
_RAG_DIR = os.path.dirname(os.path.abspath(__file__))
if _RAG_DIR not in sys.path:
    sys.path.insert(0, _RAG_DIR)

import embedder as embedder_mod
from embedder import get_embeddings
from config import Config


# ── 假响应工具 ───────────────────────────────────────────────


class _FakeResponse:
    def __init__(self, status_code: int, body: Any):
        self.status_code = status_code
        self._body = body

    def json(self):
        return self._body

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


class _RecorderPost:
    """模拟 embedder.httpx.post，记录每次调用的 (url, json, headers, timeout)."""

    def __init__(self, response: _FakeResponse):
        self.response = response
        self.calls: List[Dict[str, Any]] = []

    def __call__(self, url, json=None, headers=None, timeout=None, **kwargs):
        self.calls.append({
            "url": url,
            "json": json,
            "headers": headers or {},
            "timeout": timeout,
        })
        return self.response


def _make_config(base_url: str = "https://openrouter.ai/api/v1",
                 model: str = "openai/text-embedding-3-small",
                 dim: int = 1024,
                 provider_order: list = None) -> Config:
    return Config(
        service_base_url="http://localhost:8090",
        jina_api_key="",
        embedding_base_url=base_url,
        embedding_api_key="ek_test",
        embedding_model=model,
        embedding_dim=dim,
        login_username="admin",
        login_password="password",
        embedding_provider_order=provider_order if provider_order is not None else [],
    )


def _patched_embedder(monkeypatch, response: _FakeResponse):
    rec = _RecorderPost(response)
    monkeypatch.setattr(embedder_mod.httpx, "post", rec)
    return rec


def _ok_response(*dims_or_vectors):
    """构造 OpenAI 兼容的 {data: [{index, embedding}, ...]} 响应。

    dims_or_vectors 是 (index, vector) 元组, 故意打乱顺序测排序。
    """
    return {
        "data": [
            {"index": idx, "embedding": vec}
            for idx, vec in dims_or_vectors
        ]
    }


# ── 1. OpenRouter + provider.order 触发 ──────────────────────


def test_openrouter_with_provider_order_sends_provider_payload(monkeypatch):
    cfg = _make_config(provider_order=["openai", "azure"])
    rec = _patched_embedder(monkeypatch, _FakeResponse(200, _ok_response((0, [0.1, 0.2]))))

    out = get_embeddings(["hello"], cfg)

    assert out == [[0.1, 0.2]]
    assert len(rec.calls) == 1
    payload = rec.calls[0]["json"]
    assert payload["provider"] == {"order": ["openai", "azure"]}
    assert payload["model"] == "openai/text-embedding-3-small"
    assert payload["input"] == ["hello"]


# ── 2. Fireworks + provider.order 误配 → M1 修了才过 ─────────


def test_fireworks_with_provider_order_omits_provider_payload(monkeypatch):
    """Fireworks endpoint + provider.order 误配 → payload 不应含 provider 字段。

    这条是 M1（endpoint 校验）的关键回归保护。
    """
    cfg = _make_config(
        base_url="https://api.fireworks.ai/inference/v1",
        model="nomic-ai/nomic-embed-text-v1.5",
        dim=768,
        provider_order=["openai", "azure"],  # 用户照抄 OpenRouter 例子忘了删
    )
    rec = _patched_embedder(monkeypatch, _FakeResponse(200, _ok_response((0, [0.3]))))

    out = get_embeddings(["x"], cfg)

    assert out == [[0.3]]
    payload = rec.calls[0]["json"]
    assert "provider" not in payload, (
        "Fireworks endpoint 不应发送 provider.order 字段 "
        f"(实际 payload: {payload})"
    )


# ── 3. 未声明 provider.order ─────────────────────────────────


def test_no_provider_order_omits_provider_payload(monkeypatch):
    cfg = _make_config(provider_order=[])
    rec = _patched_embedder(monkeypatch, _FakeResponse(200, _ok_response((0, [0.5]))))

    get_embeddings(["x"], cfg)

    assert "provider" not in rec.calls[0]["json"]


# ── 4. dim=0 → 不发送 dimensions 字段 ───────────────────────


def test_dim_zero_omits_dimensions_field(monkeypatch):
    cfg = _make_config(dim=0)
    rec = _patched_embedder(monkeypatch, _FakeResponse(200, _ok_response((0, [0.5]))))

    get_embeddings(["x"], cfg)

    assert "dimensions" not in rec.calls[0]["json"]


def test_dim_positive_sends_dimensions_field(monkeypatch):
    cfg = _make_config(dim=768)
    rec = _patched_embedder(monkeypatch, _FakeResponse(200, _ok_response((0, [0.5]))))

    get_embeddings(["x"], cfg)

    assert rec.calls[0]["json"]["dimensions"] == 768


# ── 5. 基本 URL / headers / payload 字段 ─────────────────────


def test_basic_url_headers_and_payload(monkeypatch):
    cfg = _make_config()
    rec = _patched_embedder(monkeypatch, _FakeResponse(200, _ok_response((0, [0.1]))))

    get_embeddings(["a", "b"], cfg)

    call = rec.calls[0]
    assert call["url"] == "https://openrouter.ai/api/v1/embeddings"
    assert call["headers"]["Authorization"] == "Bearer ek_test"
    assert call["headers"]["Content-Type"] == "application/json"
    assert call["timeout"] == 60.0
    assert call["json"]["input"] == ["a", "b"]


# ── 6. 空 texts 立即返回 ────────────────────────────────────


def test_empty_texts_returns_immediately(monkeypatch):
    cfg = _make_config()
    rec = _patched_embedder(monkeypatch, _FakeResponse(200, _ok_response()))

    out = get_embeddings([], cfg)

    assert out == []
    assert rec.calls == [], "空 texts 不应触发 HTTP 请求"


# ── 7. 响应按 index 排序 ────────────────────────────────────


def test_response_sorted_by_index(monkeypatch):
    """服务端可能乱序返回，embedder 应按 index 排序。"""
    body = {
        "data": [
            {"index": 2, "embedding": [0.3]},
            {"index": 0, "embedding": [0.1]},
            {"index": 1, "embedding": [0.2]},
        ]
    }
    cfg = _make_config()
    _patched_embedder(monkeypatch, _FakeResponse(200, body))

    out = get_embeddings(["a", "b", "c"], cfg)

    assert out == [[0.1], [0.2], [0.3]]


# ── 8. base_url 末尾 / 归一化 ───────────────────────────────


def test_base_url_trailing_slash_normalized(monkeypatch):
    cfg = _make_config(base_url="https://openrouter.ai/api/v1/")
    rec = _patched_embedder(monkeypatch, _FakeResponse(200, _ok_response((0, [0.5]))))

    get_embeddings(["x"], cfg)

    # 不应出现 //embeddings（双斜杠）
    assert rec.calls[0]["url"] == "https://openrouter.ai/api/v1/embeddings"
