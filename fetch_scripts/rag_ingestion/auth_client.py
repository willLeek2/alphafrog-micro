"""管理员用户登录与 JWT 复用。"""
from __future__ import annotations

import threading
from typing import Any, Dict, Optional, Tuple

import requests

from config import Config


_JWT_CACHE: Dict[Tuple[str, str, str], str] = {}
_JWT_CACHE_LOCK = threading.Lock()


def get_admin_jwt(cfg: Config, session: Optional[requests.Session] = None) -> str:
    """使用管理员用户名/密码登录，按进程缓存服务端签发的 JWT。"""
    base_url = cfg.service_base_url.rstrip("/")
    username = cfg.login_username.strip()
    password = cfg.login_password
    if not username or not password:
        raise RuntimeError("需要配置管理员 login_username/login_password")

    cache_key = (base_url, username, password)
    with _JWT_CACHE_LOCK:
        cached = _JWT_CACHE.get(cache_key)
        if cached:
            return cached

        request_session = session or requests.Session()
        jwt = _login(request_session, base_url, username, password)
        _JWT_CACHE[cache_key] = jwt
        return jwt


def _login(session: requests.Session, base_url: str, username: str, password: str) -> str:
    payload = {"username": username, "password": password}
    response = session.post(
        f"{base_url}/api/auth/login", json=payload, timeout=30.0
    )
    if response.status_code == 400 and "User already logged in" in response.text:
        session.post(
            f"{base_url}/api/auth/logout",
            json={"username": username},
            timeout=10.0,
        )
        response = session.post(
            f"{base_url}/api/auth/login", json=payload, timeout=30.0
        )

    if response.status_code != 200:
        raise RuntimeError(
            f"管理员登录失败: HTTP {response.status_code} body={response.text!r}"
        )
    jwt = _extract_jwt(_safe_json(response), response.text)
    if not jwt:
        raise RuntimeError("管理员登录成功但响应中未提取到 JWT")
    return jwt


def _safe_json(response) -> Any:
    try:
        return response.json()
    except Exception:
        return None


def _extract_jwt(payload: Any, raw_text: str) -> str:
    if isinstance(payload, str) and payload.strip():
        return payload.strip()
    if isinstance(payload, dict):
        for key in ("token", "access_token", "accessToken", "jwt"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        nested = payload.get("data")
        if nested is not None:
            extracted = _extract_jwt(nested, "")
            if extracted:
                return extracted
    return raw_text.strip().strip('"')


def _clear_jwt_cache_for_tests() -> None:
    with _JWT_CACHE_LOCK:
        _JWT_CACHE.clear()
