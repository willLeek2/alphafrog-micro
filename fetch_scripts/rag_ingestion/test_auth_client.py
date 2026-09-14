"""管理员用户登录客户端单元测试。"""
from __future__ import annotations

import os
import sys

import pytest

_RAG_DIR = os.path.dirname(os.path.abspath(__file__))
if _RAG_DIR not in sys.path:
    sys.path.insert(0, _RAG_DIR)

from auth_client import _clear_jwt_cache_for_tests, get_admin_jwt
from config import Config


class _Response:
    def __init__(self, status_code, body, text=None):
        self.status_code = status_code
        self._body = body
        self.text = text if text is not None else str(body)

    def json(self):
        return self._body


class _Session:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def post(self, url, **kwargs):
        self.calls.append((url, kwargs))
        return self.responses.pop(0)


def _config(username="admin", password="password"):
    return Config(
        service_base_url="http://localhost:8090",
        jina_api_key="",
        embedding_base_url="https://example.invalid/v1",
        embedding_api_key="embedding-key",
        embedding_model="model",
        embedding_dim=1024,
        login_username=username,
        login_password=password,
    )


def setup_function():
    _clear_jwt_cache_for_tests()


def test_login_returns_and_reuses_jwt():
    session = _Session([_Response(200, {"token": "jwt-value"})])

    assert get_admin_jwt(_config(), session) == "jwt-value"
    assert get_admin_jwt(_config(), session) == "jwt-value"
    assert len(session.calls) == 1


def test_existing_login_logs_out_then_retries():
    session = _Session([
        _Response(400, {}, "User already logged in"),
        _Response(200, {}),
        _Response(200, "jwt-after-relogin", '"jwt-after-relogin"'),
    ])

    assert get_admin_jwt(_config(), session) == "jwt-after-relogin"
    assert [call[0] for call in session.calls] == [
        "http://localhost:8090/api/auth/login",
        "http://localhost:8090/api/auth/logout",
        "http://localhost:8090/api/auth/login",
    ]


def test_missing_credentials_fail_closed():
    with pytest.raises(RuntimeError, match="login_username/login_password"):
        get_admin_jwt(_config(username="", password=""), _Session([]))
