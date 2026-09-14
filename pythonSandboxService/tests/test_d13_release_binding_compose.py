"""D13 release-binding compose 契约测试。

codex a1b749ad 裁定 ccmax 单 writer 完成 release-binding:
compose 同名 canonical millis 注入到 python-sandbox-service 与
python-sandbox-gateway-service 两个 service;本测试钉死以下不变量:

1. Python service 同时暴露 ``AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS`` 与
   ``AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS``;
2. Gateway service 至少暴露 ``AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS``;
3. 两 service 的 ``_MILLIS`` 接同一个 outer env var (binding closure);
4. compose 内置的默认值能通过 ``validate_max_task_timeout_binding`` 缝,
   并且满足 ``seconds * 1000 == millis``.
"""

from __future__ import annotations

import pathlib
import re

import pytest

try:
    import yaml  # type: ignore[import-untyped]
except ImportError:  # pragma: no cover - 仅在缺 pyyaml 时跳过
    yaml = None  # type: ignore[assignment]


# 仓库根: tests/ 上溯 3 级回到 repo root
REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
COMPOSE_PATH = REPO_ROOT / "docker-compose.yml"

_SECONDS_ENV = "AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS"
_MILLIS_ENV = "AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS"

_ENV_PATTERN = re.compile(r"^\$\{([A-Z0-9_]+)(?::-(.*))?\}$")


def _parse_env_var(spec: str) -> tuple[str | None, str | None]:
    """解析 compose 环境变量值。

    - ``${VAR:-default}`` -> (VAR, default)
    - ``${VAR}`` -> (VAR, None)
    - 纯字面量 -> (None, value)
    """
    if not isinstance(spec, str):
        return (None, None)
    match = _ENV_PATTERN.match(spec.strip())
    if match:
        var = match.group(1)
        default = match.group(2)
        return (var, default)
    return (None, spec)


@pytest.fixture(scope="module")
def compose_services():
    """加载 docker-compose.yml 的 services 映射。"""
    if yaml is None:
        pytest.skip("pyyaml 未安装,跳过 compose 契约测试")
    if not COMPOSE_PATH.exists():
        pytest.skip(f"compose 文件不存在: {COMPOSE_PATH}")
    with COMPOSE_PATH.open("r", encoding="utf-8") as fp:
        compose = yaml.safe_load(fp)
    return compose["services"]


def test_python_service_has_canonical_timeout_envs(compose_services):
    """Python service 必须同时挂 SECONDS + MILLIS canonical env。"""
    env = compose_services["python-sandbox-service"]["environment"]
    assert _SECONDS_ENV in env, f"Python service 缺 {_SECONDS_ENV}"
    assert _MILLIS_ENV in env, f"Python service 缺 {_MILLIS_ENV}"


def test_gateway_service_has_canonical_timeout_millis(compose_services):
    """Gateway service 至少挂 MILLIS canonical env。"""
    env = compose_services["python-sandbox-gateway-service"]["environment"]
    assert _MILLIS_ENV in env, f"Gateway service 缺 {_MILLIS_ENV}"


def test_millis_substitution_source_identical(compose_services):
    """两 service 的 _MILLIS 必须接同一个 outer env var (binding closure)。"""
    py_env = compose_services["python-sandbox-service"]["environment"]
    gw_env = compose_services["python-sandbox-gateway-service"]["environment"]

    py_var, _ = _parse_env_var(py_env[_MILLIS_ENV])
    gw_var, _ = _parse_env_var(gw_env[_MILLIS_ENV])

    assert py_var == _MILLIS_ENV, f"Python _MILLIS 接的不是 {_MILLIS_ENV}: {py_var}"
    assert gw_var == _MILLIS_ENV, f"Gateway _MILLIS 接的不是 {_MILLIS_ENV}: {gw_var}"


def test_default_values_pass_binding_seam(compose_services):
    """compose 默认值必须通过 validate_max_task_timeout_binding 缝。"""
    from app.config import validate_max_task_timeout_binding

    env = compose_services["python-sandbox-service"]["environment"]
    _, seconds_default = _parse_env_var(env[_SECONDS_ENV])
    _, millis_default = _parse_env_var(env[_MILLIS_ENV])

    assert seconds_default is not None, "Python SECONDS 必须有默认值 (fail-safe deployment)"
    assert millis_default is not None, "Python MILLIS 必须有默认值 (fail-safe deployment)"

    effective = validate_max_task_timeout_binding(seconds_default, millis_default)
    assert effective > 0, f"默认值不能通过 binding 缝或非正: effective={effective}"


def test_default_seconds_times_1000_equals_millis(compose_services):
    """compose 默认值必须满足 seconds * 1000 == millis (decimal-exact)。"""
    env = compose_services["python-sandbox-service"]["environment"]
    _, seconds_default = _parse_env_var(env[_SECONDS_ENV])
    _, millis_default = _parse_env_var(env[_MILLIS_ENV])

    assert seconds_default is not None and millis_default is not None
    assert int(seconds_default) * 1000 == int(millis_default), (
        f"默认值 seconds*1000 != millis: {seconds_default}*1000 vs {millis_default}"
    )
